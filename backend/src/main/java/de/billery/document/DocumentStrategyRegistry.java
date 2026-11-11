package de.billery.document;

import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Maps document type ids to their {@link DocumentTypeStrategy}. Stateless after construction, so a
 * single Spring bean is shared by all services; unknown type ids fall back to the invoice strategy
 * (type 1).
 */
@Component
public class DocumentStrategyRegistry {

  private final Map<Integer, DocumentTypeStrategy> strategies = new HashMap<>();

  public DocumentStrategyRegistry() {
    register(new InvoiceStrategy());
    register(new OfferStrategy());
    register(new CostCalculationStrategy());
    register(new PartedInvoiceStrategy());
    register(new FinalInvoiceStrategy());
    register(new CreditNoteStrategy());
  }

  public final void register(DocumentTypeStrategy strategy) {
    strategies.put(strategy.getDocumentTypeId(), strategy);
  }

  public DocumentTypeStrategy getStrategy(int documentTypeId) {
    DocumentTypeStrategy strategy = strategies.get(documentTypeId);
    if (strategy == null) {
      return strategies.get(1);
    }
    return strategy;
  }
}
