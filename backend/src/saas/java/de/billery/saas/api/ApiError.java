package de.billery.saas.api;

import org.springframework.http.HttpStatus;

/** A request the SaaS API refuses; the message is meant for the client. */
public class ApiError extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;

  /**
   * Creates the error.
   *
   * @param status the HTTP status to answer with
   * @param message what the client should be told
   */
  public ApiError(HttpStatus status, String message) {
    this(status, message, null);
  }

  /**
   * Creates the error with the exception that caused it.
   *
   * @param status the HTTP status to answer with
   * @param message what the client should be told
   * @param cause the underlying exception
   */
  public ApiError(HttpStatus status, String message, Throwable cause) {
    super(message, cause);
    this.status = status;
  }

  /**
   * The status to answer with.
   *
   * @return the HTTP status
   */
  public HttpStatus httpStatus() {
    return status;
  }
}
