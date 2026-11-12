package de.billery.api;

/**
 * A second owner (company master data) was to be created, but there is one owner per company: the
 * PostgreSQL schema enforces it ({@code uq_owner_tenant}), and the documents read the one owner.
 * The message is German and shown to the user. Mapped to HTTP 409 by {@link ApiExceptionHandler}.
 */
public class OwnerAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception with the message shown to the user. */
  public OwnerAlreadyExistsException() {
    super("Es gibt bereits einen Inhaber. Bitte die vorhandenen Firmendaten bearbeiten.");
  }
}
