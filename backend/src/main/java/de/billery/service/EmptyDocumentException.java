package de.billery.service;

/**
 * An imported DOCX/ODT document contains no text, so no template is created from it. The message is
 * German and shown to the user (instead of silently saving an empty template).
 */
public class EmptyDocumentException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the user-facing message. */
  public EmptyDocumentException() {
    super("Das Dokument enthält keinen Text; es wurde keine Vorlage angelegt.");
  }
}
