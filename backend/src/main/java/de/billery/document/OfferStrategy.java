package de.billery.document;

import java.util.List;

/**
 * Offer lifecycle (shared with {@link CostCalculationStrategy}): a draft is sent ({@code SENT}) and
 * then accepted or rejected by the customer; a rejection can still be reverted to an acceptance.
 * Acceptance directly from the draft stays possible for a customer who agrees at once. An accepted
 * offer is converted into the next document or cancelled. {@code CONVERTED} and {@code CANCELLED}
 * are final. Setting the status a document already has is not a transition; {@code
 * InvoiceController} answers it as a no-op. {@code
 * backend/src/test/resources/status-parity/transitions.json} pins this table, and the SPA checks
 * its status buttons against the same file.
 */
public class OfferStrategy extends BaseDocumentTypeStrategy {

  static final int[][] TRANSITIONS = {
    {1, 2}, {1, 3}, {1, 8}, {2, 3}, {2, 4}, {2, 8}, {4, 3}, {4, 8}, {3, 5}, {3, 8}
  };

  public OfferStrategy() {
    super(2, "Angebot", "Angebotsnummer");
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
