package de.billery.document;

import java.util.List;

/** Cost calculations follow the offer lifecycle, see {@link OfferStrategy}. */
public class CostCalculationStrategy extends BaseDocumentTypeStrategy {

  private static final int[][] TRANSITIONS = OfferStrategy.TRANSITIONS;

  public CostCalculationStrategy() {
    super(3, "Kostenberechnung", "Kostenberechnungsnummer");
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
