package de.billery.service;

/** The request's input is not acceptable; the message (German) tells the user what to change. */
public class BadRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what is wrong, for the user
   */
  public BadRequestException(String message) {
    super(message);
  }

  /**
   * @param message what is wrong, for the user
   * @param cause the check that failed
   */
  public BadRequestException(String message, Throwable cause) {
    super(message, cause);
  }
}
