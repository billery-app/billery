package de.billery.saas.provisioning;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Rolling 24-hour limits against abuse, kept in {@code daily_quota} so that they hold across
 * restarts and backend instances: invitation mails ({@link InvitationMailQuota}) and sign-ups
 * ({@link TenantProvisioningService}).
 *
 * <p>A claim names several limits (per user, per source IP, for the whole system ...). It succeeds
 * only if none is reached, and then counts all of them; a refused claim counts nothing, so one
 * account cannot use up a shared limit by retrying. Checking and counting run under an advisory
 * lock per quota, inside the caller's transaction when there is one: if the caller's work fails
 * afterwards, the count is rolled back with it.
 *
 * <p>Every refusal increments {@code billery_quota_refused_total{quota, scope}}; the alert rules in
 * {@code deploy/saas/monitoring/alerts.yml} watch it, so a reached limit is not only a log line.
 */
@Service
@Profile("saas")
public class DailyQuota {

  /** Scope of a limit for the whole system; its key is always {@link #SYSTEM_KEY}. */
  public static final String SYSTEM = "system";

  /** The key of the system-wide limit. */
  public static final String SYSTEM_KEY = "all";

  /** Base of the advisory lock keys (arbitrary, app-specific); each quota gets its own lock. */
  private static final long LOCK_BASE = 0x62696C6C65727932L;

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final MeterRegistry meters;

  /**
   * One limit of a claim.
   *
   * @param scope what is limited, e.g. {@code user}, {@code ip}, {@code system}
   * @param key whose use is counted (a user id, a hash, {@link #SYSTEM_KEY})
   * @param max how often it may be used in 24 hours; 0 or less switches the limit off (it is then
   *     neither checked nor counted)
   */
  public record Limit(String scope, String key, int max) {}

  /**
   * Creates the quota.
   *
   * @param jdbc the database (the table has no row-level security)
   * @param transactions runs check and count as one, or joins the caller's transaction
   * @param meters counts the refusals for the monitoring
   */
  public DailyQuota(JdbcClient jdbc, TransactionTemplate transactions, MeterRegistry meters) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.meters = meters;
  }

  /**
   * Counts one use if no limit is reached.
   *
   * @param quota which quota, e.g. {@code signup}
   * @param limits the limits to check and count
   * @return empty if the use is allowed (and counted); otherwise the scope of the first limit that
   *     is reached (nothing is counted then)
   */
  public Optional<String> claim(String quota, List<Limit> limits) {
    return transactions.execute(
        status -> {
          lockAndForgetExpired(quota);
          for (Limit limit : limits) {
            if (limit.max() > 0 && used(quota, limit) >= limit.max()) {
              refused(quota, limit.scope()).increment();
              return Optional.of(limit.scope());
            }
          }
          for (Limit limit : limits) {
            if (limit.max() > 0) {
              count(quota, limit);
            }
          }
          return Optional.<String>empty();
        });
  }

  /**
   * The stored form of an e-mail address or IP: the limits need nothing readable. This is not
   * anonymisation (a known value can be hashed and compared), and the rows go after a day.
   *
   * @param value an address or IP
   * @return its SHA-256 as hex
   */
  public static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /**
   * Registers the refusal counters of a quota with 0, so the monitoring sees the first refusal as
   * an increase (a counter created by its first increment starts at 1, and {@code increase()} would
   * miss it).
   *
   * @param quota the quota
   * @param scopes its scopes
   */
  public void declare(String quota, String... scopes) {
    for (String scope : scopes) {
      refused(quota, scope);
    }
  }

  private Counter refused(String quota, String scope) {
    return Counter.builder("billery.quota.refused")
        .description("Requests refused because a 24-hour abuse limit was reached")
        .tag("quota", quota)
        .tag("scope", scope)
        .register(meters);
  }

  private void lockAndForgetExpired(String quota) {
    jdbc.sql("SELECT pg_advisory_xact_lock(:key)")
        .param("key", LOCK_BASE + quota.hashCode())
        .query((rs, row) -> row)
        .single();
    jdbc.sql(
            "DELETE FROM daily_quota WHERE quota = :quota"
                + " AND window_start <= CURRENT_TIMESTAMP - INTERVAL '1 day'")
        .param("quota", quota)
        .update();
  }

  private int used(String quota, Limit limit) {
    return jdbc.sql(
            "SELECT used FROM daily_quota"
                + " WHERE quota = :quota AND scope = :scope AND quota_key = :key")
        .param("quota", quota)
        .param("scope", limit.scope())
        .param("key", limit.key())
        .query(Integer.class)
        .optional()
        .orElse(0);
  }

  private void count(String quota, Limit limit) {
    jdbc.sql(
            "INSERT INTO daily_quota AS q (quota, scope, quota_key, used)"
                + " VALUES (:quota, :scope, :key, 1)"
                + " ON CONFLICT (quota, scope, quota_key) DO UPDATE SET used = q.used + 1")
        .param("quota", quota)
        .param("scope", limit.scope())
        .param("key", limit.key())
        .update();
  }
}
