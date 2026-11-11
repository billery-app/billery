package de.billery.api.controller;

import static de.billery.api.controller.VatAndEInvoiceTest.BLOCKS;
import static de.billery.api.controller.VatAndEInvoiceTest.customer;
import static de.billery.api.controller.VatAndEInvoiceTest.item;
import static de.billery.api.controller.VatAndEInvoiceTest.saveOwner;
import static de.billery.api.controller.VatAndEInvoiceTest.withTreatment;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.InvoiceTemplate;
import de.billery.data.TestDatabaseConfig;
import de.billery.pdf.FacturXEmbedder;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentCatalog;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * The PDF of an issued invoice is a ZUGFeRD invoice, and what its XML says is what the page shows.
 *
 * <p>For every tax case (19 % and 7 % mixed, reverse charge, tax-exempt, Kleinunternehmer,
 * Stornorechnung) the PDF is downloaded like the app does it, the attached {@code xrechnung.xml} is
 * taken out of it and compared
 *
 * <ul>
 *   <li>field by field with the separately downloaded XRechnung (UBL): number, dates, type,
 *       parties, every total, the VAT breakdown and every line ({@link #FIELDS}), and
 *   <li>with the text on the page: number, buyer, net, gross, every VAT amount and every line
 *       total, formatted as the PDF prints them.
 * </ul>
 *
 * With {@code XRECHNUNG_VALIDATOR_DIR} the attached XMLs also go through the KoSIT validator, with
 * {@code PDFA_SAMPLES_DIR} the PDFs are written there for veraPDF (both in the {@code xrechnung} CI
 * job).
 */
class ZugferdTest {

  private static final int INVOICE = 1;
  private static final int OFFER = 2;
  private static final int SENT = 2;

  /**
   * One business term of EN 16931 and where it is in each syntax. Values are compared as lists in
   * document order; dates are written without dashes on both sides.
   */
  private record Field(String term, String ubl, String cii) {}

  private static final List<Field> FIELDS =
      List.of(
          new Field(
              "BT-1 number",
              "/*/*[local-name()='ID']",
              "//*[local-name()='ExchangedDocument']/*[local-name()='ID']"),
          new Field(
              "BT-2 issue date",
              "/*/*[local-name()='IssueDate']",
              "//*[local-name()='ExchangedDocument']/*[local-name()='IssueDateTime']/*"),
          new Field(
              "BT-3 type",
              "/*/*[local-name()='InvoiceTypeCode' or local-name()='CreditNoteTypeCode']",
              "//*[local-name()='ExchangedDocument']/*[local-name()='TypeCode']"),
          new Field(
              "BT-9 due date",
              "/*/*[local-name()='DueDate']",
              "//*[local-name()='DueDateDateTime']/*"),
          new Field(
              "BT-10 buyer reference",
              "/*/*[local-name()='BuyerReference']",
              "//*[local-name()='BuyerReference']"),
          new Field(
              "BT-24 specification",
              "/*/*[local-name()='CustomizationID']",
              "//*[local-name()='GuidelineSpecifiedDocumentContextParameter']/*[local-name()='ID']"),
          new Field(
              "BT-25 preceding invoice",
              "//*[local-name()='InvoiceDocumentReference']/*[local-name()='ID']",
              "//*[local-name()='InvoiceReferencedDocument']/*[local-name()='IssuerAssignedID']"),
          new Field(
              "BT-26 preceding invoice date",
              "//*[local-name()='InvoiceDocumentReference']/*[local-name()='IssueDate']",
              "//*[local-name()='InvoiceReferencedDocument']/*[local-name()='FormattedIssueDateTime']/*"),
          new Field(
              "BT-27 seller",
              "//*[local-name()='AccountingSupplierParty']//*[local-name()='RegistrationName']",
              "//*[local-name()='SellerTradeParty']/*[local-name()='Name']"),
          new Field(
              "BT-31/32 seller tax id",
              "//*[local-name()='AccountingSupplierParty']//*[local-name()='PartyTaxScheme']/*[local-name()='CompanyID']",
              "//*[local-name()='SellerTradeParty']/*[local-name()='SpecifiedTaxRegistration']/*[local-name()='ID']"),
          new Field(
              "BT-34 seller address",
              "//*[local-name()='AccountingSupplierParty']//*[local-name()='EndpointID']",
              "//*[local-name()='SellerTradeParty']/*[local-name()='URIUniversalCommunication']/*[local-name()='URIID']"),
          new Field(
              "BT-44 buyer",
              "//*[local-name()='AccountingCustomerParty']//*[local-name()='RegistrationName']",
              "//*[local-name()='BuyerTradeParty']/*[local-name()='Name']"),
          new Field(
              "BT-48 buyer VAT id",
              "//*[local-name()='AccountingCustomerParty']//*[local-name()='PartyTaxScheme']/*[local-name()='CompanyID']",
              "//*[local-name()='BuyerTradeParty']/*[local-name()='SpecifiedTaxRegistration']/*[local-name()='ID']"),
          new Field(
              "BT-49 buyer address",
              "//*[local-name()='AccountingCustomerParty']//*[local-name()='EndpointID']",
              "//*[local-name()='BuyerTradeParty']/*[local-name()='URIUniversalCommunication']/*[local-name()='URIID']"),
          new Field(
              "BT-72 delivery date",
              "//*[local-name()='ActualDeliveryDate']",
              "//*[local-name()='ActualDeliverySupplyChainEvent']//*[local-name()='DateTimeString']"),
          new Field(
              "BT-81 payment means",
              "//*[local-name()='PaymentMeansCode']",
              "//*[local-name()='SpecifiedTradeSettlementPaymentMeans']/*[local-name()='TypeCode']"),
          new Field(
              "BT-84 IBAN",
              "//*[local-name()='PayeeFinancialAccount']/*[local-name()='ID']",
              "//*[local-name()='IBANID']"),
          new Field(
              "BT-106 sum of lines",
              "//*[local-name()='LegalMonetaryTotal']/*[local-name()='LineExtensionAmount']",
              "//*[local-name()='SpecifiedTradeSettlementHeaderMonetarySummation']/*[local-name()='LineTotalAmount']"),
          new Field(
              "BT-109 net",
              "//*[local-name()='TaxExclusiveAmount']",
              "//*[local-name()='TaxBasisTotalAmount']"),
          new Field(
              "BT-110 VAT",
              "/*/*[local-name()='TaxTotal']/*[local-name()='TaxAmount']",
              "//*[local-name()='TaxTotalAmount']"),
          new Field(
              "BT-112 gross",
              "//*[local-name()='TaxInclusiveAmount']",
              "//*[local-name()='GrandTotalAmount']"),
          new Field(
              "BT-115 payable",
              "//*[local-name()='PayableAmount']",
              "//*[local-name()='DuePayableAmount']"),
          new Field(
              "BT-116 VAT basis per rate",
              "//*[local-name()='TaxSubtotal']/*[local-name()='TaxableAmount']",
              "//*[local-name()='ApplicableHeaderTradeSettlement']/*[local-name()='ApplicableTradeTax']/*[local-name()='BasisAmount']"),
          new Field(
              "BT-117 VAT per rate",
              "//*[local-name()='TaxSubtotal']/*[local-name()='TaxAmount']",
              "//*[local-name()='ApplicableHeaderTradeSettlement']/*[local-name()='ApplicableTradeTax']/*[local-name()='CalculatedAmount']"),
          new Field(
              "BT-118 VAT category",
              "//*[local-name()='TaxSubtotal']/*[local-name()='TaxCategory']/*[local-name()='ID']",
              "//*[local-name()='ApplicableHeaderTradeSettlement']/*[local-name()='ApplicableTradeTax']/*[local-name()='CategoryCode']"),
          new Field(
              "BT-119 VAT rate",
              "//*[local-name()='TaxSubtotal']/*[local-name()='TaxCategory']/*[local-name()='Percent']",
              "//*[local-name()='ApplicableHeaderTradeSettlement']/*[local-name()='ApplicableTradeTax']/*[local-name()='RateApplicablePercent']"),
          new Field(
              "BT-120 exemption reason",
              "//*[local-name()='TaxSubtotal']//*[local-name()='TaxExemptionReason']",
              "//*[local-name()='ApplicableHeaderTradeSettlement']/*[local-name()='ApplicableTradeTax']/*[local-name()='ExemptionReason']"),
          new Field(
              "BT-126 line id",
              "//*[local-name()='InvoiceLine' or local-name()='CreditNoteLine']/*[local-name()='ID']",
              "//*[local-name()='AssociatedDocumentLineDocument']/*[local-name()='LineID']"),
          new Field(
              "BT-127 line note",
              "//*[local-name()='InvoiceLine' or local-name()='CreditNoteLine']/*[local-name()='Note']",
              "//*[local-name()='AssociatedDocumentLineDocument']//*[local-name()='Content']"),
          new Field(
              "BT-129 quantity",
              "//*[local-name()='InvoicedQuantity' or local-name()='CreditedQuantity']",
              "//*[local-name()='BilledQuantity']"),
          new Field(
              "BT-130 unit",
              "//*[local-name()='InvoicedQuantity' or local-name()='CreditedQuantity']/@unitCode",
              "//*[local-name()='BilledQuantity']/@unitCode"),
          new Field(
              "BT-131 line total",
              "//*[local-name()='InvoiceLine' or local-name()='CreditNoteLine']/*[local-name()='LineExtensionAmount']",
              "//*[local-name()='LineTotalAmount' and ancestor::*[local-name()='IncludedSupplyChainTradeLineItem']]"),
          new Field(
              "BT-146 unit price",
              "//*[local-name()='Price']/*[local-name()='PriceAmount']",
              "//*[local-name()='NetPriceProductTradePrice']/*[local-name()='ChargeAmount']"),
          new Field(
              "BT-151 line VAT category",
              "//*[local-name()='ClassifiedTaxCategory']/*[local-name()='ID']",
              "//*[local-name()='SpecifiedLineTradeSettlement']/*[local-name()='ApplicableTradeTax']/*[local-name()='CategoryCode']"),
          new Field(
              "BT-152 line VAT rate",
              "//*[local-name()='ClassifiedTaxCategory']/*[local-name()='Percent']",
              "//*[local-name()='SpecifiedLineTradeSettlement']/*[local-name()='ApplicableTradeTax']/*[local-name()='RateApplicablePercent']"),
          new Field(
              "BT-153 item name",
              "//*[local-name()='Item']/*[local-name()='Name']",
              "//*[local-name()='SpecifiedTradeProduct']/*[local-name()='Name']"));

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
    customerId = customers.createCustomer(customer("DE123456789", "04011000-12345-67")).getId();
    saveOwner(false, "030 123456");
  }

  @Test
  void everyIssuedInvoiceIsAZugferdPdfWhoseXmlMatchesTheXRechnungAndThePage() throws Exception {
    Map<String, Integer> documents = issueEveryTaxCase();
    java.util.Set<String> covered = new java.util.HashSet<>();

    for (Map.Entry<String, Integer> document : documents.entrySet()) {
      String name = document.getKey();
      byte[] pdf = pdf(document.getValue());
      String cii = attachedXml(pdf).orElseThrow(() -> new AssertionError(name + ": no XML"));
      String ubl = xrechnung(document.getValue());

      Document ciiXml = parse(cii);
      Document ublXml = parse(ubl);
      for (Field field : FIELDS) {
        assertEquals(
            values(ublXml, field.ubl()),
            values(ciiXml, field.cii()),
            name + ": " + field.term() + " differs between the attached XML and the XRechnung");
        if (!values(ciiXml, field.cii()).isEmpty()) {
          covered.add(field.term());
        }
      }
      assertPageShows(name, pdf, ciiXml);
    }
    // An expression that finds nothing on either side compares equal: every term must be seen.
    for (Field field : FIELDS) {
      assertTrue(covered.contains(field.term()), field.term() + " was never present in any case");
    }
  }

  @Test
  void theAttachmentIsDeclaredAsZugferdXRechnungInAPdfA3() throws Exception {
    int id = issue(create(dto(List.of(item("Beratung", 1, 100, "Std", 19)))).id()).id();

    try (PDDocument document = Loader.loadPDF(pdf(id))) {
      PDDocumentCatalog catalog = document.getDocumentCatalog();
      PDComplexFileSpecification spec =
          catalog.getNames().getEmbeddedFiles().getNames().get(FacturXEmbedder.FILE_NAME);
      assertNotNull(spec, "attachment named " + FacturXEmbedder.FILE_NAME);
      assertEquals(FacturXEmbedder.FILE_NAME, spec.getFileUnicode());
      assertEquals(
          "Alternative", spec.getCOSObject().getNameAsString(COSName.getPDFName("AFRelationship")));
      assertEquals("text/xml", spec.getEmbeddedFile().getSubtype());
      COSArray associated =
          (COSArray) catalog.getCOSObject().getDictionaryObject(COSName.getPDFName("AF"));
      assertEquals(1, associated.size(), "the XML is the document's associated file");
      String xmp = new String(catalog.getMetadata().toByteArray(), StandardCharsets.UTF_8);
      assertTrue(xmp.contains("<pdfaid:part>3</pdfaid:part>"), xmp);
      assertTrue(xmp.contains("<pdfaid:conformance>B</pdfaid:conformance>"), xmp);
      assertTrue(xmp.contains("<fx:ConformanceLevel>XRECHNUNG</fx:ConformanceLevel>"), xmp);
      assertTrue(xmp.contains("<fx:DocumentFileName>xrechnung.xml</fx:DocumentFileName>"), xmp);
    }
  }

  @Test
  void theSameInvoiceGivesTheSameXmlOnEveryDownload() throws Exception {
    int id = issue(create(dto(List.of(item("Beratung", 1, 100, "Std", 19)))).id()).id();

    assertEquals(attachedXml(pdf(id)), attachedXml(pdf(id)));
  }

  @Test
  void withoutCompleteDataThePdfHasNoAttachmentButIsStillAPdfA() throws Exception {
    customers.updateCustomer(customerId, customerWithoutEmail());
    int id = issue(create(dto(List.of(item("Beratung", 1, 100, "Std", 19)))).id()).id();

    byte[] pdf = pdf(id);
    assertTrue(attachedXml(pdf).isEmpty(), "no e-invoice without the buyer's e-mail (BT-49)");
    assertTrue(xmp(pdf).contains("<pdfaid:part>3</pdfaid:part>"));
    assertTrue(controller.checkXRechnung(id).toString().contains("E-Mail des Kunden"));
  }

  /**
   * Text copied from other programs can contain control characters that XML 1.0 forbids. They must
   * neither break the PDF (its HTML is parsed as XHTML) nor make the attached XML or the XRechnung
   * invalid.
   */
  @Test
  void controlCharactersInTheDataBreakNeitherThePdfNorTheXml() throws Exception {
    int id =
        issue(
                create(dto(List.of(item("Kern\u0001bohrung\u0008 \u001Fneu", 1, 100, "Std", 19))))
                    .id())
            .id();

    byte[] pdf = pdf(id);
    String cii = attachedXml(pdf).orElseThrow();
    String ubl = xrechnung(id);
    parse(cii);
    parse(ubl);
    assertTrue(cii.contains("Kernbohrung neu"), cii);
    assertTrue(ubl.contains("Kernbohrung neu"), ubl);
    try (PDDocument document = Loader.loadPDF(pdf)) {
      assertTrue(new PDFTextStripper().getText(document).contains("Kernbohrung neu"));
    }
  }

  @Test
  void draftsAndOffersAreNoEInvoices() throws Exception {
    int draft = create(dto(List.of(item("Beratung", 1, 100, "Std", 19)))).id();
    int offer = create(withType(dto(List.of(item("Beratung", 1, 100, "Std", 19))), OFFER)).id();

    assertTrue(attachedXml(pdf(draft)).isEmpty(), "a draft is no invoice yet");
    assertTrue(attachedXml(pdf(offer)).isEmpty(), "an offer is no invoice");
    assertTrue(xmp(pdf(offer)).contains("<pdfaid:part>3</pdfaid:part>"), "still a PDF/A");
  }

  /**
   * Writes the PDFs of every case (with and without attachment, an offer) for veraPDF; the {@code
   * xrechnung} CI job validates them as PDF/A-3b.
   */
  @Test
  @EnabledIfEnvironmentVariable(named = "PDFA_SAMPLES_DIR", matches = ".+")
  void writesPdfSamplesForVeraPdf() throws Exception {
    Path dir = Files.createDirectories(Path.of(System.getenv("PDFA_SAMPLES_DIR")));
    for (Map.Entry<String, Integer> document : issueEveryTaxCase().entrySet()) {
      Files.write(dir.resolve(document.getKey() + ".pdf"), pdf(document.getValue()));
    }
    int offer = create(withType(dto(List.of(item("Beratung", 1, 100, "Std", 19))), OFFER)).id();
    Files.write(dir.resolve("offer.pdf"), pdf(offer));
  }

  /** The attached XMLs pass the KoSIT validator (scenario EN16931 XRechnung (CII)). */
  @Test
  @EnabledIfEnvironmentVariable(named = "XRECHNUNG_VALIDATOR_DIR", matches = ".+")
  void theAttachedXmlsPassTheKositValidator(@TempDir Path dir) throws Exception {
    List<Path> files = new ArrayList<>();
    for (Map.Entry<String, Integer> document : issueEveryTaxCase().entrySet()) {
      files.add(
          Files.writeString(
              dir.resolve(document.getKey() + "-cii.xml"),
              attachedXml(pdf(document.getValue())).orElseThrow(),
              StandardCharsets.UTF_8));
    }

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
        "KoSIT validator rejected an attached XML:\n" + output);
  }

  // --- the cases ---------------------------------------------------------------------------------

  /** Issues one document per tax case; the Kleinunternehmer case changes the company data last. */
  private Map<String, Integer> issueEveryTaxCase() {
    Map<String, Integer> documents = new LinkedHashMap<>();
    int mixed =
        issue(
                create(
                        dto(
                            List.of(
                                withNote(item("Beratung", 1.5, 100, "Std", 19)),
                                item("Fachbuch Ä-Ü", 2, 25, "Stk", 7),
                                item("Fahrtkosten", 3, 0.3, "km", 19))))
                    .id())
            .id();
    documents.put("mixed-rates", mixed);
    documents.put(
        "reverse-charge",
        issue(
                create(
                        withTreatment(
                            dto(List.of(item("Estrich", 10, 30, "m²", 19))),
                            "REVERSE_CHARGE",
                            null))
                    .id())
            .id());
    documents.put(
        "tax-exempt",
        issue(
                create(
                        withTreatment(
                            dto(List.of(item("Kurs", 1, 100, "Stk", 19))),
                            "TAX_EXEMPT",
                            "gemäß § 4 Nr. 21 UStG"))
                    .id())
            .id());
    documents.put("credit-note", controller.cancelInvoice(mixed).id());
    saveOwner(true, "030 123456");
    documents.put(
        "small-business",
        issue(create(dto(List.of(item("Beratung", 1, 100, "Std", 19)))).id()).id());
    return documents;
  }

  // --- what the page shows -----------------------------------------------------------------------

  /**
   * The page shows what the XML says: number, buyer, net, gross, the VAT of every rate and every
   * line total, in the German format the PDF uses ({@code 1.234,56}).
   */
  private static void assertPageShows(String name, byte[] pdf, Document cii) throws Exception {
    String page;
    try (PDDocument document = Loader.loadPDF(pdf)) {
      page = new PDFTextStripper().getText(document);
    }
    List<String> expected = new ArrayList<>();
    expected.addAll(values(cii, FIELDS.get(0).cii()));
    expected.add(values(cii, "//*[local-name()='BuyerTradeParty']/*[local-name()='Name']").get(0));
    for (String term : List.of("BT-109 net", "BT-112 gross", "BT-131 line total")) {
      for (String amount : values(cii, field(term).cii())) {
        expected.add(german(amount));
      }
    }
    for (String vat : values(cii, field("BT-117 VAT per rate").cii())) {
      if (new BigDecimal(vat).signum() != 0) {
        expected.add(german(vat));
      }
    }
    for (String text : expected) {
      assertTrue(page.contains(text), name + ": the page does not show " + text + "\n" + page);
    }
  }

  private static Field field(String term) {
    return FIELDS.stream().filter(f -> f.term().equals(term)).findFirst().orElseThrow();
  }

  private static String german(String amount) {
    NumberFormat format = NumberFormat.getNumberInstance(Locale.GERMANY);
    format.setMinimumFractionDigits(2);
    format.setMaximumFractionDigits(2);
    return format.format(new BigDecimal(amount).abs());
  }

  // --- reading the PDF and the XML ---------------------------------------------------------------

  private static java.util.Optional<String> attachedXml(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      var names = document.getDocumentCatalog().getNames();
      if (names == null || names.getEmbeddedFiles() == null) {
        return java.util.Optional.empty();
      }
      PDComplexFileSpecification spec =
          names.getEmbeddedFiles().getNames().get(FacturXEmbedder.FILE_NAME);
      return java.util.Optional.of(
          new String(spec.getEmbeddedFile().toByteArray(), StandardCharsets.UTF_8));
    }
  }

  private static String xmp(byte[] pdf) throws IOException {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      return new String(
          document.getDocumentCatalog().getMetadata().toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static Document parse(String xml) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory
        .newDocumentBuilder()
        .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
  }

  /** The text of every node the expression selects, in document order; dates without dashes. */
  private static List<String> values(Document xml, String expression) throws Exception {
    NodeList nodes =
        (NodeList)
            XPathFactory.newInstance().newXPath().evaluate(expression, xml, XPathConstants.NODESET);
    List<String> values = new ArrayList<>();
    for (int i = 0; i < nodes.getLength(); i++) {
      String text = nodes.item(i).getTextContent().trim();
      values.add(text.matches("\\d{4}-\\d{2}-\\d{2}") ? text.replace("-", "") : text);
    }
    return values;
  }

  // --- documents ---------------------------------------------------------------------------------

  private byte[] pdf(int id) throws IOException {
    return controller.downloadInvoicePdf(id).getBody().getContentAsByteArray();
  }

  private String xrechnung(int id) {
    return new String(controller.downloadXRechnung(id).getBody(), StandardCharsets.UTF_8);
  }

  private InvoiceDto create(InvoiceDto dto) {
    return controller.createInvoice(dto);
  }

  private InvoiceDto issue(int id) {
    return controller.updateInvoiceStatus(id, new StatusChangeRequest(SENT, null, null)).getBody();
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
        "Danke für den Auftrag.",
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

  /** A line with a free-text note as sub-item: BT-127 in both syntaxes. */
  private static InvoiceItemDto withNote(InvoiceItemDto line) {
    InvoiceItemDto note =
        new InvoiceItemDto(
            null,
            null,
            "custom",
            0,
            "Hinweis",
            "Material wird gestellt",
            "",
            BigDecimal.ONE,
            BigDecimal.ZERO,
            null,
            null,
            List.of(),
            BigDecimal.valueOf(19));
    return new InvoiceItemDto(
        line.id(),
        line.invoiceId(),
        line.itemType(),
        line.itemId(),
        line.itemName(),
        line.itemDescription(),
        line.unit(),
        line.quantity(),
        line.unitPrice(),
        line.totalPrice(),
        line.parentItemId(),
        List.of(note),
        line.taxRate());
  }

  private static InvoiceDto withType(InvoiceDto d, int type) {
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
        type,
        d.items(),
        d.archived(),
        d.workStartDate(),
        d.workEndDate(),
        d.templateId(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static de.billery.api.dto.CustomerDto customerWithoutEmail() {
    de.billery.api.dto.CustomerDto c = customer("DE123456789", "");
    return new de.billery.api.dto.CustomerDto(
        c.id(),
        c.firstName(),
        c.lastName(),
        c.postalCode(),
        c.city(),
        c.street(),
        c.houseNumber(),
        c.phone(),
        "",
        c.taxId(),
        c.note(),
        c.buyerReference());
  }
}
