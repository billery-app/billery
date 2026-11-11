package de.billery.api.controller;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.dto.ArticleDto;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.repository.OwnerRepository;
import de.billery.service.DocumentStateException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

/**
 * VAT per line, tax treatments, units, and the XRechnung, end to end through {@link
 * InvoiceController} against the H2 test database.
 *
 * <p>{@link #generatedXRechnungsPassTheKositValidator} runs only when {@code
 * XRECHNUNG_VALIDATOR_DIR} points at a directory with the unpacked KoSIT validator ({@code
 * validator/validationtool-1.5.0-standalone.jar}) and configuration ({@code config/scenarios.xml})
 * from {@code XRechnung/} — see {@code docs/guides/testing.md}.
 */
class VatAndEInvoiceTest {

  private static final int INVOICE = 1;
  private static final int SENT = 2;
  private static final int PAID = 6;

  private InvoiceController controller;
  private CustomerController customers;
  private int customerId;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void freshData() throws Exception {
    TestDatabaseConfig.clearData();
    for (int type = 1; type <= 4; type++) {
      InvoiceTemplate template = new InvoiceTemplate();
      template.setName("Default " + type);
      template.setDocumentTypeId(type);
      template.setDefault(true);
      template.setBlocks(BLOCKS);
      TestWiring.templateRepository().save(template);
    }
    controller = TestWiring.invoiceController();
    customers = TestWiring.customerController();
    customerId = customers.createCustomer(customer("", "")).getId();
    saveOwner(false, "030 123456");
  }

  // --- VAT per line ------------------------------------------------------------------------------

  @Test
  void mixedRatesAreSummedPerRateInTheApiPaymentsAndPdf() {
    InvoiceDto draft =
        create(dto(List.of(item("Beratung", 1.5, 100, "Std", 19), item("Buch", 2, 25, "Stk", 7))));

    // 150.00 @ 19 % = 28.50, 50.00 @ 7 % = 3.50
    assertEquals(new BigDecimal("200.00"), draft.netAmount());
    assertEquals(new BigDecimal("32.00"), draft.taxAmount());
    assertEquals(new BigDecimal("232.00"), draft.grossAmount());
    assertEquals(2, draft.vatGroups().size());
    assertEquals("Std", draft.items().get(0).unit(), "unit stored on the line");
    assertEquals(0, new BigDecimal("7").compareTo(draft.items().get(1).taxRate()));

    int id = issue(draft.id()).id();
    controller.updateInvoiceStatus(id, new StatusChangeRequest(PAID, LocalDate.now(), null));
    InvoiceDto paid = controller.getInvoice(id).getBody();
    assertEquals(PAID, paid.documentStatusId());
    assertEquals(
        new BigDecimal("232.00"), paid.paidAmount(), "full payment = gross incl. both rates");

    String html = TestWiring.invoiceRepository().findById(id).getRenderedHtml();
    assertTrue(html.contains("MwSt. 19 % auf 150,00 €"), html);
    assertTrue(html.contains("MwSt. 7 % auf 50,00 €"));
    assertTrue(html.contains(">USt.</th>"), "mixed rates add a VAT column");
    assertTrue(html.contains(">1,5<"), "decimal quantities are not truncated");
    assertTrue(html.contains("232,00 €"));
  }

  @Test
  void onlyNineteenAndSevenPercentAreAccepted() {
    DocumentStateException e =
        assertThrows(
            DocumentStateException.class,
            () -> create(dto(List.of(item("Altsatz", 1, 10, "Stk", 16)))));

    assertTrue(e.getMessage().contains("16"));
  }

  @Test
  void aKleinunternehmerAlwaysInvoicesWithoutVatAndWithTheParagraph19Note() {
    saveOwner(true, "030 123456");

    InvoiceDto draft = create(dto(List.of(item("Beratung", 1, 100, "Std", 19))));
    int id = issue(draft.id()).id();

    assertEquals("SMALL_BUSINESS", draft.taxTreatment());
    assertEquals(new BigDecimal("0.00"), draft.taxAmount());
    String html = TestWiring.invoiceRepository().findById(id).getRenderedHtml();
    assertTrue(html.contains("§ 19 UStG"));
    assertFalse(html.contains("MwSt. 19 %"));
  }

