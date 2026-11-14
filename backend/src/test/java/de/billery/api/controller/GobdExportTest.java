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
import de.billery.data.Invoice;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.service.DocumentPdfService;
import de.billery.service.EInvoiceService;
import de.billery.service.ExportBusyException;
import de.billery.service.ExportLimitException;
import de.billery.service.GobdExportService;
import de.billery.tenant.DefaultCurrentTenant;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
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

    MockHttpServletResponse response =
        exportResponse(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), true);

    assertEquals(200, response.getStatus());
    assertEquals("application/zip", response.getContentType());
    assertTrue(response.getHeader("Content-Disposition").contains("GoBD-Export_2026-01-01"));
    Map<String, byte[]> zip = unzip(response.getContentAsByteArray());
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
        unzip(
            exportResponse(LocalDate.now().minusDays(1), LocalDate.now(), false)
                .getContentAsByteArray());

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
            unzip(
                    exportResponse(LocalDate.now().minusDays(1), LocalDate.now(), false)
                        .getContentAsByteArray())
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
  void anInvertedPeriodIsRejected() throws Exception {
    assertEquals(
        400, exportResponse(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1), false).getStatus());
  }

  private static final LocalDate JAN_1 = LocalDate.of(2026, 1, 1);
  private static final LocalDate DEC_31 = LocalDate.of(2026, 12, 31);

  /** Limits as in application.properties, with {@code overrides} applied by the caller. */
  private static final SecureRandom RANDOM = new SecureRandom();

  private static final long BIG = 2L * 1024 * 1024 * 1024;

  private static GobdExportService serviceWithLimits(
      int maxDocuments, int maxWithFiles, long maxBytes, int maxDays, int maxConcurrent) {
    GobdExportService service = TestWiring.gobdExportService();
    service.configureLimits(maxDocuments, maxWithFiles, maxBytes, maxDays, maxConcurrent, 30);
    return service;
  }

  /** Prepares, writes and closes one export, as the controller does. */
  private static byte[] run(GobdExportService service, LocalDate from, LocalDate to, boolean files)
      throws Exception {
    try (GobdExportService.PreparedExport prepared = service.prepare(from, to, files)) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      prepared.writeTo(out);
      return out.toByteArray();
    }
  }

  private MockHttpServletResponse exportResponse(LocalDate from, LocalDate to, boolean files)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    export.gobdExport(from, to, files, response);
    return response;
  }

  @Test
  void aPeriodLongerThanTheLimitIsRejected() {
    GobdExportService service = serviceWithLimits(10_000, 2_000, BIG, 30, 1);

    ExportLimitException e =
        assertThrows(
            ExportLimitException.class, () -> run(service, JAN_1, LocalDate.of(2026, 3, 1), false));

    assertTrue(e.getMessage().contains("30 Tage"));
    assertDoesNotThrow(() -> run(service, JAN_1, LocalDate.of(2026, 1, 31), false));
  }

  @Test
  void tooManyDocumentsAreRejected() {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    issue(LocalDate.of(2026, 3, 2), "B", 1, 1);
    GobdExportService service = serviceWithLimits(1, 1, BIG, 3_660, 1);

    assertThrows(ExportLimitException.class, () -> run(service, JAN_1, DEC_31, false));
  }

  @Test
  void theLimitForExportsWithFilesIsLowerThanWithoutThem() {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    issue(LocalDate.of(2026, 3, 2), "B", 1, 1);
    GobdExportService service = serviceWithLimits(10, 1, BIG, 3_660, 1);

    assertThrows(ExportLimitException.class, () -> run(service, JAN_1, DEC_31, true));
    assertDoesNotThrow(() -> run(service, JAN_1, DEC_31, false));
  }

  @Test
  void exportsAreRefusedWhileAllSlotsAreTaken() {
    GobdExportService service = serviceWithLimits(10, 10, BIG, 3_660, 0);

    assertThrows(ExportBusyException.class, () -> run(service, JAN_1, DEC_31, false));
  }

  @Test
  void aFailedExportReleasesItsSlot() {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    GobdExportService tooSmall = serviceWithLimits(0, 0, BIG, 3_660, 1);

    assertThrows(ExportLimitException.class, () -> run(tooSmall, JAN_1, DEC_31, false));
    assertThrows(ExportLimitException.class, () -> run(tooSmall, JAN_1, DEC_31, false));

    tooSmall.configureLimits(10, 10, BIG, 3_660, 1, 30);
    assertDoesNotThrow(() -> run(tooSmall, JAN_1, DEC_31, false));
  }

  /** A service whose PDFs and e-invoice checks are the given stand-ins. */
  private static GobdExportService serviceWith(DocumentPdfService pdfs, EInvoiceService eInvoices) {
    GobdExportService service =
        new GobdExportService(
            TestWiring.invoiceRepository(),
            TestWiring.paymentRepository(),
            TestWiring.historyRepository(),
            TestWiring.customerRepository(),
            eInvoices,
            pdfs,
            new DefaultCurrentTenant());
    service.configureLimits(10_000, 10_000, BIG, 3_660, 1, 30);
    return service;
  }

  @Test
  void theZipLeavesTheServerDocumentByDocumentInsteadOfBeingCollected() throws Exception {
    for (int i = 1; i <= 5; i++) {
      issue(LocalDate.of(2026, 3, i), "Beleg " + i, 1, 1);
    }
    int megabyte = 1024 * 1024;
    long[] sent = {0};
    List<Long> sentWhenRendering = new ArrayList<>();
    DocumentPdfService bigPdfs =
        new DocumentPdfService(TestWiring.pdfGenerator(), TestWiring.eInvoiceService()) {
          @Override
          public byte[] pdf(Invoice invoice) {
            sentWhenRendering.add(sent[0]);
            byte[] pdf = new byte[megabyte];
            RANDOM.nextBytes(pdf); // does not compress
            return pdf;
          }
        };
    OutputStream client =
        new OutputStream() {
          @Override
          public void write(int b) {
            sent[0]++;
          }

          @Override
          public void write(byte[] b, int off, int len) {
            sent[0] += len;
          }
        };

    try (GobdExportService.PreparedExport prepared =
        serviceWith(bigPdfs, TestWiring.eInvoiceService()).prepare(JAN_1, DEC_31, true)) {
      prepared.writeTo(client);
    }

    assertEquals(5, sentWhenRendering.size());
    for (int k = 1; k < 5; k++) {
      assertTrue(
          sentWhenRendering.get(k) >= (long) k * megabyte - 64 * 1024,
          "the PDFs before document " + k + " already went out: " + sentWhenRendering);
    }
    assertTrue(sent[0] >= 5L * megabyte);
  }

  @Test
  void aFailureOnTheWayLeavesAnArchiveThatCannotBeOpened() throws Exception {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    issue(LocalDate.of(2026, 3, 2), "B", 1, 1);
    int[] checks = {0};
    EInvoiceService failing =
        new EInvoiceService(
            TestWiring.invoiceRepository(),
            TestWiring.customerRepository(),
            TestWiring.ownerRepository()) {
          @Override
          public List<String> check(int id) {
            if (++checks[0] == 2) {
              throw new IllegalStateException("database gone");
            }
            return super.check(id);
          }
        };
    GobdExportService service = serviceWith(TestWiring.documentPdfService(), failing);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try (GobdExportService.PreparedExport prepared = service.prepare(JAN_1, DEC_31, true)) {
      assertThrows(IllegalStateException.class, () -> prepared.writeTo(out));
    }

    assertTrue(out.size() > 0, "the first document had already gone out");
    Path cutOff = Files.createTempFile("gobd", ".zip");
    try {
      Files.write(cutOff, out.toByteArray());
      assertThrows(ZipException.class, () -> new ZipFile(cutOff.toFile()).close());
    } finally {
      Files.delete(cutOff);
    }
    checks[0] = 10; // works again; the slot was released
    assertDoesNotThrow(() -> run(service, JAN_1, DEC_31, true));
  }

  @Test
  void anExportThatOutgrowsTheSizeLimitBreaksOffUnreadable() throws Exception {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    GobdExportService service = serviceWithLimits(10, 10, 100L, 3_660, 1);
    ByteArrayOutputStream out = new ByteArrayOutputStream();

    try (GobdExportService.PreparedExport prepared = service.prepare(JAN_1, DEC_31, false)) {
      ExportLimitException e =
          assertThrows(ExportLimitException.class, () -> prepared.writeTo(out));
      assertTrue(e.getMessage().contains("zu groß"), e.getMessage());
    }

    assertTrue(out.size() <= 100, "nothing beyond the limit went out");
    assertDoesNotThrow(() -> run(serviceWithLimits(10, 10, BIG, 3_660, 1), JAN_1, DEC_31, false));
  }

  @Test
  void anExportThatTakesTooLongBreaksOffAndFreesItsSlot() throws Exception {
    issue(LocalDate.of(2026, 3, 1), "A", 1, 1);
    GobdExportService service = TestWiring.gobdExportService();
    service.configureLimits(10, 10, BIG, 3_660, 1, 0); // no time at all

    try (GobdExportService.PreparedExport prepared = service.prepare(JAN_1, DEC_31, false)) {
      ExportLimitException e =
          assertThrows(
              ExportLimitException.class, () -> prepared.writeTo(new ByteArrayOutputStream()));
      assertTrue(e.getMessage().contains("zu lange"), e.getMessage());
    }
    service.configureLimits(10, 10, BIG, 3_660, 1, 30);
    assertDoesNotThrow(() -> run(service, JAN_1, DEC_31, false));
  }

  @Test
  void eachTenantHasOneExportAtATimeSoNoTenantTakesEverySlot() throws Exception {
    int[] tenant = {1};
    GobdExportService service =
        new GobdExportService(
            TestWiring.invoiceRepository(),
            TestWiring.paymentRepository(),
            TestWiring.historyRepository(),
            TestWiring.customerRepository(),
            TestWiring.eInvoiceService(),
            TestWiring.documentPdfService(),
            () -> tenant[0]);
    service.configureLimits(10, 10, BIG, 3_660, 2, 30);

    GobdExportService.PreparedExport first = service.prepare(JAN_1, DEC_31, false);
    assertThrows(
        ExportBusyException.class,
        () -> service.prepare(JAN_1, DEC_31, false),
        "the same tenant waits for its own export");
    tenant[0] = 2;
    try (var second = service.prepare(JAN_1, DEC_31, false)) {
      tenant[0] = 3;
      assertThrows(
          ExportBusyException.class,
          () -> service.prepare(JAN_1, DEC_31, false),
          "all slots of the instance are taken");
      first.close();
      assertDoesNotThrow(() -> service.prepare(JAN_1, DEC_31, false).close());
    }
  }

  @Test
  void theSlotIsHeldUntilTheExportIsClosed() throws Exception {
    GobdExportService service = serviceWithLimits(10, 10, BIG, 3_660, 1);
    GobdExportService.PreparedExport first = service.prepare(JAN_1, DEC_31, false);

    assertThrows(ExportBusyException.class, () -> service.prepare(JAN_1, DEC_31, false));
    first.writeTo(new ByteArrayOutputStream());
    assertThrows(ExportBusyException.class, () -> service.prepare(JAN_1, DEC_31, false));
    first.close();
    first.close(); // twice releases once
    assertDoesNotThrow(() -> run(service, JAN_1, DEC_31, false));
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
