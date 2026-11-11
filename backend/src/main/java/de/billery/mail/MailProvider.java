package de.billery.mail;

/** Sends a message; implemented by {@link SmtpMailProvider}, replaced by fakes in tests. */
@FunctionalInterface
public interface MailProvider {

  /**
   * Sends a message.
   *
   * @param message sender (null: the configured one), recipient, subject and plain text
   * @throws MailException when the message could not be delivered to the SMTP server
   */
  void send(MailMessage message) throws MailException;

  /**
   * @return false when no mail account is configured and {@link #send} cannot work
   */
  default boolean configured() {
    return true;
  }
}
