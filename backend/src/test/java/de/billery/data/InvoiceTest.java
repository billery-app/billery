package de.billery.data;

import static de.billery.TestMoney.assertAmount;
import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class InvoiceTest {

  @Test
  public void testTaxCalculation() {
    // Eine Rechnung mit 19% Steuer erstellen
    Invoice invoice = new Invoice();
    invoice.setTotalPrice(bd(100.0)); // 100€ Nettobetrag
    invoice.setTaxRate(bd(19)); // 19% Steuersatz

    // Erwartete Werte
    double expectedNetAmount = 100.0;
    double expectedTaxAmount = 19.0; // 100 * 19 / 100
    double expectedGrossAmount = 119.0; // 100 + 19

    // Testen der Berechnungen
    assertAmount(
        expectedNetAmount,
        invoice.getNetAmount(),
        0.01,
        "Nettobetrag sollte gleich totalPrice sein");
    assertAmount(
        expectedTaxAmount,
        invoice.getTaxAmount(),
        0.01,
        "Steuerbetrag sollte 19€ bei 100€ Netto und 19% Steuersatz sein");
    assertAmount(
        expectedGrossAmount,
        invoice.getGrossAmount(),
        0.01,
        "Bruttobetrag sollte 119€ bei 100€ Netto und 19% Steuersatz sein");
  }

  @Test
  public void testTaxCalculationWithDifferentRate() {
    // Eine Rechnung mit 7% Steuer erstellen (reduzierter Steuersatz)
    Invoice invoice = new Invoice();
    invoice.setTotalPrice(bd(50.0)); // 50€ Nettobetrag
    invoice.setTaxRate(bd(7)); // 7% Steuersatz

    // Erwartete Werte
    double expectedNetAmount = 50.0;
    double expectedTaxAmount = 3.5; // 50 * 7 / 100
    double expectedGrossAmount = 53.5; // 50 + 3.5

    // Testen der Berechnungen
    assertAmount(expectedNetAmount, invoice.getNetAmount(), 0.01);
    assertAmount(expectedTaxAmount, invoice.getTaxAmount(), 0.01);
    assertAmount(expectedGrossAmount, invoice.getGrossAmount(), 0.01);
  }

  @Test
  public void testZeroTax() {
    // Eine Rechnung ohne Steuer
    Invoice invoice = new Invoice();
    invoice.setTotalPrice(bd(200.0)); // 200€ Nettobetrag
    invoice.setTaxRate(bd(0.0)); // 0% Steuersatz

    // Bei 0% Steuer sollten alle Beträge gleich sein
    assertAmount(200.0, invoice.getNetAmount(), 0.01);
    assertAmount(0.0, invoice.getTaxAmount(), 0.01);
    assertAmount(200.0, invoice.getGrossAmount(), 0.01);
  }

  @Test
  public void testFormattedInvoiceNumber() {
    Invoice invoice = new Invoice();
    invoice.setId(42);

    assertEquals(
        Invoice.DRAFT_LABEL,
        invoice.getFormattedInvoiceNumber(),
        "Ein Entwurf ohne Nummer darf keine erfundene Rechnungsnummer zeigen");

    invoice.setDocumentNumber("RE-2026-007");
    assertEquals("RE-2026-007", invoice.getFormattedInvoiceNumber());
  }

  @Test
  public void testToString() {
    Invoice invoice = new Invoice();
    invoice.setId(1);
    invoice.setTotalPrice(bd(100.0));
    invoice.setTaxRate(bd(19));
    invoice.setPaid(false);
    invoice.setDocumentNumber("RE-2026-001");

    String result = invoice.toString();
    System.out.println("toString result: " + result); // Debug output

    assertTrue(result.contains("RE-2026-001"), "toString sollte Rechnungsnummer enthalten");
    assertTrue(
        result.contains("119"),
        "toString sollte Bruttobetrag enthalten"); // Geändert zu "119" ohne Komma
    assertTrue(result.contains("offen"), "toString sollte Zahlungsstatus enthalten");
  }
}
