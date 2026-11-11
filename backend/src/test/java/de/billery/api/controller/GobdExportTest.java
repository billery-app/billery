package de.billery.api.controller;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.ApiExceptionHandler;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.PaymentRequest;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.service.ExportBusyException;
import de.billery.service.ExportLimitException;
import de.billery.service.GobdExportService;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXParseException;

/** {@code GET /api/export/gobd}: content, period, and consistency of CSV files and index.xml. */
class GobdExportTest {

  private InvoiceController invoices;
  private ExportController export;
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
    InvoiceTemplate template = new InvoiceTemplate();
    template.setName("Default");
    template.setDocumentTypeId(1);
    template.setDefault(true);
    template.setBlocks(
        "[{\"id\":\"t\",\"type\":\"totals\",\"order\":0,\"data\":{},\"config\":{}}]");
    TestWiring.templateRepository().save(template);
    TestWiring.ownerRepository()
        .save(
            new Owner(
                0,
                "Erika",
                "Muster",
                "Muster GmbH",
                "GmbH",
                "12345",
                "Ort",
                "Weg",
                1,
                "030 1",
                "info@muster.de",
                "",
                "DE987654321",
                "DE02120300000000202051",
                false));
    customerId =
        TestWiring.customerController()
            .createCustomer(
                new CustomerDto(
                    null, "Max", "Kunde", "1", "Ort", "Weg", 1, "", "max@kunde.de", "", "", ""))
            .getId();
    invoices = TestWiring.invoiceController();
    export = TestWiring.exportController();
  }

  @Test
  void exportsIssuedDocumentsOfThePeriodWithLinesPaymentsAndHistory() throws Exception {
    int paid = issue(LocalDate.of(2026, 3, 1), "Beratung; \"Projekt\"", 2, 100);
    invoices.recordPayment(
        paid,
        new PaymentRequest(LocalDate.of(2026, 3, 10), new BigDecimal("238.00"), "Bar", "", ""));
    int cancelled = issue(LocalDate.of(2026, 4, 1), "Fehler", 1, 50);
    invoices.cancelInvoice(cancelled);
    issue(LocalDate.of(2025, 12, 31), "Vorjahr", 1, 10); // outside the period
    draft(LocalDate.of(2026, 5, 1)); // drafts are not records

    ResponseEntity<byte[]> response =
        export.gobdExport(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);

    assertEquals(HttpStatus.OK, response.getStatusCode());
    Map<String, byte[]> zip = unzip(response.getBody());
    List<String> belege = lines(zip.get("belege.csv"));
    assertEquals(4, belege.size(), "header + 2 invoices + 1 credit note");
    assertTrue(belege.get(1).contains("\"238,00\";\"238,00\""), "gross and paid");
    assertTrue(belege.stream().anyMatch(l -> l.startsWith("\"") && l.contains("Stornorechnung")));
    assertTrue(
        lines(zip.get("positionen.csv")).get(1).contains("\"Beratung; \"\"Projekt\"\"\""),
        "separators and quotes inside values are escaped");
    assertEquals(2, lines(zip.get("zahlungen.csv")).size());
    assertTrue(lines(zip.get("historie.csv")).size() > 4);
    assertEquals(2, lines(zip.get("kunden.csv")).size());
    long pdfs = zip.keySet().stream().filter(n -> n.endsWith(".pdf")).count();
    long xmls = zip.keySet().stream().filter(n -> n.endsWith("_xrechnung.xml")).count();
    assertEquals(3, pdfs);
    assertEquals(3, xmls);
  }

  @Test
  void everyCsvHeaderMatchesTheColumnsDeclaredInIndexXml() throws Exception {
    issue(LocalDate.now(), "A", 1, 1);
    Map<String, byte[]> zip =
        unzip(export.gobdExport(LocalDate.now().minusDays(1), LocalDate.now(), false).getBody());

    DocumentBuilderFactory factory = withoutExternalEntities();
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    Document index =
        factory.newDocumentBuilder().parse(new ByteArrayInputStream(zip.get("index.xml")));
    NodeList tables = index.getElementsByTagName("Table");
    assertEquals(5, tables.getLength());
    for (int i = 0; i < tables.getLength(); i++) {
      Element table = (Element) tables.item(i);
      String file = table.getElementsByTagName("URL").item(0).getTextContent();
      List<String> declared = new ArrayList<>();
      NodeList names = table.getElementsByTagName("Name");
      for (int n = 1; n < names.getLength(); n++) { // item 0 is the table name
        declared.add(names.item(n).getTextContent());
      }
      String header = lines(zip.get(file)).get(0);
      assertEquals(
          String.join(";", declared.stream().map(c -> "\"" + c + "\"").toList()), header, file);
    }
    assertFalse(zip.keySet().stream().anyMatch(n -> n.startsWith("dokumente/")));
  }

  /**
   * Validates {@code index.xml} against the official GDPdU DTD (test resource {@code
   * gdpdu/gdpdu-01-09-2004.dtd}, provenance in {@code gdpdu/README.md}), so audit software such as
   * IDEA can read the description. The parser loads nothing external: after checking that the
   * DOCTYPE references the DTD, the test replaces it with the bundled DTD as internal subset.
   */
  @Test
  void indexXmlIsValidAgainstTheGdpduDtd() throws Exception {
    issue(LocalDate.now(), "A", 1, 1);
    String index =
        new String(
            unzip(export.gobdExport(LocalDate.now().minusDays(1), LocalDate.now(), false).getBody())
                .get("index.xml"),
            StandardCharsets.UTF_8);
    String doctype = "<!DOCTYPE DataSet SYSTEM \"gdpdu-01-09-2004.dtd\">";
    assertTrue(index.contains(doctype), "index.xml must reference the GDPdU DTD");
    String dtd;
    try (var in = GobdExportTest.class.getResourceAsStream("/gdpdu/gdpdu-01-09-2004.dtd")) {
      // Drop the DTD's own text declaration; the rest is valid as an internal subset.
      dtd =
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
              .replaceFirst("^<\\?xml[^>]*\\?>", "");
    }
    String inlined = index.replace(doctype, "<!DOCTYPE DataSet [" + dtd + "]>");

    DocumentBuilderFactory factory = withoutExternalEntities();
    factory.setValidating(true);
    DocumentBuilder builder = factory.newDocumentBuilder();
    List<String> problems = new ArrayList<>();
    builder.setErrorHandler(
        new ErrorHandler() {
          @Override
          public void warning(SAXParseException e) {
            problems.add("warning line " + e.getLineNumber() + ": " + e.getMessage());
          }

          @Override
          public void error(SAXParseException e) {
            problems.add("error line " + e.getLineNumber() + ": " + e.getMessage());
          }

          @Override
          public void fatalError(SAXParseException e) throws SAXParseException {
            throw e;
          }
        });

    builder.parse(new InputSource(new StringReader(inlined)));

    assertEquals(List.of(), problems);
  }

  @Test
  void anInvertedPeriodIsRejected() {
    assertEquals(
        HttpStatus.BAD_REQUEST,
        export
            .gobdExport(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), false)
            .getStatusCode());
  }

  private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
  private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);

  /** Limits as in application.properties, with {@code overrides} applied by the caller. */
  private static GobdExportService serviceWithLimits(
      int maxDocuments, int maxWithFiles, long maxBytes, int maxDays, int maxConcurrent) {
    GobdExportService service = TestWiring.gobdExportService();
    service.configureLimits(maxDocuments, maxWithFiles, maxBytes, maxDays, maxConcurrent);
    return service;
  }

  @Test
  void aPeriodLongerThanTheLimitIsRejected() {
    GobdExportService service = serviceWithLimits(10_000, 2_000, 134_217_728L, 30, 1);

    ExportLimitException e =
        assertThrows(
            ExportLimitException.class,
            () -> service.export(JAN_1, LocalDate.of(2026, 3, 1), false));

    assertTrue(e.getMessage().contains("30 Tage"));
    assertDoesNotThrow(() -> service.export(JAN_1, LocalDate.of(2026, 1, 31), false));
  }

  @Test
  void tooManyDocumentsAreRejected() {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    issue(LocalDate.of(2026, 3, 2), "B", 1, 1);
    GobdExportService service = serviceWithLimits(1, 1, 134_217_728L, 3_660, 1);

    assertThrows(ExportLimitException.class, () -> service.export(JAN_1, DEC_31, false));
  }

  @Test
  void theLimitForExportsWithFilesIsLowerThanWithoutThem() {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    issue(LocalDate.of(2026, 3, 2), "B", 1, 1);
    GobdExportService service = serviceWithLimits(10, 1, 134_217_728L, 3_660, 1);

    assertThrows(ExportLimitException.class, () -> service.export(JAN_1, DEC_31, true));
    assertDoesNotThrow(() -> service.export(JAN_1, DEC_31, false));
  }

  @Test
  void anExportThatOutgrowsTheSizeLimitIsAborted() {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    GobdExportService service = serviceWithLimits(10, 10, 100L, 3_660, 1);

    assertThrows(ExportLimitException.class, () -> service.export(JAN_1, DEC_31, false));
  }

  @Test
  void exportsAreRefusedWhileAllSlotsAreTaken() {
    GobdExportService service = serviceWithLimits(10, 10, 134_217_728L, 3_660, 0);

    assertThrows(ExportBusyException.class, () -> service.export(JAN_1, DEC_31, false));
  }

  @Test
  void aFailedExportReleasesItsSlot() {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    GobdExportService tooSmall = serviceWithLimits(0, 0, 134_217_728L, 3_660, 1);

    assertThrows(ExportLimitException.class, () -> tooSmall.export(JAN_1, DEC_31, false));
    assertThrows(ExportLimitException.class, () -> tooSmall.export(JAN_1, DEC_31, false));

    tooSmall.configureLimits(10, 10, 134_217_728L, 3_660, 1);
    assertDoesNotThrow(() -> tooSmall.export(JAN_1, DEC_31, false));
  }

  @Test
  void limitFailuresMapToTheirHttpStatus() {
    ApiExceptionHandler handler = new ApiExceptionHandler();

    ResponseEntity<Map<String, String>> tooLarge =
        handler.handleExportLimit(new ExportLimitException("zu groß"));
    ResponseEntity<Map<String, String>> busy =
        handler.handleExportBusy(new ExportBusyException("belegt"));

    assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, tooLarge.getStatusCode());
    assertEquals("zu groß", tooLarge.getBody().get("message"));
    assertEquals(HttpStatus.TOO_MANY_REQUESTS, busy.getStatusCode());
    assertEquals("60", busy.getHeaders().getFirst("Retry-After"));
  }

  private int issue(LocalDate date, String name, double quantity, double price) {
    int id = draft(date, name, quantity, price);
    return invoices.updateInvoiceStatus(id, new StatusChangeRequest(2, null, null)).getBody().id();
  }

  private int draft(LocalDate date) {
    return draft(date, "Entwurf", 1, 1);
  }

  private int draft(LocalDate date, String name, double quantity, double price) {
    InvoiceItemDto item =
        new InvoiceItemDto(
            null,
            null,
            "article",
            0,
            name,
            null,
            "Std",
            bd(quantity),
            BigDecimal.valueOf(price),
            null,
            null,
            List.of(),
            new BigDecimal("19"));
    return invoices
        .createInvoice(
            new InvoiceDto(
                null,
                customerId,
                null,
                null,
                null,
                date,
                date.plusDays(14),
                null,
                BigDecimal.valueOf(19),
                null,
                null,
                null,
                null,
                1,
                List.of(item),
                false,
                date,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null))
        .id();
  }

  /** A parser factory that never resolves external entities (XXE-safe). */
  private static DocumentBuilderFactory withoutExternalEntities() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    return factory;
  }

  private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
      for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
        entries.put(e.getName(), in.readAllBytes());
      }
    }
    return entries;
  }

  private static List<String> lines(byte[] csv) {
    return List.of(new String(csv, StandardCharsets.UTF_8).split("\r\n"));
  }
}