  @Test
  void onlyAKleinunternehmerMayUseParagraph19() {
    InvoiceDto draft =
        create(withTreatment(dto(List.of(item("X", 1, 100, "Stk", 19))), "SMALL_BUSINESS", null));

    assertEquals("STANDARD", draft.taxTreatment());
    assertEquals(new BigDecimal("19.00"), draft.taxAmount());
  }

  @Test
  void reverseChargeNeedsTheCustomersTaxIdAndPrintsTheParagraph13bNote() {
    InvoiceDto draft =
        create(
            withTreatment(dto(List.of(item("Estrich", 10, 30, "m²", 19))), "REVERSE_CHARGE", null));

    DocumentStateException e = assertThrows(DocumentStateException.class, () -> issue(draft.id()));
    assertTrue(e.getMessage().contains("§ 13b"));

    customers.updateCustomer(customerId, customer("DE123456789", ""));
    int id = issue(draft.id()).id();

    InvoiceDto issued = controller.getInvoice(id).getBody();
    assertEquals(new BigDecimal("0.00"), issued.taxAmount());
    assertEquals(new BigDecimal("300.00"), issued.grossAmount());
    String html = TestWiring.invoiceRepository().findById(id).getRenderedHtml();
    assertTrue(html.contains("Steuerschuldnerschaft des Leistungsempfängers (§ 13b UStG)."));
  }

  @Test
  void aTaxExemptInvoiceNeedsItsLegalBasis() {
    InvoiceDto noReason =
        create(withTreatment(dto(List.of(item("Kurs", 1, 100, "Stk", 19))), "TAX_EXEMPT", null));
    assertThrows(DocumentStateException.class, () -> issue(noReason.id()));

    InvoiceDto withReason =
        create(
            withTreatment(
                dto(List.of(item("Kurs", 1, 100, "Stk", 19))),
                "TAX_EXEMPT",
                "gemäß § 4 Nr. 21 UStG"));
    int id = issue(withReason.id()).id();

    String html = TestWiring.invoiceRepository().findById(id).getRenderedHtml();
    assertTrue(html.contains("Steuerfreie Leistung gemäß § 4 Nr. 21 UStG."));
  }

  @Test
  void aCreditNoteKeepsTheRatesAndTreatmentOfTheInvoice() {
    int id =
        issue(create(dto(List.of(item("A", 1, 100, "Stk", 19), item("B", 1, 100, "Stk", 7)))).id())
            .id();

    InvoiceDto creditNote = controller.cancelInvoice(id);

    assertEquals(new BigDecimal("26.00"), creditNote.taxAmount());
    assertEquals(2, creditNote.vatGroups().size());
    String html = TestWiring.invoiceRepository().findById(creditNote.id()).getRenderedHtml();
    assertTrue(html.contains("-19,00 €"), "credit note shows its VAT negative");
  }

  @Test
  void theCatalogStoresADefaultRate() {
    ArticleController articles = TestWiring.articleController();
    int food =
        articles
            .createArticle(
                new ArticleDto(
                    null, "Brot", "", "Stk", null, BigDecimal.ONE, new BigDecimal("7"), null))
            .getId();
    int odd =
        articles
            .createArticle(
                new ArticleDto(
                    null, "X", "", "Stk", null, BigDecimal.ONE, new BigDecimal("16"), null))
            .getId();

    assertEquals(0, new BigDecimal("7").compareTo(articles.getArticle(food).getBody().taxRate()));
    assertEquals(0, new BigDecimal("19").compareTo(articles.getArticle(odd).getBody().taxRate()));
  }

  // --- XRechnung -------------------------------------------------------------------------------

  @Test
  void theXRechnungOfAnIssuedInvoiceUsesThePartiesFrozenAtIssue() throws Exception {
    customers.updateCustomer(customerId, customer("", "04011000-12345-67"));
    int id =
        issue(
                create(
                        dto(
                            List.of(
                                item("Beratung", 1.5, 100, "Std", 19),
                                item("Buch", 2, 25, "Stk", 7))))
                    .id())
            .id();
    customers.updateCustomer(
        customerId,
        new CustomerDto(
            null,
            "Max",
            "Umbenannt",
            "99999",
            "Anderswo",
            "Gasse",
            2,
            "",
            "neu@kunde.de",
            "",
            "",
            ""));

    String xml = xrechnung(id);

    assertWellFormed(xml);
    assertTrue(xml.contains("<cbc:InvoiceTypeCode>380</cbc:InvoiceTypeCode>"));
    assertTrue(xml.contains("Max Müller &amp; Söhne"), "buyer as at issue");
    assertFalse(xml.contains("Umbenannt"));
    assertTrue(xml.contains("<cbc:BuyerReference>04011000-12345-67</cbc:BuyerReference>"));
    assertTrue(xml.contains("unitCode=\"HUR\">1.5</cbc:InvoicedQuantity>"));
    assertTrue(xml.contains("<cbc:TaxAmount currencyID=\"EUR\">32.00</cbc:TaxAmount>"));
    assertEquals(2, count(xml, "<cac:TaxSubtotal>"));
    assertTrue(xml.contains("<cbc:PayableAmount currencyID=\"EUR\">232.00</cbc:PayableAmount>"));
  }

