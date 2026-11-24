package de.billery.mail;

/**
 * An SMTP account to send with: the system account ({@link MailSettings}) or a company's own
 * ({@code mail_account}, password decrypted for this one delivery).
 *
 * @param host the mail server
 * @param port its port
 * @param security how the connection is secured
 * @param username the login, empty for none
 * @param password the password, empty for none
 * @param from the sender address
 * @param fromName the sender's display name, may be empty
 */
public record SmtpEndpoint(
    String host,
    int port,
    MailSettings.Security security,
    String username,
    String password,
    String from,
    String fromName) {

  /**
   * Leaves the password out, so a log line or an exception message never carries it.
   *
   * @return host, port, security and sender
   */
  @Override
  public String toString() {
    return "SmtpEndpoint[" + host + ":" + port + ", " + security + ", from " + from + "]";
  }
}
