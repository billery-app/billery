package de.billery.xmlgenerator;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Minimal indenting XML writer of the e-invoice generators; escapes all text content it is given.
 * One instance writes one document and is then discarded, so its buffer field cannot grow
 * unbounded.
 */
@SuppressWarnings("PMD.AvoidStringBufferField")
final class XmlWriter {
  private static final String EUR = "EUR";
  private final StringBuilder out = new StringBuilder();
  private int depth;

  void raw(String text) {
    out.append(text).append('\n');
  }

  void open(String name) {
    open(name, "");
  }

  void open(String name, String attributes) {
    indent();
    out.append('<').append(name);
    if (!attributes.isEmpty()) {
      out.append(' ').append(attributes);
    }
    out.append(">\n");
    depth++;
  }

  void close(String name) {
    depth--;
    indent();
    out.append("</").append(name).append(">\n");
  }

  void leaf(String name, String text) {
    leaf(name, text, "");
  }

  void leaf(String name, String text, String attributes) {
    indent();
    out.append('<').append(name);
    if (!attributes.isEmpty()) {
      out.append(' ').append(attributes);
    }
    out.append('>')
        .append(escape(text == null ? "" : text))
        .append("</")
        .append(name)
        .append(">\n");
  }

  /**
   * An amount with two decimals and the currency (UBL writes it on every amount).
   *
   * @param name the element
   * @param value the amount
   */
  void amount(String name, BigDecimal value) {
    leaf(name, decimal(value), "currencyID=\"" + EUR + "\"");
  }

  /**
   * An amount with two decimals and no currency attribute (CII names the currency once).
   *
   * @param name the element
   * @param value the amount
   */
  void plainAmount(String name, BigDecimal value) {
    leaf(name, decimal(value));
  }

  private static String decimal(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private void indent() {
    out.append("  ".repeat(depth));
  }

  /** Escapes markup and drops the characters XML 1.0 forbids ({@link XmlChars}). */
  private static String escape(String text) {
    return XmlChars.clean(text)
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;");
  }

  @Override
  public String toString() {
    return out.toString();
  }
}
