package de.billery.document;

import java.util.List;

/**
 * Stornorechnung (credit note). It is created already issued by {@code InvoiceLifecycleService}
 * when an invoice is cancelled and never changes status afterwards.
 */
public class CreditNoteStrategy extends BaseDocumentTypeStrategy {

  public CreditNoteStrategy() {
    super(DocumentType.CREDIT_NOTE, "Stornorechnung", "Stornorechnungsnummer");
  }

  @Override
  public int getDefaultStatus() {
    return DocumentStatus.SENT;
  }

  @Override
  public boolean isValidTransition(int fromStatus, int toStatus) {
    return false;
  }

  @Override
  public List<Integer> getAvailableStatuses(int currentStatus) {
    return List.of(currentStatus);
  }
}