  @Test
  void theXRechnungOfACreditNoteReferencesTheCancelledInvoice() throws Exception {
    InvoiceDto invoice = issue(create(dto(List.of(item("A", 1, 100, "Stk", 19)))).id());
    InvoiceDto creditNote = controller.cancelInvoice(invoice.id());

    String xml = xrechnung(creditNote.id());

    assertWellFormed(xml);
    assertTrue(xml.contains("<ubl:CreditNote"));
    assertTrue(xml.contains("<cbc:CreditNoteTypeCode>381</cbc:CreditNoteTypeCode>"));
    assertTrue(xml.contains("<cbc:ID>" + invoice.invoiceNumber() + "</cbc:ID>"));
    assertTrue(xml.contains("<cac:CreditNoteLine>"));
  }

  @Test
  void missingMasterDataIsReportedInsteadOfAnInvalidXRechnung() {
    saveOwner(false, "");
    int draft = create(dto(List.of(item("A", 1, 100, "Stk", 19)))).id();

    List<String> missing = controller.checkXRechnung(draft).get("missing");
    assertEquals(List.of("Telefon (Firmendaten)"), missing, "no number required for a draft");
    assertThrows(DocumentStateException.class, () -> controller.downloadXRechnung(draft));

    int issued = issue(draft).id();
    DocumentStateException e =
        assertThrows(DocumentStateException.class, () -> controller.downloadXRechnung(issued));
    assertTrue(e.getMessage().contains("Telefon"));
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "XRECHNUNG_VALIDATOR_DIR", matches = ".+")
  void generatedXRechnungsPassTheKositValidator(@TempDir Path dir) throws Exception {
    customers.updateCustomer(customerId, customer("DE123456789", "04011000-12345-67"));
    List<Path> files = new ArrayList<>();
    int mixed =
        issue(
                create(
                        dto(
                            List.of(
                                item("Beratung", 1.5, 100, "Std", 19),
                                item("Buch", 2, 25, "Stk", 7))))
                    .id())
            .id();
    files.add(write(dir, "mixed.xml", xrechnung(mixed)));
    int reverse =
        issue(
                create(
                        withTreatment(
                            dto(List.of(item("Estrich", 10, 30, "m²", 19))),
                            "REVERSE_CHARGE",
                            null))
                    .id())
            .id();
    files.add(write(dir, "reverse-charge.xml", xrechnung(reverse)));
    int exempt =
        issue(
                create(
                        withTreatment(
                            dto(List.of(item("Kurs", 1, 100, "Stk", 19))),
                            "TAX_EXEMPT",
                            "gemäß § 4 Nr. 21 UStG"))
                    .id())
            .id();
    files.add(write(dir, "tax-exempt.xml", xrechnung(exempt)));
    files.add(write(dir, "credit-note.xml", xrechnung(controller.cancelInvoice(mixed).id())));
    saveOwner(true, "030 123456");
    int small = issue(create(dto(List.of(item("Beratung", 1, 100, "Std", 19)))).id()).id();
    files.add(write(dir, "small-business.xml", xrechnung(small)));

    Path base = Path.of(System.getenv("XRECHNUNG_VALIDATOR_DIR"));
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.addAll(
        List.of(
            "-jar",
            base.resolve("validator/validationtool-1.5.0-standalone.jar").toString(),
            "-s",
            base.resolve("config/scenarios.xml").toString(),
            "-r",
            base.resolve("config").toString(),
            "-o",
            dir.toString()));
    files.forEach(f -> command.add(f.toString()));
    // Argument list without a shell; the paths come from the test's own temp dir and the CI-set
    // XRECHNUNG_VALIDATOR_DIR, never from user input.
    /* nosemgrep: java.lang.security.audit.command-injection-process-builder.command-injection-process-builder */
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertTrue(process.waitFor(5, TimeUnit.MINUTES), "validator timed out");

    assertTrue(
        output.contains("Acceptable:  " + files.size() + "  Rejected:  0"),
        "KoSIT validator rejected an XRechnung:\n" + output);
  }

