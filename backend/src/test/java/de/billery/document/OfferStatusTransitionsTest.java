package de.billery.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The offer / cost calculation lifecycle against {@code status-parity/transitions.json}, which the
 * SPA's {@code useDocumentType.spec.js} reads as well.
 */
class OfferStatusTransitionsTest {

  private static final int[] ALL_STATUSES = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};

  private final JsonNode fixture = load();

  @Test
  void everyPairOfStatusesIsAllowedExactlyWhenTheFixtureListsIt() {
    for (JsonNode type : fixture.get("documentTypes")) {
      DocumentTypeStrategy strategy = new DocumentStrategyRegistry().getStrategy(type.asInt());
      for (int from : ALL_STATUSES) {
        for (int to : ALL_STATUSES) {
          assertEquals(
              listed(from, to),
              strategy.isValidTransition(from, to),
              "type " + type + ": " + from + " -> " + to);
        }
      }
    }
  }

  @Test
  void availableStatusesAreTheCurrentOneAndItsTargets() {
    for (JsonNode type : fixture.get("documentTypes")) {
      DocumentTypeStrategy strategy = new DocumentStrategyRegistry().getStrategy(type.asInt());
      for (int from : ALL_STATUSES) {
        List<Integer> expected = new ArrayList<>(List.of(from));
        for (JsonNode pair : fixture.get("transitions")) {
          if (pair.get(0).asInt() == from) {
            expected.add(pair.get(1).asInt());
          }
        }
        assertEquals(
            expected.stream().distinct().sorted().toList(),
            strategy.getAvailableStatuses(from),
            "type " + type + " from " + from);
      }
    }
  }

  @Test
  void convertedAndCancelledAreFinal() {
    DocumentTypeStrategy offer = new DocumentStrategyRegistry().getStrategy(DocumentType.OFFER);

    for (int to : ALL_STATUSES) {
      assertFalse(offer.isValidTransition(DocumentStatus.CONVERTED, to));
      assertFalse(offer.isValidTransition(DocumentStatus.CANCELLED, to));
    }
  }

  @Test
  void aRejectedOfferCanStillBeAccepted() {
    DocumentTypeStrategy offer = new DocumentStrategyRegistry().getStrategy(DocumentType.OFFER);

    assertTrue(offer.isValidTransition(DocumentStatus.REJECTED, DocumentStatus.ACCEPTED));
    assertTrue(offer.isValidTransition(DocumentStatus.DRAFT, DocumentStatus.ACCEPTED));
  }

  private boolean listed(int from, int to) {
    for (JsonNode pair : fixture.get("transitions")) {
      if (pair.get(0).asInt() == from && pair.get(1).asInt() == to) {
        return true;
      }
    }
    return false;
  }

  private static JsonNode load() {
    try (InputStream in =
        OfferStatusTransitionsTest.class.getResourceAsStream("/status-parity/transitions.json")) {
      return new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
