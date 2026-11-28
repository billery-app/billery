package de.billery.pdf;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * A document cannot be rendered because user-configured data is missing (no company/owner record,
 * no usable template for the document type). The message is shown to the user, so it is German and
 * says what to configure. Mapped to HTTP 409 by {@code ApiExceptionHandler}.
 */
public class MissingConfigurationException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * Creates the exception.
   *
   * @param message user-facing explanation of what must be configured
   */
  public MissingConfigurationException(UserMessage message) {
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
