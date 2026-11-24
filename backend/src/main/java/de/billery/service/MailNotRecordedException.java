package de.billery.service;

/**
 * A mail to a customer went out, but its entry in the document's history (and an offer's change to
 * "Gesendet") could not be written. The message tells the user not to send again; the backend log
 * has an error and {@code billery_mail_dispatch_unrecorded_total} counts it.
 */
public class MailNotRecordedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what happened, for the user
   * @param cause the failed write
   */
  public MailNotRecordedException(String message, Throwable cause) {
    super(message, cause);
  }
}