  // --- helpers ---------------------------------------------------------------------------------

  static final String BLOCKS =
      "[{\"id\":\"a\",\"type\":\"address\",\"order\":1,\"data\":{\"role\":\"receiver\","
          + "\"name\":\"{{kunde}}\"},\"config\":{}},"
          + "{\"id\":\"m\",\"type\":\"invoice-meta\",\"order\":2,\"data\":{},\"config\":{}},"
          + "{\"id\":\"i\",\"type\":\"line-items\",\"order\":3,\"data\":{\"columns\":["
          + "{\"key\":\"description\",\"label\":\"Beschreibung\",\"show\":true},"
          + "{\"key\":\"quantity\",\"label\":\"Menge\",\"show\":true},"
          + "{\"key\":\"unit\",\"label\":\"Einheit\",\"show\":true},"
          + "{\"key\":\"rate\",\"label\":\"Preis\",\"show\":true},"
          + "{\"key\":\"amount\",\"label\":\"Gesamt\",\"show\":true}]},\"config\":{}},"
          + "{\"id\":\"t\",\"type\":\"totals\",\"order\":4,\"data\":{},\"config\":{}}]";

  private InvoiceDto create(InvoiceDto dto) {
    return controller.createInvoice(dto);
  }

  private InvoiceDto issue(int id) {
    return controller.updateInvoiceStatus(id, new StatusChangeRequest(SENT, null, null)).getBody();
  }

  private String xrechnung(int id) {
    return new String(controller.downloadXRechnung(id).getBody(), StandardCharsets.UTF_8);
  }

  static InvoiceItemDto item(
      String name, double quantity, double unitPrice, String unit, double rate) {
    return new InvoiceItemDto(
        null,
        null,
        "article",
        0,
        name,
        null,
        unit,
        bd(quantity),
        BigDecimal.valueOf(unitPrice),
        null,
        null,
        List.of(),
        BigDecimal.valueOf(rate));
  }

  private InvoiceDto dto(List<InvoiceItemDto> items) {
    return new InvoiceDto(
        null,
        customerId,
        null,
        null,
        null,
        LocalDate.now(),
        LocalDate.now().plusDays(14),
        null,
        BigDecimal.valueOf(19),
        null,
        null,
        null,
        null,
        INVOICE,
        items,
        false,
        LocalDate.now(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  static InvoiceDto withTreatment(InvoiceDto d, String treatment, String reason) {
    return new InvoiceDto(
        d.id(),
        d.customerId(),
        d.customerName(),
        d.invoiceNumber(),
        d.documentStatusId(),
        d.invoiceDate(),
        d.dueDate(),
        d.netAmount(),
        d.taxRate(),
        d.taxAmount(),
        d.grossAmount(),
        d.paidDate(),
        d.notes(),
        d.documentTypeId(),
        d.items(),
        d.archived(),
        d.workStartDate(),
        d.workEndDate(),
        d.templateId(),
        null,
        null,
        null,
        treatment,
        reason,
        null);
  }

  static CustomerDto customer(String taxId, String buyerReference) {
    return new CustomerDto(
        null,
        "Max",
        "Müller & Söhne",
        "12345",
        "Ort",
        "Weg",
        1,
        "",
        "max@kunde.de",
        taxId,
        "",
        buyerReference);
  }

  static void saveOwner(boolean smallBusiness, String phone) {
    OwnerRepository owners = TestWiring.ownerRepository();
    owners.findAll().forEach(o -> owners.delete(o.getId()));
    owners.save(
        new Owner(
            0,
            "Erika",
            "Muster",
            "Muster & Partner GmbH",
            "GmbH",
            "12345",
            "Ort",
            "Weg",
            1,
            phone,
            "info@muster.de",
            "",
            "DE987654321",
            "DE02120300000000202051",
            smallBusiness));
  }

  private static void assertWellFormed(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  private static int count(String text, String needle) {
    int count = 0;
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
      count++;
    }
    return count;
  }

  private static Path write(Path dir, String name, String xml) throws IOException {
    return Files.writeString(dir.resolve(name), xml, StandardCharsets.UTF_8);
  }
}
