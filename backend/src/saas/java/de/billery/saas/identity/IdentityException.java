package de.billery.saas.identity;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/** A sign-in that cannot be mapped to a user; the message is meant for the client. */
public class IdentityException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** What the client is told: a fixed message with its code, never data of the request (#15). */
  private final transient UserMessage clientText;

  /**
   * Creates the exception.
   *
   * @param message what the client should be told (a fixed sentence)
   */
  public IdentityException(UserMessage message) {
    super(message.text());
    this.clientText = message;
  }

  /**
   * @return the message the client may see, with its code
   */
  @Override
  public UserMessage userMessage() {
    return clientText;
  }
}
