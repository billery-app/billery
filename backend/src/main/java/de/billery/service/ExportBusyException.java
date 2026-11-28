package de.billery.service;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/** The maximum number of concurrent exports is running; the caller should retry later. */
public class ExportBusyException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * Creates the exception.
   *
   * @param message a user-facing explanation
   */
  public ExportBusyException(UserMessage message) {
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
