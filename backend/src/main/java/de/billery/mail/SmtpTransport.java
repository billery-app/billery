package de.billery.mail;

import java.util.List;
import javax.net.SocketFactory;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.email.EmailBuilder;
import org.simplejavamail.mailer.MailerBuilder;

/**
 * Delivers one {@link MailMessage} through one SMTP account. Used for the system account ({@link
 * SmtpMailProvider}) and for the companies' own accounts ({@link CompanyMailSender}); a connection
 * is opened per message and closed afterwards (mail is sent rarely, and a company's account
 * settings may change between two messages).
 */
public final class SmtpTransport {

  /** How long one delivery may take before it counts as failed (connect, TLS, sending). */
  static final int TIMEOUT_MILLIS = 15_000;

  private SmtpTransport() {}

  /**
   * Sends the message.
   *
   * @param endpoint the account
   * @param message recipients, subject, text and attachments; an empty sender means the account's
   * @param policy which servers may be connected to
   * @throws MailException when the server could not be reached or refused the message
   */
  public static void send(SmtpEndpoint endpoint, MailMessage message, MailHostPolicy policy)
      throws MailException {
    try {
      Email email = build(endpoint, message);
      String user = endpoint.username().isEmpty() ? null : endpoint.username();
      String password = user == null ? null : endpoint.password();
      // Without falling back to a plain socket when a guarded one refuses (has no effect without
      // a socket factory).
      try (Mailer mailer =
          MailerBuilder.withSMTPServer(endpoint.host(), endpoint.port(), user, password)
              .withTransportStrategy(
                  switch (endpoint.security()) {
                    case SSL -> TransportStrategy.SMTPS;
                    case STARTTLS -> TransportStrategy.SMTP_TLS;
                    case NONE -> TransportStrategy.SMTP;
                  })
              .withSessionTimeout(TIMEOUT_MILLIS)
              .withProperty("mail.smtp.socketFactory.fallback", "false")
              .withProperty("mail.smtps.socketFactory.fallback", "false")
              .buildMailer()) {
        SocketFactory guard = policy.socketFactory();
        if (guard != null) {
          // JavaMail creates the plain socket with this factory and layers TLS on top (for SMTPS
          // as well as STARTTLS), so every connection passes the check of MailHostPolicy. Put
          // into the session directly: the builder's withProperty turns every value into a
          // string, and JavaMail only uses a factory given as an object (MailHostPolicyTest
          // proves it is used).
          mailer.getSession().getProperties().put("mail.smtp.socketFactory", guard);
          mailer.getSession().getProperties().put("mail.smtps.socketFactory", guard);
        }
        mailer.sendMail(email);
      }
    } catch (RuntimeException e) {
      throw new MailException("Mail delivery failed", e);
    } catch (Exception e) {
      // closing the mailer
      throw new MailException("Mail delivery failed", e, MailException.Reason.OTHER);
    }
  }

  private static Email build(SmtpEndpoint endpoint, MailMessage message) {
    String from = message.getFrom() == null ? endpoint.from() : message.getFrom();
    String fromName = message.getFromName() == null ? endpoint.fromName() : message.getFromName();
    EmailPopulatingBuilder email =
        fromName == null || fromName.isBlank()
            ? EmailBuilder.startingBlank().from(from)
            : EmailBuilder.startingBlank().from(fromName, from);
    email = email.toMultiple(message.getToAll());
    List<String> cc = message.getCc();
    if (!cc.isEmpty()) {
      email = email.ccMultiple(cc.toArray(String[]::new));
    }
    List<String> bcc = message.getBcc();
    if (!bcc.isEmpty()) {
      email = email.bccMultiple(bcc.toArray(String[]::new));
    }
    email = email.withSubject(message.getSubject()).withPlainText(message.getText());
    for (MailAttachment attachment : message.getAttachments()) {
      email =
          email.withAttachment(attachment.fileName(), attachment.content(), attachment.mimeType());
    }
    return email.buildEmail();
  }
}
