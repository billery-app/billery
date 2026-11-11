package de.billery.api.controller;

import static de.billery.TestMoney.assertAmount;
import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.PaymentRequest;
import de.billery.api.dto.SearchResult;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.DatabaseException;
import de.billery.data.DocumentHistoryEntry;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.Payment;
import de.billery.data.TestDatabaseConfig;
import de.billery.pdf.MissingConfigurationException;
import de.billery.repository.InvoiceTemplateRepository;
import de.billery.repository.OwnerRepository;
import de.billery.service.DocumentNotFoundException;
import de.billery.service.DocumentStateException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * End-to-end document flows through {@link InvoiceController} (and thereby {@code DocumentService},
 * {@code InvoiceLifecycleService}, the repositories and the PDF renderer) against the H2 test
 * database: numbering on issue, totals, transactions, status, locking of issued invoices, payments,
 * Storno, archive, delete, PDF download, statistics and the Kleinunternehmer rule.
 */
class DocumentLifecycleTest {

  private static final int INVOICE = 1;
  private static final int OFFER = 2;
  private static final int DRAFT = 1;
  private static final int SENT = 2;
  private static final int PAID = 6;
  private static final int CANCELLED = 8;
  private static final int PARTIALLY_PAID = 9;
  private static final int CREDIT_NOTE = 6;
  private static final String YEAR = String.valueOf(LocalDate.now().getYear());

