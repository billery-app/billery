package de.billery.api;

import de.billery.api.error.ErrorCode;
import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * A second owner (company master data) was to be created, but there is one owner per company: the
 * PostgreSQL schema enforces it ({@code uq_owner_tenant}), and the documents read the one owner.
 * The message is German and shown to the user. Mapped to HTTP 409 by {@link ApiExceptionHandler}.
 */
public class OwnerAlreadyExistsException extends RuntimeException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code, which the SPA translates (#15). */
  private final transient UserMessage coded;

  /** Creates the exception with the message shown to the user. */
  public OwnerAlreadyExistsException() {
    this(ErrorCode.OWNER_EXISTS.with());
  }

  private OwnerAlreadyExistsException(UserMessage message) {
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
