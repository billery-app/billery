package de.billery.service;

import static de.billery.TestMoney.assertAmount;
import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.api.dto.InvoiceItemDto;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DocumentService#calculateTotalFromItems(List)}: the server computes quantity ×
 * unit price per priced row (rounded to cents), ignores client-sent totals, and counts {@code
 * CUSTOM} note rows as zero.
 */
class DocumentServiceTotalTest {

  @Test
  void emptyListReturnsZero() {
    assertAmount(0.0, DocumentService.calculateTotalFromItems(Collections.emptyList()), 0.0);
    assertAmount(0.0, DocumentService.calculateTotalFromItems(null), 0.0);
  }

  @Test
  void flatListSumsQuantityTimesUnitPrice() {
    List<InvoiceItemDto> items =
        Arrays.asList(
            priced("a", 1, 100.0, null), priced("b", 2, 25.0, null), priced("c", 0.5, 50.0, null));
    assertAmount(175.0, DocumentService.calculateTotalFromItems(items), 0.001);
  }

  @Test
  void childrenAreSummedAlongsideRoots() {
    List<InvoiceItemDto> children =
        Arrays.asList(priced("a.1", 1, 10.0, null), priced("a.2", 1, 5.0, null));
    List<InvoiceItemDto> items =
        Arrays.asList(priced("a", 1, 100.0, children), priced("b", 1, 50.0, null));
    assertAmount(165.0, DocumentService.calculateTotalFromItems(items), 0.001);
  }

  @Test
  void deepNestingRecurses() {
    List<InvoiceItemDto> leaves =
        Arrays.asList(priced("a.1.1", 1, 3.0, null), priced("a.1.2", 1, 4.0, null));
    List<InvoiceItemDto> mid = Arrays.asList(priced("a.1", 1, 10.0, leaves));
    List<InvoiceItemDto> items = Arrays.asList(priced("a", 1, 100.0, mid));
    assertAmount(117.0, DocumentService.calculateTotalFromItems(items), 0.001);
  }

  @Test
  void clientSentTotalIsIgnored() {
    InvoiceItemDto tampered = item("article", "a", 2.0, 10.0, 999.0, null);
    assertAmount(20.0, DocumentService.calculateTotalFromItems(List.of(tampered)), 0.001);
  }

  @Test
  void customNoteRowsNeverContribute() {
    InvoiceItemDto note = item("custom", "Hinweis", 3.0, 50.0, 150.0, null);
    InvoiceItemDto line = priced("a", 1, 40.0, List.of(note));
    assertAmount(40.0, DocumentService.calculateTotalFromItems(List.of(line)), 0.001);
  }

  @Test
  void lineTotalsAreRoundedHalfUpToCents() {
    // 3 × 0.335 = 1.005 → 1.01 (binary double arithmetic alone would give 1.00).
    assertAmount(1.01, DocumentService.lineTotal(bd(3), bd(0.335)), 0.0);
  }

  @Test
  void unitPriceIsRoundedToStoredPrecisionBeforeMultiplying() {
    // unit_price is DECIMAL(10,2): 0.335 is stored as 0.34, so the line must total 3 × 0.34.
    InvoiceItemDto line = priced("a", 3, 0.335, null);
    assertAmount(1.02, DocumentService.calculateTotalFromItems(List.of(line)), 0.0);
  }

  @Test
  void missingQuantityCountsAsOneAndMissingPriceAsZero() {
    InvoiceItemDto noQuantity = item("service", "a", null, 25.0, null, null);
    InvoiceItemDto noPrice = item("service", "b", 4.0, null, null, null);
    assertAmount(
        25.0, DocumentService.calculateTotalFromItems(List.of(noQuantity, noPrice)), 0.001);
  }

  private InvoiceItemDto priced(
      String name, double quantity, double unitPrice, List<InvoiceItemDto> children) {
    return item("article", name, quantity, unitPrice, null, children);
  }

  private InvoiceItemDto item(
      String type,
      String name,
      Double quantity,
      Double unitPrice,
      Double total,
      List<InvoiceItemDto> children) {
    return new InvoiceItemDto(
        null,
        null,
        type,
        0,
        name,
        null,
        "Stk",
        quantity == null ? null : BigDecimal.valueOf(quantity),
        unitPrice == null ? null : BigDecimal.valueOf(unitPrice),
        total == null ? null : BigDecimal.valueOf(total),
        null,
        children,
        null);
  }
}
