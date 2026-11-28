package de.billery.saas.api;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;
import org.springframework.http.HttpStatus;

/** A request the SaaS API refuses; the message is meant for the client. */
public class ApiError extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  private final HttpStatus status;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * Creates the error.
   *
   * @param status the HTTP status to answer with
   * @param message what the client should be told
   */
  public ApiError(HttpStatus status, UserMessage message) {
    this(status, message, null);
  }

  /**
   * Creates the error with the exception that caused it.
   *
   * @param status the HTTP status to answer with
   * @param message what the client should be told
   * @param cause the underlying exception
   */
  public ApiError(HttpStatus status, UserMessage message, Throwable cause) {
    super(message.text(), cause);
    this.status = status;
    this.coded = message;
  }

  /**
   * The status to answer with.
   *
   * @return the HTTP status
   */
  public HttpStatus httpStatus() {
    return status;
  }

  /**
   * @return the message with its code
   */
  @Override
  public UserMessage userMessage() {
    return coded;
  }
}
