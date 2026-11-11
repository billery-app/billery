package de.billery.service;

/**
 * An export would exceed a configured limit (period, document count or output size). The message is
 * meant for the user and names the limit.
 */
public class ExportLimitException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what exceeded which limit
   */
  public ExportLimitException(String message) {
    super(message);
  }
}
