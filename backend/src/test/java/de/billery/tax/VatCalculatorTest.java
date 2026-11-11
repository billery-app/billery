package de.billery.tax;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.data.InvoiceItem;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** {@link VatCalculator}: grouping by rate, rounding per group, sub-items, notes, treatments. */
class VatCalculatorTest {

  @Test
  void groupsByRateAndSumsTheGroups() {
    VatBreakdown vat =
        VatCalculator.calculate(
            List.of(line(1, 100.00, 19), line(2, 25.00, 7)), TaxTreatment.STANDARD);

    assertEquals(2, vat.groups().size());
    assertEquals(group("7.00", "50.00", "3.50"), vat.groups().get(0));
    assertEquals(group("19.00", "100.00", "19.00"), vat.groups().get(1));
    assertEquals(new BigDecimal("150.00"), vat.net());
    assertEquals(new BigDecimal("22.50"), vat.tax());
    assertEquals(new BigDecimal("172.50"), vat.gross());
  }

  @Test
  void roundsOncePerGroupNotPerLine() {
    // Per line: 3 × round(0.03 × 19 %) = 3 × 0.01 = 0.03. Per group: round(0.09 × 19 %) = 0.02.
    VatBreakdown vat =
        VatCalculator.calculate(
            List.of(line(1, 0.03, 19), line(1, 0.03, 19), line(1, 0.03, 19)),
            TaxTreatment.STANDARD);

    assertEquals(new BigDecimal("0.02"), vat.tax());
  }

  @Test
  void includesSubItemsAndIgnoresNotes() {
    InvoiceItem parent = line(1, 100.00, 19);
    InvoiceItem child = line(2, 10.00, 7);
    InvoiceItem note = new InvoiceItem(0, 0, "CUSTOM", 0, bd(1), bd(0), bd(0));
    note.setTaxRatePercent(bd(0));
    parent.setChildren(new ArrayList<>(List.of(child, note)));

    VatBreakdown vat = VatCalculator.calculate(List.of(parent), TaxTreatment.STANDARD);

    assertEquals(new BigDecimal("120.00"), vat.net());
    assertEquals(new BigDecimal("20.40"), vat.tax());
    assertEquals(2, vat.groups().size(), "the note adds no 0 % group");
  }

  @Test
  void treatmentsWithoutVatPutEverythingIntoOneZeroGroup() {
    for (TaxTreatment treatment :
        List.of(
            TaxTreatment.SMALL_BUSINESS, TaxTreatment.REVERSE_CHARGE, TaxTreatment.TAX_EXEMPT)) {
      VatBreakdown vat =
          VatCalculator.calculate(List.of(line(1, 100, 19), line(1, 50, 7)), treatment);

      assertEquals(List.of(group("0.00", "150.00", "0.00")), vat.groups(), treatment.name());
      assertEquals(new BigDecimal("150.00"), vat.gross());
    }
  }

  @Test
  void anEmptyDocumentHasZeroTotals() {
    VatBreakdown vat = VatCalculator.calculate(List.of(), TaxTreatment.STANDARD);

    assertTrue(vat.groups().isEmpty());
    assertEquals(new BigDecimal("0.00"), vat.gross());
  }

  @Test
  void onlyTheRatesOfParagraph12AreAllowed() {
    assertTrue(VatCalculator.isAllowedRate(bd(19)));
    assertTrue(VatCalculator.isAllowedRate(bd(7.0)));
    assertFalse(VatCalculator.isAllowedRate(bd(16)));
    assertFalse(VatCalculator.isAllowedRate(bd(0)));
  }

  @Test
  void treatmentsCarryTheirLegalNoteAndXRechnungCategory() {
    assertEquals("S", TaxTreatment.STANDARD.xrechnungCategory());
    assertEquals("", TaxTreatment.STANDARD.legalNote(""));
    assertEquals("AE", TaxTreatment.REVERSE_CHARGE.xrechnungCategory());
    assertTrue(TaxTreatment.REVERSE_CHARGE.legalNote("").contains("§ 13b"));
    assertTrue(TaxTreatment.SMALL_BUSINESS.legalNote("").contains("§ 19"));
    assertEquals(
        "Steuerfreie Leistung gemäß § 4 Nr. 14 UStG.",
        TaxTreatment.TAX_EXEMPT.legalNote("gemäß § 4 Nr. 14 UStG"));
    assertEquals(TaxTreatment.STANDARD, TaxTreatment.parse("unknown"));
    assertEquals(TaxTreatment.REVERSE_CHARGE, TaxTreatment.parse("reverse_charge"));
  }

  private static InvoiceItem line(double quantity, double unitPrice, double rate) {
    InvoiceItem item =
        new InvoiceItem(
            0,
            0,
            "ARTICLE",
            0,
            bd(quantity),
            bd(unitPrice),
            InvoiceItem.lineTotal(bd(quantity), bd(unitPrice)));
    item.setTaxRatePercent(bd(rate));
    return item;
  }

  private static VatBreakdown.Group group(String rate, String net, String tax) {
    return new VatBreakdown.Group(new BigDecimal(rate), new BigDecimal(net), new BigDecimal(tax));
  }
}
