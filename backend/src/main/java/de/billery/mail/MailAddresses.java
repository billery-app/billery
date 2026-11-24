package de.billery.mail;

import java.util.regex.Pattern;

/** Checks of what users type into mail fields: addresses, names and subjects. */
public final class MailAddresses {

  /**
   * A plain address: local part, {@code @}, a domain with a dot. No display name, no angle
   * brackets, no separators, no white space or line breaks (which could add header lines).
   */
  private static final Pattern ADDRESS =
      Pattern.compile(
          "^[^@\\s,;<>\"()\\[\\]\\\\]+@[^@\\s,;<>\"()\\[\\]\\\\]+\\.[^@\\s,;<>\"()\\[\\]\\\\]+$");

  /** The longest address SMTP accepts. */
  static final int MAX_ADDRESS = 254;

  private MailAddresses() {}

  /**
   * @param address what the user typed (already trimmed)
   * @return true if it is one plain e-mail address
   */
  public static boolean isAddress(String address) {
    return address != null && address.length() <= MAX_ADDRESS && ADDRESS.matcher(address).matches();
  }

  /**
   * @param text a value that goes into a header (subject, name)
   * @return true if it contains a line break or another control character
   */
  public static boolean hasControlCharacters(String text) {
    if (text == null) {
      return false;
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.isISOControl(text.charAt(i))) {
        return true;
      }
    }
    return false;
  }
}
