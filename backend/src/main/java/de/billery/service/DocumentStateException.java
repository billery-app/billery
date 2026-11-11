package de.billery.service;

/**
 * The requested operation is not allowed in the document's current state, e.g. editing or deleting
 * an issued invoice, or issuing a draft that lacks a mandatory field. The message is German and
 * shown to the user. Mapped to HTTP 409 by {@code ApiExceptionHandler}.
 */
public class DocumentStateException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message user-facing explanation
   */
  public DocumentStateException(String message) {
    super(message);
  }
}
