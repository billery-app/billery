package de.billery.saas.identity;

/** A sign-in that cannot be mapped to a user; the message is meant for the client. */
public class IdentityException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The text for the client: a fixed sentence of the code, never data of the request. */
  private final String clientText;

  /**
   * Creates the exception.
   *
   * @param message what the client should be told (a fixed sentence)
   */
  public IdentityException(String message) {
    super(message);
    this.clientText = message;
  }

  /**
   * @return the text the client may see
   */
  public String clientMessage() {
    return clientText;
  }
}
