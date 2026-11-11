package de.billery.service;

/**
 * An offer or cost calculation cannot move from its current status to the requested one. The
 * message is German, names both statuses and is shown to the user. Mapped to HTTP 400 by {@code
 * ApiExceptionHandler}.
 */
public class InvalidStatusChangeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message user-facing explanation
   */
  public InvalidStatusChangeException(String message) {
    super(message);
  }
}
