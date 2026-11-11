package de.billery.data;

/**
 * Unchecked wrapper for a failed database operation. Repositories throw it instead of returning
 * {@code null} / empty results, so a failed write can never be reported to the client as success.
 * {@code ApiExceptionHandler} maps it to HTTP 500.
 */
public class DatabaseException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was being done when the error occurred
   * @param cause the underlying JDBC error
   */
  public DatabaseException(String message, Throwable cause) {
    super(message, cause);
  }
}
