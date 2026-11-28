package de.billery.desktop;

import de.billery.api.error.UserFacing;
import de.billery.api.error.UserMessage;

/**
 * A backup setting or restore the desktop app refuses (a folder, a password, an unknown or damaged
 * backup). Still an {@link IllegalArgumentException}, as before; the message has a code the SPA
 * translates (#15).
 */
public class BackupRefusedException extends IllegalArgumentException implements UserFacing {

  private static final long serialVersionUID = 1L;

  /** The message with its code. */
  private final transient UserMessage coded;

  /**
   * @param message why it was refused
   */
  public BackupRefusedException(UserMessage message) {
    super(message.text());
    this.coded = message;
  }

  /**
   * @param message why it was refused
   * @param cause the failure behind it
   */
  public BackupRefusedException(UserMessage message, Throwable cause) {
    super(message.text(), cause);
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
