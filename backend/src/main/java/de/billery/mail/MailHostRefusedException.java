package de.billery.mail;

import java.io.IOException;

/**
 * The connection to a mail server was refused before it was made, because the address it resolves
 * to is not allowed (see {@link MailHostPolicy}). An {@link IOException}, so the mail library
 * reports it like any other failed connection; {@link MailException#classify} recognises it.
 */
public class MailHostRefusedException extends IOException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what was refused (host name and port; no user data)
   */
  public MailHostRefusedException(String message) {
    super(message);
  }
}
