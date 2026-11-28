package de.billery.mail;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * A mail server the SaaS profile does not allow (a port, no encryption, an unknown or internal
 * address), refused when the account is saved ({@link MailHostPolicy#checkAccount}). Still an
 * {@link IllegalArgumentException}; the message has a code the SPA translates (#15).
 */
public class MailHostNotAllowedException extends IllegalArgumentException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code. */
  private final transient UserMessage coded;

  /**
   * @param message why the server is not allowed
   */
  public MailHostNotAllowedException(UserMessage message) {
    super(message.text());
    this.coded = message;
  }

  /**
   * @param message why the server is not allowed
   * @param cause the failed lookup
   */
  public MailHostNotAllowedException(UserMessage message, Throwable cause) {
    super(message.text(), cause);
    this.coded = message;
  }

  /**
   * @return the message with its code
   */
  @Override
  public UserMessage userMessage() {
    return coded;
  }
}
