package de.billery.mail;

import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;
import org.springframework.stereotype.Component;

/**
 * Sends plain-text mail through the SMTP account of {@link MailSettings}. Does nothing useful while
 * mail is not configured: {@link #send} then throws, callers check {@link #configured()} first.
 */
@Component
public class SmtpMailProvider implements MailProvider {

  /** How long one delivery may take before it counts as failed (connect, TLS, sending). */
  private static final int TIMEOUT_MILLIS = 15_000;

  private final MailSettings settings;
  private Mailer mailer;

  /**
   * @param settings the SMTP account; the connection is built on the first message
   */
  public SmtpMailProvider(MailSettings settings) {
    this.settings = settings;
  }

  /**
   * @return true when an SMTP account is configured
   */
  @Override
  public boolean configured() {
    return settings.configured();
  }

  private synchronized Mailer connection() {
    if (mailer == null) {
      String user = settings.getUsername().isEmpty() ? null : settings.getUsername();
      String password = user == null ? null : settings.getPassword();
      mailer =
          MailerBuilder.withSMTPServer(settings.getHost(), settings.getPort(), user, password)
              .withTransportStrategy(
                  switch (settings.getSecurity()) {
                    case SSL -> TransportStrategy.SMTPS;
                    case STARTTLS -> TransportStrategy.SMTP_TLS;
                    case NONE -> TransportStrategy.SMTP;
                  })
              .withSessionTimeout(TIMEOUT_MILLIS)
              .buildMailer();
    }
    return mailer;
  }

  @Override
  public void send(MailMessage msg) throws MailException {
    if (!configured()) {
      throw new MailException("Mail is not configured (MAIL_HOST, MAIL_FROM)", null);
    }
    try {
      Email email =
          EmailBuilder.startingBlank()
              .from(msg.getFrom() == null ? settings.getFrom() : msg.getFrom())
              .to(msg.getTo())
              .withSubject(msg.getSubject())
              .withPlainText(msg.getText())
              .buildEmail();
      connection().sendMail(email);
    } catch (RuntimeException e) {
      throw new MailException("Mailversand fehlgeschlagen", e);
    }
  }
}
