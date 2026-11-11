package de.billery.pdf;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.Owner;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Verifies the HTML rendering of line items, in particular the CUSTOM ("Freitext") rows. */
class TemplateBasedPdfGeneratorTest {

  private static final List<Map<String, Object>> DEFAULT_COLUMNS =
      Arrays.asList(
          Map.of("key", "description", "label", "Beschreibung", "width", "50%"),
          Map.of("key", "quantity", "label", "Menge", "width", "15%"),
          Map.of("key", "unit", "label", "Einheit", "width", "10%"),
          Map.of("key", "rate", "label", "Preis", "width", "12%"),
          Map.of("key", "amount", "label", "Gesamt", "width", "13%"));

  private final TemplateBasedPdfGenerator generator = TestWiring.pdfGenerator();

  @Test
  void pricedItemEmitsOneCellPerColumn() {
    StringBuilder html = new StringBuilder();
    InvoiceItem item = pricedItem("Standardarbeit", 2, 50.0, "Stk");

    generator.renderPricedItemRow(html, item, DEFAULT_COLUMNS, "EUR", false);

    String out = html.toString();
    assertTrue(out.startsWith("<tr>") && out.endsWith("</tr>"), "should be a single <tr>…</tr>");
    assertEquals(
        DEFAULT_COLUMNS.size(),
        countOccurrences(out, "<td"),
        "priced row must have one <td> per visible column");
    assertFalse(out.contains("—"), "no em-dashes in a priced row");
    assertFalse(out.contains("colspan="), "no colspan in a priced row");
  }

  @Test
  void customItemRendersSingleMergedCellFromQuantityToEnd() {
    StringBuilder html = new StringBuilder();
    InvoiceItem item = noteItem("Anfahrt", "50 km Anreise\npauschal");

    generator.renderNoteItemRow(html, item, DEFAULT_COLUMNS, false);

    String out = html.toString();
    assertTrue(out.startsWith("<tr>") && out.endsWith("</tr>"));
    assertTrue(out.contains("Anfahrt"), "Bezeichnung must be visible");
    assertTrue(out.contains("50 km Anreise"), "Beschreibung must be visible");
    assertTrue(
        out.contains("colspan=\"" + (DEFAULT_COLUMNS.size() - 1) + "\""),
        "the merged cell must span all columns after the Bezeichnung");
    assertEquals(1, countOccurrences(out, "colspan="), "exactly one colspan on a note row");
    assertFalse(out.contains("—"), "no em-dashes on a note row");
    assertFalse(out.contains("<td>&nbsp;</td>"), "no empty placeholder cells");
  }

  @Test
  void customItemPreservesNewlines() {
    StringBuilder html = new StringBuilder();
    InvoiceItem item = noteItem("Hinweis", "Erste Zeile\nZweite Zeile\nDritte Zeile");

    generator.renderNoteItemRow(html, item, DEFAULT_COLUMNS, false);

    String out = html.toString();
    assertTrue(
        out.contains("white-space: pre-wrap"),
        "merged cell must use pre-wrap so author-entered newlines survive");
    assertTrue(out.contains("Erste Zeile"));
    assertTrue(out.contains("Zweite Zeile"));
  }

  @Test
  void customItemCellUsesAmberBackground() {
    StringBuilder html = new StringBuilder();
    generator.renderNoteItemRow(html, noteItem("X", "y"), DEFAULT_COLUMNS, false);

    assertTrue(
        html.toString().contains("#fffbeb"),
        "note row should use the amber-tinted background to mirror the editor");
  }

  @Test
  void customItemWithEmptyDescriptionStillRendersTheRow() {
    StringBuilder html = new StringBuilder();
    generator.renderNoteItemRow(html, noteItem("Leer", ""), DEFAULT_COLUMNS, false);

    String out = html.toString();
    assertTrue(out.contains("Leer"));
    assertTrue(
        out.contains("colspan=\"4\""),
        "the merged cell must still span to the end even when the description is empty");
  }

