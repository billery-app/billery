package de.billery.pdf;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import de.billery.TestWiring;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Renderer parity: the PDF renderer must show what the template editor preview shows.
 *
 * <p>Every case in {@code template-parity/cases.json} is rendered here with the Java renderer and
 * in {@code frontend/src/utils/templateParity.spec.js} with the editor's block renderer, from the
 * same document data. Both must contain each expected text (tags stripped, entities decoded,
 * whitespace collapsed) and each expected style declaration, and none of the absent texts. A case
 * that fails on one side only means the two renderers drifted apart.
 */
class TemplateParityTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final TemplateBasedPdfGenerator generator = TestWiring.pdfGenerator();

  @TestFactory
  Stream<DynamicTest> everyCaseRendersLikeTheEditorPreview() throws Exception {
    JsonNode fixture;
    try (InputStream in = getClass().getResourceAsStream("/template-parity/cases.json")) {
      fixture = JSON.readTree(in);
    }
    JsonNode context = fixture.get("context");
    List<DynamicTest> tests = new ArrayList<>();
    for (JsonNode testCase : fixture.get("cases")) {
      tests.add(
          DynamicTest.dynamicTest(
              testCase.get("name").asText(), () -> assertCase(context, testCase)));
    }
    return tests.stream();
  }

  private void assertCase(JsonNode context, JsonNode testCase) throws Exception {
    ObjectNode invoiceData = context.get("invoice").deepCopy();
    if (testCase.has("invoice")) {
      invoiceData.setAll((ObjectNode) testCase.get("invoice"));
    }
    InvoiceTemplate template = new InvoiceTemplate();
    template.setDocumentTypeId(invoiceData.get("documentTypeId").asInt());
    template.setBlocks(JSON.writeValueAsString(List.of(testCase.get("block"))));
    template.setCssContent("");
    template.setMarginTopMm(10);
    template.setMarginRightMm(10);
    template.setMarginBottomMm(10);
    template.setMarginLeftMm(10);

    String html =
        generator.generateHtmlFromBlocks(
            template,
            invoice(invoiceData),
            customer(context.get("customer")),
            owner(context.get("owner")),
            items(context.get("items")));
    String body = html.substring(html.indexOf("<body>"));
    String text = visibleText(body);
    String styles = normalizeSizes(body);

    for (JsonNode expected : testCase.path("expectText")) {
      assertTrue(
          text.contains(expected.asText()), "missing text '" + expected.asText() + "' in: " + text);
    }
    for (JsonNode absent : testCase.path("expectAbsent")) {
      assertFalse(
          text.contains(absent.asText()), "unexpected text '" + absent.asText() + "' in: " + text);
    }
    for (JsonNode style : testCase.path("expectStyle")) {
      assertTrue(
          styles.contains(style.asText()), "missing style '" + style.asText() + "' in: " + styles);
    }
  }

  /** Text a reader sees: tags removed, entities decoded, whitespace collapsed. */
  static String visibleText(String html) {
    return html.replaceAll("<[^>]+>", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace(' ', ' ')
        .replaceAll("\\s+", " ")
        .trim();
  }

  /** {@code 10.50pt} → {@code 10.5pt}: the renderers format sizes differently, not their value. */
  static String normalizeSizes(String html) {
    Matcher m = Pattern.compile("(\\d+)\\.(\\d*?)0+pt").matcher(html);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      m.appendReplacement(
          out, m.group(2).isEmpty() ? m.group(1) + "pt" : m.group(1) + "." + m.group(2) + "pt");
    }
    m.appendTail(out);
    return out.toString();
  }

  private static Owner owner(JsonNode o) {
    return new Owner(
        1,
        "",
        "",
        o.get("companyName").asText(),
        "",
        o.get("postalCode").asText(),
        o.get("city").asText(),
        o.get("street").asText(),
        o.get("houseNumber").asInt(),
        o.get("phone").asText(),
        o.get("email").asText(),
        o.get("url").asText(),
        o.get("taxId").asText(),
        o.get("iban").asText(),
        false);
  }

  private static Customer customer(JsonNode c) {
    return new Customer(
        c.get("firstName").asText(),
        c.get("lastName").asText(),
        c.get("postalCode").asText(),
        c.get("city").asText(),
        c.get("street").asText(),
        c.get("houseNumber").asInt(),
        c.get("phone").asText(),
        c.get("email").asText(),
        "",
        "");
  }

  private static Invoice invoice(JsonNode i) {
    Invoice invoice = new Invoice();
    invoice.setDocumentTypeId(i.get("documentTypeId").asInt());
    invoice.setDocumentNumber(i.get("invoiceNumber").asText());
    invoice.setInvoiceDate(i.get("invoiceDate").asText());
    invoice.setDueDate(i.get("dueDate").asText());
    invoice.setWorkStartDate(i.get("workStartDate").asText());
    invoice.setWorkEndDate(i.get("workEndDate").asText());
    invoice.setNotes(i.get("notes").asText());
    invoice.setTaxTreatment(i.get("taxTreatment").asText());
    invoice.setTotalPrice(new BigDecimal(i.get("netAmount").asText()));
    return invoice;
  }

  private static List<InvoiceItem> items(JsonNode list) {
    List<InvoiceItem> items = new ArrayList<>();
    for (JsonNode node : list) {
      InvoiceItem item = new InvoiceItem();
      boolean note = "custom".equalsIgnoreCase(node.get("itemType").asText());
      item.setItemType(
          note ? InvoiceItem.ItemType.CUSTOM.getValue() : node.get("itemType").asText());
      item.setItemName(node.get("itemName").asText());
      item.setItemDescription(node.path("itemDescription").asText(""));
      BigDecimal quantity = note ? BigDecimal.ONE : new BigDecimal(node.get("quantity").asText());
      BigDecimal price = note ? BigDecimal.ZERO : new BigDecimal(node.get("unitPrice").asText());
      item.setQuantity(quantity);
      item.setUnitPrice(price);
      item.setTotalPrice(quantity.multiply(price));
      item.setUnit(node.path("unit").asText(""));
      if (node.has("taxRate")) {
        item.setTaxRatePercent(new BigDecimal(node.get("taxRate").asText()));
      }
      if (node.has("children")) {
        item.setChildren(items(node.get("children")));
      }
      items.add(item);
    }
    return items;
  }
}
