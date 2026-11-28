package de.billery.service;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * An offer or cost calculation cannot move from its current status to the requested one. The
 * message is German, names both statuses and is shown to the user. Mapped to HTTP 400 by {@code
 * ApiExceptionHandler}.
 */
public class InvalidStatusChangeException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * Creates the exception.
   *
   * @param message user-facing explanation
   */
  public InvalidStatusChangeException(UserMessage message) {
    super(message.text());
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