  private InvoiceController controller;
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
    // clearData() empties the template table; restore one default per type.
    for (int type = 1; type <= 4; type++) {
      InvoiceTemplate template = new InvoiceTemplate();
      template.setName("Default " + type);
      template.setDocumentTypeId(type);
      template.setDefault(true);
      template.setBlocks(DEFAULT_BLOCKS);
      TestWiring.templateRepository().save(template);
    }
    controller = TestWiring.invoiceController();
    customerId =
        TestWiring.customerController()
            .createCustomer(
                new CustomerDto(
                    null, "Max", "Müller & Söhne", "12345", "Ort", "Weg", 1, "", "", "", "", null))
            .getId();
  }

  @Test
  void invoicesAreNumberedWhenIssuedAndSequentialPerTypeAndYear() {
    saveOwner(false);
    InvoiceDto first = create(INVOICE, item(1, 10));
    InvoiceDto second = create(INVOICE, item(1, 10));
    assertEquals("", first.invoiceNumber().replace("ENTWURF", ""), "drafts have no number");

    assertEquals("RE-" + YEAR + "-001", issue(first.id()).invoiceNumber());
    assertEquals("RE-" + YEAR + "-002", issue(second.id()).invoiceNumber());
    assertEquals("AN-" + YEAR + "-001", create(OFFER, item(1, 10)).invoiceNumber());
    assertEquals("RE-" + YEAR + "-003", controller.getNextInvoiceNumber(INVOICE).getBody());
  }

  @Test
  void deletedDraftsLeaveNoGapInTheInvoiceNumbers() {
    saveOwner(false);
    controller.deleteInvoice(create(INVOICE, item(1, 10)).id());

    assertEquals("RE-" + YEAR + "-001", issue(create(INVOICE, item(1, 10)).id()).invoiceNumber());
  }

  @Test
  void theNumberFollowsTheYearOfTheInvoiceDate() {
    saveOwner(false);
    InvoiceDto backdated =
        create(withDates(dto(INVOICE, customerId, null, item(1, 10)), LocalDate.of(2025, 12, 30)));

    assertEquals("RE-2025-001", issue(backdated.id()).invoiceNumber());
  }

  @Test
  void issuingRequiresTheServiceDateAndConsumesNoNumberOnFailure() {
    saveOwner(false);
    InvoiceDto noServiceDate = create(withDates(dto(INVOICE, customerId, null, item(1, 10)), null));

    DocumentStateException e =
        assertThrows(DocumentStateException.class, () -> issue(noServiceDate.id()));

    assertTrue(e.getMessage().contains("Leistungsdatum"));
    assertEquals(DRAFT, controller.getInvoice(noServiceDate.id()).getBody().documentStatusId());
    assertEquals("RE-" + YEAR + "-001", controller.getNextInvoiceNumber(INVOICE).getBody());
  }

  @Test
  void issuingWithoutCompanyDataFailsAndRollsBackTheNumber() {
    int id = create(INVOICE, item(1, 10)).id();

    assertThrows(MissingConfigurationException.class, () -> issue(id));

    assertEquals(DRAFT, controller.getInvoice(id).getBody().documentStatusId());
    assertEquals("RE-" + YEAR + "-001", controller.getNextInvoiceNumber(INVOICE).getBody());
  }

  @Test
  void amountsAreComputedServerSideAndRoundedToCents() {
    InvoiceItemDto tampered =
        new InvoiceItemDto(
            null,
            null,
            "article",
            0,
            "Kabel",
            null,
            "Stk",
            bd(3.0),
            BigDecimal.valueOf(0.335),
            BigDecimal.valueOf(999),
            null,
            List.of(),
            null);

    InvoiceDto saved = create(INVOICE, tampered);

    // 0.335 is stored as 0.34 (DECIMAL(10,2)); the client's 999 is ignored.
    assertEquals(new BigDecimal("1.02"), saved.netAmount().setScale(2));
    assertEquals(new BigDecimal("0.19"), saved.taxAmount());
    assertEquals(new BigDecimal("1.21"), saved.grossAmount().setScale(2));
    assertEquals(1.02, saved.items().get(0).totalPrice().doubleValue(), 0.0);
    assertEquals(0.34, saved.items().get(0).unitPrice().doubleValue(), 0.0);
  }

  @Test
  void failedSaveRollsBackAndLeavesNoGapInTheNumbers() {
    InvoiceDto unknownCustomer = dto(OFFER, 999_999, null, item(1, 10));

    assertThrows(DatabaseException.class, () -> controller.createInvoice(unknownCustomer));

    assertTrue(listed(true, true).isEmpty(), "no half-saved document");
    assertEquals(
        "AN-" + YEAR + "-001",
        create(OFFER, item(1, 10)).invoiceNumber(),
        "the failed save must not consume a number");
  }

  @Test
  void statusTransitionsFollowTheDocumentTypeRules() {
    saveOwner(false);
    int id = create(INVOICE, item(1, 10)).id();

    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.updateInvoiceStatus(id, status(PAID)).getStatusCode(),
        "a draft cannot jump to paid");
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.updateInvoiceStatus(id, status(CANCELLED)).getStatusCode(),
        "a draft is deleted, not cancelled");
    assertEquals(HttpStatus.OK, controller.updateInvoiceStatus(id, status(SENT)).getStatusCode());
    assertEquals(SENT, controller.getInvoice(id).getBody().documentStatusId());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller
            .updateInvoiceStatus(id, new StatusChangeRequest(null, null, null))
            .getStatusCode(),
        "missing status");
    assertEquals(
        HttpStatus.NOT_FOUND, controller.updateInvoiceStatus(999, status(SENT)).getStatusCode());
  }

  @Test
  void issuedInvoicesCannotBeEditedDeletedOrReLaidOut() {
    saveOwner(false);
    int id = issue(create(INVOICE, item(1, 10)).id()).id();

    assertThrows(DocumentStateException.class, () -> controller.updateInvoice(id, notes("x")));
    assertThrows(DocumentStateException.class, () -> controller.deleteInvoice(id));
    assertThrows(
        DocumentStateException.class,
        () -> TestWiring.templateController().cloneForDocument(id, null));
    assertEquals(1, listed(true, true).size());
  }

  @Test
  void invoicesCannotBeVersionedOnlyCancelledAndCorrected() {
    saveOwner(false);
    InvoiceDto issued = issue(create(INVOICE, item(1, 10)).id());

    assertThrows(
        DocumentStateException.class,
        () ->
            controller.createInvoice(
                withNumber(
                    dto(INVOICE, customerId, null, item(1, 10)), issued.invoiceNumber() + "/")));
  }

  @Test
  void paymentsDeriveTheStatusAndAreReversedNotDeleted() {
    saveOwner(false);
    // net 100 + 19 % = 119.00 gross
    int id = issue(create(INVOICE, item(1, 100)).id()).id();
    LocalDate firstDay = LocalDate.now().minusDays(3);
    LocalDate secondDay = LocalDate.now().minusDays(1);

    Payment first = controller.recordPayment(id, payment(firstDay, "50.00"));
    InvoiceDto partly = controller.getInvoice(id).getBody();
    assertEquals(PARTIALLY_PAID, partly.documentStatusId());
    assertEquals(new BigDecimal("50.00"), partly.paidAmount());
    assertNull(partly.paidDate());

    assertThrows(
        DocumentStateException.class,
        () -> controller.recordPayment(id, payment(secondDay, "69.01")),
        "more than the open amount");
    assertThrows(
        DocumentStateException.class, () -> controller.recordPayment(id, payment(secondDay, "0")));

    controller.recordPayment(id, payment(secondDay, "69.00"));
    InvoiceDto paid = controller.getInvoice(id).getBody();
    assertEquals(PAID, paid.documentStatusId());
    assertEquals(secondDay, paid.paidDate(), "paid date is the date of the last payment");

    Payment reversal = controller.reversePayment(id, first.id());
    assertEquals(new BigDecimal("-50.00"), reversal.amount());
    assertEquals(first.id(), reversal.reversesPaymentId());
    assertEquals(PARTIALLY_PAID, controller.getInvoice(id).getBody().documentStatusId());
    assertThrows(DocumentStateException.class, () -> controller.reversePayment(id, first.id()));
    assertEquals(3, controller.getPayments(id).size(), "nothing is deleted");

    List<String> actions =
        controller.getHistory(id).stream().map(DocumentHistoryEntry::action).toList();
    assertEquals(
        List.of("CREATED", "ISSUED", "PAYMENT_RECORDED", "PAYMENT_RECORDED", "PAYMENT_REVERSED"),
        actions);
  }

  @Test
  void markingAnInvoicePaidRecordsTheOpenAmountOnTheGivenDate() {
    saveOwner(false);
    int id = issue(create(INVOICE, item(1, 100)).id()).id();
    LocalDate received = LocalDate.now().minusDays(2);

    assertThrows(
        DocumentStateException.class, () -> controller.updateInvoiceStatus(id, status(PAID)));
    controller.updateInvoiceStatus(id, new StatusChangeRequest(PAID, received, null));

    InvoiceDto paid = controller.getInvoice(id).getBody();
    assertEquals(PAID, paid.documentStatusId());
    assertEquals(received, paid.paidDate());
    assertEquals(new BigDecimal("119.00"), paid.paidAmount());
  }

  @Test
  void cancellingCreatesAnIssuedCreditNoteThatReferencesTheInvoice() throws Exception {
    saveOwner(false);
    InvoiceDto invoice = issue(create(INVOICE, item(2, 25)).id());
    controller.recordPayment(invoice.id(), payment(LocalDate.now(), "10.00"));

    InvoiceDto creditNote = controller.cancelInvoice(invoice.id());

    assertEquals(CREDIT_NOTE, creditNote.documentTypeId());
    assertEquals("ST-" + YEAR + "-001", creditNote.invoiceNumber());
    assertEquals(SENT, creditNote.documentStatusId());
    assertEquals(invoice.id(), creditNote.cancelsInvoiceId());
    assertEquals(0, invoice.netAmount().compareTo(creditNote.netAmount()));
    InvoiceDto cancelled = controller.getInvoice(invoice.id()).getBody();
    assertEquals(CANCELLED, cancelled.documentStatusId());
    assertEquals(creditNote.id(), cancelled.cancelledByInvoiceId());
    assertEquals(new BigDecimal("10.00"), cancelled.paidAmount(), "payments are kept");

    String html =
        TestWiring.pdfGenerator()
            .generateInvoiceHtml(TestWiring.invoiceRepository().findById(creditNote.id()));
    assertTrue(html.contains("Stornorechnung"));
    assertTrue(html.contains("Storno zu Rechnung:</strong> " + invoice.invoiceNumber()));
    assertTrue(html.contains("-50,00"), "amounts are shown negative");

    assertThrows(DocumentStateException.class, () -> controller.cancelInvoice(invoice.id()));
    assertThrows(
        DocumentStateException.class, () -> controller.updateInvoice(creditNote.id(), notes("x")));
    assertThrows(DocumentStateException.class, () -> controller.deleteInvoice(creditNote.id()));
    assertThrows(
        DocumentStateException.class,
        () -> controller.createInvoice(dto(CREDIT_NOTE, customerId, null, item(1, 1))));
    int draft = create(INVOICE, item(1, 1)).id();
    assertThrows(DocumentStateException.class, () -> controller.cancelInvoice(draft));
  }

  @Test
  void issuedPdfIsTheFrozenSnapshotEvenAfterTheCustomerChanges() {
    saveOwner(false);
    int id = issue(create(INVOICE, item(1, 10)).id()).id();
    String snapshot = TestWiring.invoiceRepository().findById(id).getRenderedHtml();
    assertTrue(snapshot.contains("Müller &amp; Söhne"));

    CustomerController customers = TestWiring.customerController();
    customers.updateCustomer(
        customerId,
        new CustomerDto(null, "Max", "Umbenannt", "12345", "Ort", "Weg", 1, "", "", "", "", null));

    assertEquals(HttpStatus.OK, controller.downloadInvoicePdf(id).getStatusCode());
    assertEquals(snapshot, TestWiring.invoiceRepository().findById(id).getRenderedHtml());
  }

  @Test
  void archiveHidesDocumentFromDefaultListAndUnknownIdsAreNotFound() {
    int id = create(INVOICE, item(1, 10)).id();

    controller.archiveInvoice(id);
    assertTrue(listed(false, false).isEmpty());
    assertEquals(1, listed(true, false).size());

    controller.unarchiveInvoice(id);
    assertEquals(1, listed(false, false).size());

    assertEquals(HttpStatus.NOT_FOUND, controller.archiveInvoice(999).getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, controller.unarchiveInvoice(999).getStatusCode());
    assertEquals(HttpStatus.NOT_FOUND, controller.getInvoice(999).getStatusCode());
  }

  @Test
  void pdfDownloadRequiresConfiguredCompanyData() {
    int id = create(INVOICE, item(1, 10)).id();

    MissingConfigurationException e =
        assertThrows(MissingConfigurationException.class, () -> controller.downloadInvoicePdf(id));
    assertTrue(e.getMessage().contains("Firmendaten"));
  }

  @Test
  void pdfDownloadRendersSpecialCharactersAndNamesFileByDocumentType() throws Exception {
    saveOwner(false);
    InvoiceItemDto item =
        new InvoiceItemDto(
            null,
            null,
            "service",
            0,
            "Planung & <Umsetzung>",
            null,
            "Std",
            bd(2.0),
            BigDecimal.valueOf(50),
            null,
            null,
            List.of(),
            null);
    InvoiceDto offer = create(OFFER, item);

    ResponseEntity<Resource> response = controller.downloadInvoicePdf(offer.id());

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertEquals(
        "attachment; filename=\"Angebot_AN-" + YEAR + "-001.pdf\"",
        response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION));
    byte[] pdf = response.getBody().getContentAsByteArray();
    assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
  }

  @Test
  void customerWithDocumentsCannotBeDeleted() {
    create(INVOICE, item(1, 10));
    CustomerController customers = TestWiring.customerController();

    ResponseEntity<Map<String, String>> response = customers.deleteCustomer(customerId);

    assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
    assertEquals(1, listed(true, true).size(), "documents must survive");
  }

  @Test
  void deletingDocumentRemovesItsOverrideTemplateButNeverAGlobalOne() {
    InvoiceTemplateRepository templates = TestWiring.templateRepository();
    InvoiceTemplate override = new InvoiceTemplate();
    override.setName("Override");
    override.setDocumentTypeId(INVOICE);
    override.setBlocks(DEFAULT_BLOCKS);
    override.setDocumentSpecific(true);
    templates.save(override);
    int globalId = templates.findByDocumentTypeId(INVOICE).get(0).getId();

    int withOverride = create(dto(INVOICE, customerId, override.getId(), item(1, 1))).id();
    int withGlobal = create(dto(INVOICE, customerId, globalId, item(1, 1))).id();

    controller.deleteInvoice(withOverride);
    controller.deleteInvoice(withGlobal);

    assertNull(templates.findById(override.getId()));
    assertNotNull(templates.findById(globalId));
    assertThrows(DocumentNotFoundException.class, () -> controller.deleteInvoice(withGlobal));
  }

  @Test
  void newVersionKeepsThePerDocumentTemplate() {
    InvoiceTemplateRepository templates = TestWiring.templateRepository();
    InvoiceTemplate override = new InvoiceTemplate();
    override.setName("Override");
    override.setDocumentTypeId(OFFER);
    override.setBlocks(DEFAULT_BLOCKS);
    override.setDocumentSpecific(true);
    templates.save(override);
    InvoiceDto first = create(dto(OFFER, customerId, override.getId(), item(1, 1)));

    InvoiceDto version =
        controller.createInvoice(
            withNumber(
                dto(OFFER, customerId, override.getId(), item(1, 1)), first.invoiceNumber() + "/"));

    assertEquals(first.invoiceNumber() + "/2", version.invoiceNumber());
    assertEquals(override.getId(), version.templateId());
  }

  @Test
  void smallBusinessRuleAppliesToDraftsButNotToIssuedDocuments() {
    saveOwner(false);
    InvoiceDto issued = issue(create(INVOICE, item(1, 100)).id());
    InvoiceDto draft = create(INVOICE, item(1, 100));

    saveOwner(true); // owner becomes a Kleinunternehmer afterwards

    InvoiceDto issuedLater = controller.getInvoice(issued.id()).getBody();
    InvoiceDto draftAfterEdit = controller.updateInvoice(draft.id(), notes("x")).getBody();
    InvoiceDto clone =
        controller.cloneInvoice(issued.id(), Map.of("invoiceDate", "2026-01-01")).getBody();

    assertEquals(0, BigDecimal.valueOf(19).compareTo(issuedLater.taxRate()));
    assertEquals(0, BigDecimal.ZERO.compareTo(draftAfterEdit.taxRate()));
    assertEquals(0, BigDecimal.ZERO.compareTo(clone.taxRate()));
    assertEquals(DRAFT, clone.documentStatusId());
  }

  @Test
  void statisticsCountOnlyIssuedInvoicesAndOffsetCreditNotes() {
    saveOwner(false);
    issue(create(INVOICE, item(1, 100)).id()); // counts: 100
    int cancelled = issue(create(INVOICE, item(1, 40)).id()).id(); // +40 ...
    controller.cancelInvoice(cancelled); // ... -40 by its credit note
    create(INVOICE, item(1, 1000)); // draft: ignored
    create(OFFER, item(1, 1000)); // offer: ignored

    var stats = controller.getStatistics(YEAR + "-01-01", YEAR + "-12-31");

    assertEquals(HttpStatus.OK, stats.getStatusCode());
    assertAmount(100.0, stats.getBody().getTotalRevenue(), 0.001);
    assertAmount(19.0, stats.getBody().getTotalTaxAmount(), 0.001);
    // Both invoices were issued; the credit note offsets the amounts, it is not counted.
    assertEquals(2, stats.getBody().getTotalInvoiceCount());
    assertEquals(1, stats.getBody().getUnpaidInvoiceCount());
    // Gross from VatCalculator: the open invoice 119; the cancelled one and its credit note cancel.
    assertAmount(119.0, stats.getBody().getTotalGross(), 0.001);
    assertAmount(119.0, stats.getBody().getUnpaidGross(), 0.001);
    assertAmount(0.0, stats.getBody().getPaidGross(), 0.001);
    assertAmount(119.0, stats.getBody().getIssuedGross(), 0.001);
    assertAmount(19.0, stats.getBody().getIssuedTaxAmount(), 0.001);
  }

  @Test
  void aCreditNoteMarkedPaidInLegacyDataNeverCountsAsAPaidInvoice() throws Exception {
    saveOwner(false);
    issue(create(INVOICE, item(1, 100)).id()); // open: 119 gross
    int cancelled = issue(create(INVOICE, item(1, 40)).id()).id();
    controller.cancelInvoice(cancelled);
    // The app cannot pay a credit note; simulate legacy / hand-edited data.
    try (java.sql.Connection conn = de.billery.data.Database.getConnection();
        java.sql.Statement stmt = conn.createStatement()) {
      stmt.executeUpdate(
          "UPDATE invoice SET document_status_id = 6, paid_date = CURRENT_DATE"
              + " WHERE document_type_id = 6");
    }

    var stats = controller.getStatistics(YEAR + "-01-01", YEAR + "-12-31").getBody();

    assertEquals(0, stats.getPaidInvoiceCount());
    assertAmount(0.0, stats.getPaidGross(), 0.001);
    assertAmount(0.0, stats.getPaidTaxAmount(), 0.001);
    assertAmount(119.0, stats.getIssuedGross(), 0.001);
    assertAmount(19.0, stats.getIssuedTaxAmount(), 0.001);
    assertAmount(119.0, stats.getTotalGross(), 0.001); // the credit note still offsets its invoice
  }

  @Test
  void statisticsRefuseUnparsableReversedAndOverlongPeriodsWith400() {
    assertEquals(
        HttpStatus.BAD_REQUEST, controller.getStatistics("2026-13-01", null).getStatusCode());
    assertEquals(HttpStatus.BAD_REQUEST, controller.getStatistics("gestern", null).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.getStatistics("2026-02-01", "2026-01-01").getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.getStatistics("2000-01-01", "2026-12-31").getStatusCode());
    assertEquals(
        HttpStatus.OK, controller.getStatistics("2017-01-01", "2026-12-31").getStatusCode());
  }

  @Test
  void theDatabaseRefusesToDeleteACustomerWithDocuments() {
    create(INVOICE, item(1, 10));

    assertThrows(DatabaseException.class, () -> TestWiring.customerRepository().delete(customerId));
  }

  // --- helpers ---------------------------------------------------------------------------------

  private static final String DEFAULT_BLOCKS =
      "[{\"id\":\"h\",\"type\":\"header\",\"order\":0,\"data\":{\"companyName\":\"{{firma}}\"},"
          + "\"config\":{}},"
          + "{\"id\":\"a\",\"type\":\"address\",\"order\":1,\"data\":{\"role\":\"receiver\","
          + "\"name\":\"{{kunde}}\"},\"config\":{}},"
          + "{\"id\":\"m\",\"type\":\"invoice-meta\",\"order\":2,\"data\":{},\"config\":{}},"
          + "{\"id\":\"i\",\"type\":\"line-items\",\"order\":3,\"data\":{\"columns\":["
          + "{\"key\":\"description\",\"label\":\"Beschreibung\",\"show\":true},"
          + "{\"key\":\"quantity\",\"label\":\"Menge\",\"show\":true},"
          + "{\"key\":\"amount\",\"label\":\"Gesamt\",\"show\":true}]},\"config\":{}},"
          + "{\"id\":\"t\",\"type\":\"totals\",\"order\":4,\"data\":{},\"config\":{}}]";

  /**
   * The list endpoints (document page, documents of a customer) load customers, items, payments and
   * credit notes in batches; every DTO must be identical to the one the single-document endpoint
   * builds with per-document queries.
   */
  @Test
  void listEndpointsReturnTheSameDtosAsTheSingleDocumentEndpoint() throws Exception {
    saveOwner(false);
    create(INVOICE, item(1, 10)); // draft
    InvoiceDto partlyPaid = issue(create(INVOICE, item(2, 25)).id());
    controller.recordPayment(partlyPaid.id(), payment(LocalDate.now(), "10.00"));
    InvoiceDto cancelled = issue(create(INVOICE, item(3, 7.5)).id());
    controller.cancelInvoice(cancelled.id()); // adds a credit note
    InvoiceDto archived = issue(create(INVOICE, item(1, 99)).id());
    controller.archiveInvoice(archived.id());

    List<InvoiceDto> all = listed(true, true);
    assertEquals(5, all.size());
    for (InvoiceDto listed : all) {
      assertEquals(controller.getInvoice(listed.id()).getBody(), listed);
    }
    for (InvoiceDto listed : controller.getInvoicesByCustomer(customerId)) {
      assertEquals(controller.getInvoice(listed.id()).getBody(), listed);
    }
    List<InvoiceDto> archivedList = listed(true, false);
    assertEquals(1, archivedList.size());
    assertEquals(controller.getInvoice(archived.id()).getBody(), archivedList.get(0));
  }

  /**
   * The search reads its hits by column label. A label with a table prefix ({@code i.id}) works on
   * H2 and MariaDB but not with the PostgreSQL driver, which made the search fail with a database
   * error as soon as a document matched.
   */
  @Test
  void searchFindsADocumentByItsNotes() {
    InvoiceDto created = create(INVOICE, item(1, 10));
    controller.updateInvoice(created.id(), notes("Dachsanierung Müller"));

    SearchResult result = TestWiring.searchController().search("sanierung").getBody();

    assertNotNull(result);
    assertEquals(1, result.invoices().size());
    assertEquals(created.id(), result.invoices().get(0).id());
    assertEquals("Dachsanierung Müller", result.invoices().get(0).notes());
  }

  private InvoiceDto create(int type, InvoiceItemDto item) {
    return create(dto(type, customerId, null, item));
  }

  private InvoiceDto create(InvoiceDto dto) {
    return controller.createInvoice(dto);
  }

  /** Issues a draft through the status endpoint, as the SPA does. */
  private InvoiceDto issue(int id) {
    return controller.updateInvoiceStatus(id, status(SENT)).getBody();
  }

  private static StatusChangeRequest status(int statusId) {
    return new StatusChangeRequest(statusId, null, null);
  }

  private static PaymentRequest payment(LocalDate date, String amount) {
    return new PaymentRequest(date, new BigDecimal(amount), "Überweisung", "", "");
  }

  private static InvoiceItemDto item(double quantity, double unitPrice) {
    return new InvoiceItemDto(
        null,
        null,
        "article",
        0,
        "Position",
        null,
        "Stk",
        bd(quantity),
        BigDecimal.valueOf(unitPrice),
        null,
        null,
        List.of(),
        null);
  }

  private static InvoiceDto dto(int type, int customer, Integer templateId, InvoiceItemDto item) {
    return new InvoiceDto(
        null,
        customer,
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
        type,
        List.of(item),
        false,
        LocalDate.now(),
        null,
        templateId,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static InvoiceDto withNumber(InvoiceDto d, String number) {
    return new InvoiceDto(
        d.id(),
        d.customerId(),
        d.customerName(),
        number,
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
        null,
        null,
        null);
  }

  /** Sets invoice date and service date (the due date follows 14 days later). */
  private static InvoiceDto withDates(InvoiceDto d, LocalDate serviceAndInvoiceDate) {
    LocalDate invoiceDate = serviceAndInvoiceDate != null ? serviceAndInvoiceDate : d.invoiceDate();
    return new InvoiceDto(
        d.id(),
        d.customerId(),
        d.customerName(),
        d.invoiceNumber(),
        d.documentStatusId(),
        invoiceDate,
        invoiceDate.plusDays(14),
        d.netAmount(),
        d.taxRate(),
        d.taxAmount(),
        d.grossAmount(),
        d.paidDate(),
        d.notes(),
        d.documentTypeId(),
        d.items(),
        d.archived(),
        serviceAndInvoiceDate,
        d.workEndDate(),
        d.templateId(),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static InvoiceDto notes(String notes) {
    return new InvoiceDto(
        null, null, null, null, null, null, null, null, null, null, null, null, notes, null, null,
        null, null, null, null, null, null, null, null, null, null);
  }

  private static void saveOwner(boolean smallBusiness) {
    OwnerRepository owners = TestWiring.ownerRepository();
    owners.findAll().forEach(o -> owners.delete(o.getId()));
    owners.save(
        new Owner(
            0,
            "Erika",
            "Muster",
            "Muster & Partner",
            "GmbH",
            "12345",
            "Ort",
            "Weg",
            1,
            "",
            "",
            "",
            "DE1",
            "DE02120300000000202051",
            smallBusiness));
  }

  /**
   * The documents of the list as the SPA reads them ({@code GET /api/invoices/page}, one page).
   *
   * @param archived {@code true} for the archive
   * @param includeArchived {@code true} for active and archived documents together
   * @return the documents
   */
  private List<InvoiceDto> listed(boolean archived, boolean includeArchived) {
    return controller
        .getInvoicePage(0, 100, null, archived, includeArchived, null, null, null, null, null)
        .getBody()
        .items();
  }
}
