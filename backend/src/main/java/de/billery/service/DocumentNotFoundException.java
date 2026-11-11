package de.billery.service;

/** The requested document does not exist. Mapped to HTTP 404 by {@code ApiExceptionHandler}. */
public class DocumentNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param id the id that was not found
   */
  public DocumentNotFoundException(int id) {
    super("Dokument " + id + " wurde nicht gefunden.");
  }
}