  @Test
  void customItemWithEmptyNameRendersWithEmptyNameCell() {
    StringBuilder html = new StringBuilder();
    generator.renderNoteItemRow(html, noteItem("", "nur Beschreibung"), DEFAULT_COLUMNS, false);

    String out = html.toString();
    assertTrue(out.contains("nur Beschreibung"));
    assertFalse(
        out.contains("(ohne Überschrift)"),
        "the PDF should not inject placeholder text; an empty name is shown blank");
  }

  @Test
  void childCustomItemAppliesIndent() {
    StringBuilder html = new StringBuilder();
    generator.renderNoteItemRow(html, noteItem("Kinder", "Hinweis"), DEFAULT_COLUMNS, true);

    String out = html.toString();
    assertTrue(
        out.contains("padding-left: 32px"), "child note rows should keep the tree-indent padding");
  }

  @Test
  void customItemHtmlIsEscaped() {
    StringBuilder html = new StringBuilder();
    generator.renderNoteItemRow(
        html, noteItem("Sicherheit", "<script>alert(1)</script>"), DEFAULT_COLUMNS, false);

    String out = html.toString();
    assertFalse(out.contains("<script>"), "user-entered HTML must be escaped, never emitted raw");
    assertTrue(out.contains("&lt;script&gt;"));
  }

  @Test
  void allVisibleColumnsQuantitiesColumnIsFirstNumeric() {
    StringBuilder html = new StringBuilder();
    generator.renderNoteItemRow(html, noteItem("X", "y"), DEFAULT_COLUMNS, false);

    int tdCount = countOccurrences(html.toString(), "<td");
    assertEquals(2, tdCount, "label cell + merged cell");
  }

  @Test
  void noQuantityColumnMergesFromIndexOne() {
    StringBuilder html = new StringBuilder();
    List<Map<String, Object>> columnsWithoutQuantity =
        Arrays.asList(
            Map.of("key", "description", "label", "Beschreibung"),
            Map.of("key", "unit", "label", "Einheit"),
            Map.of("key", "rate", "label", "Preis"));

    generator.renderNoteItemRow(html, noteItem("X", "y"), columnsWithoutQuantity, false);

    String out = html.toString();
    assertTrue(
        out.contains("colspan=\"2\""),
        "with no quantity column the merge should start at index 1 and span the rest");
  }

  private static InvoiceItem pricedItem(String name, double qty, double price, String unit) {
    InvoiceItem item = new InvoiceItem();
    item.setItemType(InvoiceItem.ItemType.ARTICLE.getValue());
    item.setItemId(1);
    item.setItemName(name);
    item.setItemDescription("");
    item.setQuantity(bd(qty));
    item.setUnitPrice(bd(price));
    item.setTotalPrice(bd(qty * price));
    item.setUnit(unit);
    return item;
  }

  /**
   * Every variable resolves in every block (here: customer data in a footer-like field), and
   * unknown placeholders never reach the document.
   */
  @Test
  void variablesResolveInAnyBlockAndUnknownOnesAreRemoved() {
    Owner owner =
        new Owner(
            1,
            "Max",
            "Muster",
            "Muster GmbH",
            "",
            "12345",
            "Stadt",
            "Weg",
            1,
            "",
            "",
            "",
            "",
            "",
            false);
    Customer customer = new Customer("Anna", "Müller", "54321", "Ort", "Gasse", 5, "", "", "", "");
    Invoice invoice = new Invoice();
    invoice.setNotes("Danke");
    var vars = new TemplateBasedPdfGenerator.VariableContext(owner, customer, invoice);

    String out =
        generator.replaceVariables("{{firma}} an {{kunde}}: {{notizen}} {{rechnungnummer}}!", vars);

    assertEquals("Muster GmbH an Anna Müller: Danke !", out);
    assertEquals("Muster GmbH", generator.replaceVariables("{{ firma }}", vars));
  }

