package de.billery.document;

/** Ids of the {@code document_type} lookup table. */
public final class DocumentType {

  /** Rechnung. */
  public static final int INVOICE = 1;

  /** Angebot. */
  public static final int OFFER = 2;

  /** Kostenberechnung / Kostenvoranschlag. */
  public static final int COST_CALCULATION = 3;

  /** Teilrechnung. */
  public static final int PARTED_INVOICE = 4;

  /** Schluss-/Endrechnung. */
  public static final int FINAL_INVOICE = 5;

  /** Stornorechnung: cancels an issued invoice; its amounts count negatively. */
  public static final int CREDIT_NOTE = 6;

  private DocumentType() {}

  /**
   * Tells whether documents of this type are invoices in the tax sense (§14 UStG): they get their
   * number when issued, are immutable afterwards, carry payments and are cancelled by a credit
   * note.
   *
   * @param documentTypeId the type
   * @return {@code true} for invoice, part invoice and final invoice
   */
  public static boolean isInvoice(int documentTypeId) {
    return documentTypeId == INVOICE
        || documentTypeId == PARTED_INVOICE
        || documentTypeId == FINAL_INVOICE;
  }

  /**
   * Tells whether documents of this type are bookkeeping records that must never be changed or
   * deleted once issued: the invoice types and credit notes.
   *
   * @param documentTypeId the type
   * @return {@code true} for invoice types and credit notes
   */
  public static boolean isBookkeepingRecord(int documentTypeId) {
    return isInvoice(documentTypeId) || documentTypeId == CREDIT_NOTE;
  }
}
