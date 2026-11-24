package de.billery.mail;

import org.springframework.stereotype.Component;

/**
 * Sends system mails (invitations) through the operator's SMTP account of {@link MailSettings}. It
 * never sends to a company's customers: those mails go through the company's own account ({@link
 * CompanyMailSender}). Does nothing useful while mail is not configured: {@link #send} then throws,
 * callers check {@link #configured()} first.
 */
@Component
public class SmtpMailProvider implements MailProvider {

  /** The operator configures the account, so any server is allowed (a relay on the host too). */
  private static final MailHostPolicy OPERATOR = new MailHostPolicy(false);

  private final MailSettings settings;
  private final MailMetrics metrics;

  /**
   * @param settings the SMTP account
   * @param metrics counts sent and failed mails
   */
  public SmtpMailProvider(MailSettings settings, MailMetrics metrics) {
    this.settings = settings;
    this.metrics = metrics;
  }

  /**
   * @return true when an SMTP account is configured
   */
  @Override
  public boolean configured() {
    return settings.configured();
  }

  @Override
  public void send(MailMessage msg) throws MailException {
    if (!configured()) {
      throw new MailException(
          "Mail is not configured (MAIL_HOST, MAIL_FROM)", null, MailException.Reason.OTHER);
    }
    SmtpEndpoint endpoint =
        new SmtpEndpoint(
            settings.getHost(),
            settings.getPort(),
            settings.getSecurity(),
            settings.getUsername(),
            settings.getPassword(),
            settings.getFrom(),
            "");
    try {
      SmtpTransport.send(endpoint, msg, OPERATOR);
      metrics.sent(MailMetrics.SYSTEM);
    } catch (MailException e) {
      metrics.failed(MailMetrics.SYSTEM, e.reason());
      throw new MailException("Mailversand fehlgeschlagen", e, e.reason());
    }
  }
}
