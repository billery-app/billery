package de.billery.service;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * A mail could not be delivered to the company's SMTP server, or the company's mail limit is
 * reached. The message (German) explains it to the user; it never repeats the server's answer,
 * which may quote addresses.
 */
public class MailDeliveryException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  private final boolean limit;

  /**
   * @param message what happened, for the user
   * @param limitReached true for the daily limit (HTTP 429), false for a failed delivery (502)
   */
  public MailDeliveryException(UserMessage message, boolean limitReached) {
    super(message.text());
    this.coded = message;
    this.limit = limitReached;
  }

  /**
   * @param message what happened, for the user
   * @param cause the failed delivery (kept for the log, never shown)
   */
  public MailDeliveryException(UserMessage message, Throwable cause) {
    super(message.text(), cause);
    this.coded = message;
    this.limit = false;
  }

  /**
   * @return true when the daily limit of the company is reached
   */
  public boolean limitReached() {
    return limit;
  }

  /**
   * @return the message with its code
   */
  @Override
  public UserMessage userMessage() {
    return coded;
  }
}
