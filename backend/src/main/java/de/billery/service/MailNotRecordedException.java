package de.billery.service;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * A mail to a customer went out, but its entry in the document's history (and an offer's change to
 * "Gesendet") could not be written. The message tells the user not to send again; the backend log
 * has an error and {@code billery_mail_dispatch_unrecorded_total} counts it.
 */
public class MailNotRecordedException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * @param message what happened, for the user
   * @param cause the failed write
   */
  public MailNotRecordedException(UserMessage message, Throwable cause) {
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
