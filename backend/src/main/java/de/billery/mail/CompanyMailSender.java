package de.billery.mail;

import de.billery.data.MailAccount;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Sends a mail through one of the company's own SMTP accounts: decrypts the password for this one
 * delivery, connects as {@link MailHostPolicy} allows, and counts the outcome ({@link
 * MailMetrics#COMPANY}). Never falls back to the operator's system account.
 */
@Component
public class CompanyMailSender {

  private final CredentialCipher cipher;
  private final MailHostPolicy policy;
  private final MailMetrics metrics;

  /**
   * @param cipher decrypts the stored password
   * @param policy which servers may be connected to
   * @param metrics counts sent and failed mails
   */
  public CompanyMailSender(CredentialCipher cipher, MailHostPolicy policy, MailMetrics metrics) {
    this.cipher = cipher;
    this.policy = policy;
    this.metrics = metrics;
  }

  /**
   * Sends the message with the account as sender.
   *
   * @param account the company's account
   * @param message recipients, subject, text and attachments (sender: the account's)
   * @throws MailException when the password cannot be read, the server is not allowed or not
   *     reached, or it refuses the message
   */
  public void send(MailAccount account, MailMessage message) throws MailException {
    try {
      SmtpTransport.send(endpoint(account), message, policy);
      metrics.sent(MailMetrics.COMPANY);
    } catch (MailException e) {
      metrics.failed(MailMetrics.COMPANY, e.reason());
      throw e;
    }
  }

  private SmtpEndpoint endpoint(MailAccount account) throws MailException {
    String password = "";
    if (account.hasPassword()) {
      try {
        password = cipher.decrypt(account.passwordEncrypted());
      } catch (IllegalStateException e) {
        throw new MailException(
            "The stored password cannot be decrypted (key changed?)", e, MailException.Reason.AUTH);
      }
    }
    return new SmtpEndpoint(
        account.host(),
        account.port(),
        MailSettings.Security.valueOf(account.security().toUpperCase(Locale.ROOT)),
        account.username(),
        password,
        account.fromAddress(),
        account.fromName());
  }
}
