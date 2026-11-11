package de.billery.pdf;

/**
 * A document cannot be rendered because user-configured data is missing (no company/owner record,
 * no usable template for the document type). The message is shown to the user, so it is German and
 * says what to configure. Mapped to HTTP 409 by {@code ApiExceptionHandler}.
 */
public class MissingConfigurationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message user-facing explanation of what must be configured
   */
  public MissingConfigurationException(String message) {
    super(message);
  }
}
