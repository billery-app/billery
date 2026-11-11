package de.billery.saas.identity;

/** A sign-in that cannot be mapped to a user; the message is meant for the client. */
public class IdentityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what the client should be told
   */
  public IdentityException(String message) {
    super(message);
  }
}
