package de.billery.service;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/** The request's input is not acceptable; the message (German) tells the user what to change. */
public class BadRequestException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * @param message what is wrong, for the user
   */
  public BadRequestException(UserMessage message) {
    super(message.text());
    this.coded = message;
  }

  /**
   * @param message what is wrong, for the user
   * @param cause the check that failed
   */
  public BadRequestException(UserMessage message, Throwable cause) {
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
