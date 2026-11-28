package de.billery.service;

import de.billery.api.error.ErrorCode;
import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/** The requested document does not exist. Mapped to HTTP 404 by {@code ApiExceptionHandler}. */
public class DocumentNotFoundException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * Creates the exception.
   *
   * @param id the id that was not found
   */
  public DocumentNotFoundException(int id) {
    this(ErrorCode.DOCUMENT_NOT_FOUND.with("id", id));
  }

  private DocumentNotFoundException(UserMessage message) {
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
