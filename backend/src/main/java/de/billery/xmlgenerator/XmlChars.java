package de.billery.xmlgenerator;

/**
 * Removes the characters XML 1.0 forbids (control characters other than tab, line feed and carriage
 * return, unpaired surrogates, U+FFFE / U+FFFF). They reach the data through copy and paste from
 * other programs; a single one makes an XML document invalid, so an e-invoice would be rejected and
 * the PDF (its HTML is parsed as XHTML) could not be rendered. They carry no meaning in an invoice,
 * so they are dropped rather than refused.
 */
public final class XmlChars {

  private XmlChars() {}

  /**
   * Returns the text without the characters XML 1.0 does not allow.
   *
   * @param text any text, may be {@code null}
   * @return the text without forbidden characters; the same instance if there are none
   */
  public static String clean(String text) {
    if (text == null || isClean(text)) {
      return text;
    }
    StringBuilder out = new StringBuilder(text.length());
    text.codePoints().filter(XmlChars::allowed).forEach(out::appendCodePoint);
    return out.toString();
  }

  private static boolean isClean(String text) {
    return text.codePoints().allMatch(XmlChars::allowed);
  }

  /** The {@code Char} production of XML 1.0 (5th edition), section 2.2. */
  private static boolean allowed(int c) {
    return c == 0x9
        || c == 0xA
        || c == 0xD
        || (c >= 0x20 && c <= 0xD7FF)
        || (c >= 0xE000 && c <= 0xFFFD)
        || (c >= 0x10000 && c <= 0x10FFFF);
  }
}
