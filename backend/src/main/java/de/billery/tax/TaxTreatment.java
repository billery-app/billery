package de.billery.tax;

import java.util.Locale;

/**
 * How VAT applies to a whole document. Only {@link #STANDARD} charges VAT, with a rate per line;
 * every other treatment charges none and must print its legal basis on the invoice (§14 Abs. 4 Nr.
 * 9 UStG for exemptions, §14a Abs. 5 UStG for reverse charge, §34a UStDV for small businesses).
 *
 * <p>The {@code xrechnungCategory} is the VAT category code of EN 16931 (UNTDID 5305) used in the
 * XRechnung.
 */
public enum TaxTreatment {

  /** Regelbesteuerung: 19 % or 7 % per line. */
  STANDARD("S", ""),

  /** Kleinunternehmer (§19 UStG): no VAT is charged. */
  SMALL_BUSINESS("E", "Gemäß § 19 UStG wird keine Umsatzsteuer berechnet (Kleinunternehmer)."),

  /**
   * Reverse charge (§13b UStG), e.g. construction services between contractors: the customer owes
   * the VAT. The customer's VAT ID or tax number is required.
   */
  REVERSE_CHARGE("AE", "Steuerschuldnerschaft des Leistungsempfängers (§ 13b UStG)."),

  /** VAT-exempt supply (§4 UStG); the exemption basis is entered per document. */
  TAX_EXEMPT("E", "Steuerfreie Leistung");

  private final String categoryCode;
  private final String noteText;

  TaxTreatment(String xrechnungCategory, String legalNote) {
    this.categoryCode = xrechnungCategory;
    this.noteText = legalNote;
  }

  /**
   * VAT category code for the XRechnung (EN 16931 BT-118 / BT-151).
   *
   * @return {@code S}, {@code E} or {@code AE}
   */
  public String xrechnungCategory() {
    return categoryCode;
  }

  /**
   * Tells whether this treatment charges VAT.
   *
   * @return {@code true} only for {@link #STANDARD}
   */
  public boolean chargesVat() {
    return this == STANDARD;
  }

  /**
   * The note the invoice must carry for this treatment (empty for {@link #STANDARD}).
   *
   * @param exemptionReason the document's own exemption basis, used for {@link #TAX_EXEMPT}
   * @return the German note
   */
  public String legalNote(String exemptionReason) {
    if (this == TAX_EXEMPT) {
      String reason = exemptionReason == null ? "" : exemptionReason.trim();
      return reason.isEmpty() ? noteText + "." : noteText + " " + reason + ".";
    }
    return noteText;
  }

  /**
   * Parses a stored or submitted value; unknown or empty values mean {@link #STANDARD}.
   *
   * @param value the name, case-insensitive
   * @return the treatment
   */
  public static TaxTreatment parse(String value) {
    if (value == null || value.isBlank()) {
      return STANDARD;
    }
    try {
      return valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      return STANDARD;
    }
  }
}
