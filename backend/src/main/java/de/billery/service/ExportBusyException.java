package de.billery.service;

/** The maximum number of concurrent exports is running; the caller should retry later. */
public class ExportBusyException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message a user-facing explanation
   */
  public ExportBusyException(String message) {
    super(message);
  }
}
