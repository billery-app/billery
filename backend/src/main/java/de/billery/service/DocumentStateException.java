package de.billery.service;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * The requested operation is not allowed in the document's current state, e.g. editing or deleting
 * an issued invoice, or issuing a draft that lacks a mandatory field. The message is German and
 * shown to the user. Mapped to HTTP 409 by {@code ApiExceptionHandler}.
 */
public class DocumentStateException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /**
   * Creates the exception.
   *
   * @param message user-facing explanation
   */
  public DocumentStateException(UserMessage message) {
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
