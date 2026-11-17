package de.billery.saas.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Records sign-ins for the operator ({@code sign_in_log}, SaaS migration V8): which user signed in
 * to which company, when and from which address. Only the operator reads them (the application may
 * only insert); they are kept 90 days.
 *
 * <p>The backend sees access tokens, not the sign-in at the identity provider, so a sign-in is a
 * <em>session of the provider</em> that reaches a company for the first time. The session is told
 * by the token's {@code auth_time} (when the user authenticated), else its {@code sid} (the
 * provider's session id); renewed tokens of the same session carry the same value and add nothing.
 * A provider that puts neither into its access tokens gets a fallback: the first request of a user
 * in a company after {@value #IDLE_MINUTES} minutes without one counts as a new sign-in. The row
 * stores only a SHA-256 of issuer, subject and that value, never a token.
 *
 * <p>Duplicates are refused by the database (unique per company, user and session), so several
 * instances write each sign-in once; a small cache per instance keeps the database out of every
 * request. Recording never blocks a request: a failure is logged.
 */
@Component
@Profile("saas")
public class SignInLog {

  /** Minutes without a request after which the fallback counts the next one as a sign-in. */
  static final int IDLE_MINUTES = 30;

  private static final Logger LOGGER = Logger.getLogger(SignInLog.class.getName());

  /** Sessions this instance has written (or found written), and the last request per user. */
  private static final int CACHE_SIZE = 10_000;

  /** How often an instance asks the database to drop rows past the retention. */
  private static final Duration PRUNE_EVERY = Duration.ofHours(1);

  private final JdbcClient jdbc;
  private final Clock clock;
  private final Map<String, Boolean> written = lru();
  private final Map<String, Instant> lastRequest = lru();
  private Instant lastPrune = Instant.EPOCH;

  /**
   * Creates the log.
   *
   * @param jdbc the tenant-aware database access
   */
  @Autowired
  public SignInLog(JdbcClient jdbc) {
    this(jdbc, Clock.systemUTC());
  }

  /**
   * Creates the log with a clock of its own (tests).
   *
   * @param jdbc the tenant-aware database access
   * @param clock the time of the requests
   */
  SignInLog(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  private static <V> Map<String, V> lru() {
    return new LinkedHashMap<>(256, 0.75f, true) {
      @Override
      protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
        return size() > CACHE_SIZE;
      }
    };
  }

  /**
   * Records the request's session if it is new for this user and company. Call it once the user's
   * membership of the company is confirmed, with the company active on the thread (the row's
   * company comes from the connection, like every tenant row).
   *
   * @param token the validated access token
   * @param user the user of the token
   * @param tenant the company the request acts in
   * @param sourceIp the client address (the proxy's {@code X-Forwarded-For}, as for the limits)
   */
  public void record(Jwt token, UUID user, int tenant, String sourceIp) {
    Instant now = clock.instant();
    String session = sessionOf(token, user, tenant, now);
    if (session == null) {
      return;
    }
    String hash =
        sha256(token.getClaimAsString("iss") + '\n' + token.getSubject() + '\n' + session);
    String key = tenant + ":" + user + ":" + hash;
    synchronized (written) {
      if (written.containsKey(key)) {
        return;
      }
    }
    try {
      jdbc.sql(
              "INSERT INTO sign_in_log (tenant_id, user_id, source_ip, session_hash)"
                  + " VALUES (:tenant, :user, :ip, :hash) ON CONFLICT DO NOTHING")
          .param("tenant", tenant)
          .param("user", user)
          .param("ip", sourceIp == null || sourceIp.length() > 45 ? null : sourceIp)
          .param("hash", hash)
          .update();
      synchronized (written) {
        written.put(key, Boolean.TRUE);
      }
      pruneNowAndThen(now);
    } catch (DataAccessException e) {
      LOGGER.log(Level.WARNING, "A sign-in could not be recorded", e);
    }
  }

  /**
   * The value that identifies the provider session: {@code auth_time}, else {@code sid}, else the
   * time of the first request after an idle gap; null when the request continues a fallback
   * session.
   */
  private String sessionOf(Jwt token, UUID user, int tenant, Instant now) {
    Object authTime = token.getClaims().get("auth_time");
    if (authTime != null) {
      return "auth_time:"
          + (authTime instanceof Instant instant ? instant.getEpochSecond() : authTime);
    }
    String sid = token.getClaimAsString("sid");
    if (sid != null && !sid.isBlank()) {
      return "sid:" + sid;
    }
    String key = tenant + ":" + user;
    synchronized (lastRequest) {
      Instant previous = lastRequest.put(key, now);
      boolean resumed =
          previous != null && Duration.between(previous, now).toMinutes() < IDLE_MINUTES;
      return resumed ? null : "first-request:" + now.getEpochSecond();
    }
  }

  /**
   * At most once an hour per instance: drops the rows past the retention (fixed in the database).
   */
  private void pruneNowAndThen(Instant now) {
    synchronized (this) {
      if (Duration.between(lastPrune, now).compareTo(PRUNE_EVERY) < 0) {
        return;
      }
      lastPrune = now;
    }
    try {
      Integer gone = jdbc.sql("SELECT prune_sign_in_log()").query(Integer.class).single();
      if (gone != null && gone > 0) {
        LOGGER.info(() -> gone + " sign-in records past the retention removed");
      }
    } catch (DataAccessException e) {
      LOGGER.log(Level.WARNING, "The sign-in records past the retention could not be removed", e);
    }
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
    }
  }
}
