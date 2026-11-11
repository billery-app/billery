package de.billery.document;

import java.util.List;

public class PartedInvoiceStrategy extends BaseDocumentTypeStrategy {

  /**
   * Status changes a client may request. DRAFT to SENT issues the invoice; PAID records the open
   * amount as one payment; CANCELLED creates a credit note. All three run in {@code
   * InvoiceLifecycleService}. PARTIALLY_PAID is never requested, it follows from the payments.
   */
  private static final int[][] TRANSITIONS = {{1, 2}, {2, 6}, {9, 6}, {2, 8}, {9, 8}, {6, 8}};

  public PartedInvoiceStrategy() {
    super(4, "Teilrechnung", "Rechnungsnummer");
  }

  @Override
  public boolean isValidTransition(int fromStatus, int toStatus) {
    return isAllowed(fromStatus, toStatus, TRANSITIONS);
  }

  @Override
  public List<Integer> getAvailableStatuses(int currentStatus) {
    return buildStatusList(currentStatus, TRANSITIONS);
  }
}
