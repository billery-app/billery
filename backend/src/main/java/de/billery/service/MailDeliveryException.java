package de.billery.service;

/**
 * A mail could not be delivered to the company's SMTP server, or the company's mail limit is
 * reached. The message (German) explains it to the user; it never repeats the server's answer,
 * which may quote addresses.
 */
public class MailDeliveryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final boolean limit;

  /**
   * @param message what happened, for the user
   * @param limitReached true for the daily limit (HTTP 429), false for a failed delivery (502)
   */
  public MailDeliveryException(String message, boolean limitReached) {
    super(message);
    this.limit = limitReached;
  }

  /**
   * @param message what happened, for the user
   * @param cause the failed delivery (kept for the log, never shown)
   */
  public MailDeliveryException(String message, Throwable cause) {
    super(message, cause);
    this.limit = false;
  }

  /**
   * @return true when the daily limit of the company is reached
   */
  public boolean limitReached() {
    return limit;
  }
}
