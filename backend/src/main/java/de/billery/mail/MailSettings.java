package de.billery.mail;

import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The SMTP account the application sends mail with, read from the environment ({@code MAIL_HOST},
 * {@code MAIL_PORT}, {@code MAIL_USERNAME}, {@code MAIL_PASSWORD}, {@code MAIL_FROM}, {@code
 * MAIL_SECURITY}). Without {@code MAIL_HOST} and {@code MAIL_FROM} mail is switched off: {@link
 * #configured()} is false and nothing is sent; the rest of the application works as before.
 */
@Component
public class MailSettings {

  /** How the connection to the SMTP server is secured. */
  public enum Security {
    /** Plain connection upgraded with STARTTLS (port 587); the default. */
    STARTTLS,
    /** TLS from the first byte (port 465). */
    SSL,
    /** No encryption: only for a server on the same host or in tests. */
    NONE
  }

  private final String host;
  private final int port;
  private final String username;
  private final String password;
  private final String from;
  private final Security security;

  /**
   * Reads the settings; Spring maps {@code MAIL_HOST} to {@code mail.host} and so on.
   *
   * @param host SMTP server; empty switches mail off
   * @param port SMTP port; empty or 0 picks the usual port of {@code security} (an empty value
   *     comes from Compose when {@code MAIL_PORT} is not set)
   * @param username login; empty for a server without authentication
   * @param password password of the login
   * @param from sender address; empty switches mail off
   * @param security {@code starttls} (default), {@code ssl} or {@code none}
   * @throws IllegalStateException if a value is invalid, or {@code none} comes with a login, which
   *     would send the credentials unencrypted
   */
  public MailSettings(
      @Value("${mail.host:}") String host,
      @Value("${mail.port:}") String port,
      @Value("${mail.username:}") String username,
      @Value("${mail.password:}") String password,
      @Value("${mail.from:}") String from,
      @Value("${mail.security:starttls}") String security) {
    this.host = host == null ? "" : host.trim();
    this.security = parseSecurity(security);
    int parsed = parsePort(port);
    this.port = parsed > 0 ? parsed : defaultPort(this.security);
    this.username = username == null ? "" : username.trim();
    this.password = password == null ? "" : password;
    this.from = from == null ? "" : from.trim();
    if (this.security == Security.NONE && (!this.username.isEmpty() || !this.password.isEmpty())) {
      throw new IllegalStateException(
          "MAIL_SECURITY=none would send MAIL_USERNAME and MAIL_PASSWORD unencrypted:"
              + " use starttls or ssl, or a server without a login");
    }
  }

  private static Security parseSecurity(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    try {
      return normalized.isEmpty() ? Security.STARTTLS : Security.valueOf(normalized);
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "MAIL_SECURITY must be starttls, ssl or none, not '" + value + "'", e);
    }
  }

  private static int parsePort(String value) {
    String trimmed = value == null ? "" : value.trim();
    if (trimmed.isEmpty()) {
      return 0;
    }
    try {
      return Integer.parseInt(trimmed);
    } catch (NumberFormatException e) {
      throw new IllegalStateException("MAIL_PORT must be a number, not '" + value + "'", e);
    }
  }

  private static int defaultPort(Security security) {
    return switch (security) {
      case SSL -> 465;
      case STARTTLS -> 587;
      case NONE -> 25;
    };
  }

  /**
   * @return true when host and sender are set, i.e. mail can be sent
   */
  public boolean configured() {
    return !host.isEmpty() && !from.isEmpty();
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public String getUsername() {
    return username;
  }

  public String getPassword() {
    return password;
  }

  public String getFrom() {
    return from;
  }

  public Security getSecurity() {
    return security;
  }
}