  /** Config colors end up in style attributes: anything but a hex color is replaced. */
  @Test
  void safeColorAcceptsHexColorsOnly() {
    assertEquals("#2563eb", TemplateBasedPdfGenerator.safeColor("#2563eb", "#000"));
    assertEquals("#000", TemplateBasedPdfGenerator.safeColor("red; background: url(x)", "#000"));
    assertEquals("#000", TemplateBasedPdfGenerator.safeColor(null, "#000"));
  }

  /** Old editors stored the 18pt title size as the block's font size; it means 10.5pt body. */
  @Test
  void invoiceMetaReadsLegacyTitleSizesAsBodySize() {
    assertEquals(10.5, TemplateBasedPdfGenerator.metaBodyFontSizeOf(18));
    assertEquals(12.0, TemplateBasedPdfGenerator.metaBodyFontSizeOf(12));
  }

  /** Two consecutive half-width blocks share a table row; a lone one takes the left half. */
  @Test
  void halfWidthBlocksShareARow() {
    String html =
        TemplateBasedPdfGenerator.layoutBlocks(
            List.of("<p>A</p>", "<p>B</p>", "<p>C</p>", "<p>D</p>"),
            List.of(false, true, true, true));

    assertTrue(html.startsWith("<p>A</p><table class=\"block-row\""), html);
    assertEquals(2, countOccurrences(html, "class=\"block-row\""));
    assertTrue(html.indexOf("<p>B</p>") < html.indexOf("<p>C</p>"));
    assertTrue(
        html.endsWith(
            "<p>D</p></td><td style=\"width: 50%; vertical-align: top; padding: 0 0 0 12px; border: none;\"></td></tr></table>"),
        html);
  }

  /** Flying Saucer ignores max-width/max-height on images: the logo gets an explicit size. */
  @Test
  void logoIsScaledDownToItsBoxButNeverEnlarged() throws Exception {
    java.nio.file.Path dir = de.billery.config.AppConfig.FILES_PATH;
    java.nio.file.Files.createDirectories(dir);
    java.nio.file.Path big = dir.resolve("logo-parity-big.png");
    java.nio.file.Path small = dir.resolve("logo-parity-small.png");
    try {
      javax.imageio.ImageIO.write(
          new java.awt.image.BufferedImage(1600, 400, java.awt.image.BufferedImage.TYPE_INT_RGB),
          "png",
          big.toFile());
      javax.imageio.ImageIO.write(
          new java.awt.image.BufferedImage(40, 20, java.awt.image.BufferedImage.TYPE_INT_RGB),
          "png",
          small.toFile());

      assertEquals(
          "width: 200px; height: 50px;",
          TestWiring.pdfGenerator()
              .logoSizeStyle("http://127.0.0.1:8080/uploads/logo-parity-big.png"));
      assertEquals(
          "width: 40px; height: 20px;",
          TestWiring.pdfGenerator().logoSizeStyle("/uploads/logo-parity-small.png"));
      assertEquals(
          "height: 60px;", TestWiring.pdfGenerator().logoSizeStyle("/uploads/missing.svg"));
    } finally {
      java.nio.file.Files.deleteIfExists(big);
      java.nio.file.Files.deleteIfExists(small);
    }
  }

  private static InvoiceItem noteItem(String name, String description) {
    InvoiceItem item = new InvoiceItem();
    item.setItemType(InvoiceItem.ItemType.CUSTOM.getValue());
    item.setItemId(0);
    item.setItemName(name);
    item.setItemDescription(description);
    item.setQuantity(bd(1.0));
    item.setUnitPrice(bd(0.0));
    item.setTotalPrice(bd(0.0));
    item.setUnit("");
    return item;
  }

  private static int countOccurrences(String haystack, String needle) {
    int count = 0;
    int from = 0;
    while (true) {
      int idx = haystack.indexOf(needle, from);
      if (idx < 0) {
        return count;
      }
      count++;
      from = idx + needle.length();
    }
  }
}
