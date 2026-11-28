package de.billery.service;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * An export would exceed a configured limit (period, document count or output size). The message is
 * meant for the user and names the limit.
 */
public class ExportLimitException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * Creates the exception.
   *
   * @param message what exceeded which limit
   */
  public ExportLimitException(UserMessage message) {
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
