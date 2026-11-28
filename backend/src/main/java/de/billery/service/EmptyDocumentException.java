package de.billery.service;

import de.billery.api.error.ErrorCode;
import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * An imported DOCX/ODT document contains no text, so no template is created from it. The message is
 * German and shown to the user (instead of silently saving an empty template).
 */
public class EmptyDocumentException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /** Creates the exception with the user-facing message. */
  public EmptyDocumentException() {
    this(ErrorCode.TEMPLATE_IMPORT_EMPTY.with());
  }

  private EmptyDocumentException(UserMessage message) {
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
