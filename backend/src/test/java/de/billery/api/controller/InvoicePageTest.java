package de.billery.api.controller;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.InvoicePageDto;
import de.billery.api.dto.PaymentRequest;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.Database;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * {@code GET /api/invoices/page}: the filters the document list used to apply in the browser, now
 * in SQL, plus paging, order and the 400 limits.
 */
class InvoicePageTest {

  private static final List<Integer> INVOICE_TAB =
      List.of(DocumentType.INVOICE, DocumentType.CREDIT_NOTE);
  private static final List<Integer> OFFER_TAB = List.of(DocumentType.OFFER);

  private InvoiceController controller;
  private int max;
  private int erika;

  private int draftInvoice;
  private int overdueInvoice;
  private int paidInvoice;
  private int archivedInvoice;
  private int expiredOffer;
  private int acceptedOldOffer;
  private int offer2025;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void fixture() throws Exception {
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
    max = customer("Max", "Kunde");
    erika = customer("Erika", "100%_Muster");
    controller = TestWiring.invoiceController();

    draftInvoice = draft(DocumentType.INVOICE, max, LocalDate.of(2026, 5, 1));
    overdueInvoice = issue(draft(DocumentType.INVOICE, max, LocalDate.of(2026, 2, 1)));
    paidInvoice = issue(draft(DocumentType.INVOICE, erika, LocalDate.of(2026, 3, 1)));
    controller.recordPayment(
        paidInvoice,
        new PaymentRequest(LocalDate.of(2026, 3, 5), new BigDecimal("119.00"), "Bar", "", ""));
    archivedInvoice = issue(draft(DocumentType.INVOICE, max, LocalDate.of(2026, 4, 1)));
    controller.archiveInvoice(archivedInvoice);

    expiredOffer = draft(DocumentType.OFFER, erika, LocalDate.of(2026, 6, 1));
    status(expiredOffer, DocumentStatus.SENT);
    acceptedOldOffer = draft(DocumentType.OFFER, max, LocalDate.of(2026, 6, 2));
    status(acceptedOldOffer, DocumentStatus.ACCEPTED);
    offer2025 = draft(DocumentType.OFFER, max, LocalDate.of(2025, 11, 1));
    validUntil(expiredOffer, LocalDate.of(2026, 6, 15));
    validUntil(acceptedOldOffer, LocalDate.of(2026, 6, 15));
  }

  @Test
  void filtersByTabArchiveAndStatusLikeTheFormerBrowserFilter() {
    assertEquals(
        Set.of(draftInvoice, overdueInvoice, paidInvoice),
        ids(page(INVOICE_TAB, false, null, null, null)));
    assertEquals(Set.of(archivedInvoice), ids(page(INVOICE_TAB, true, null, null, null)));
    assertEquals(Set.of(draftInvoice), ids(page(INVOICE_TAB, false, null, null, "draft")));
    assertEquals(Set.of(paidInvoice), ids(page(INVOICE_TAB, false, null, null, "paid")));
    assertEquals(Set.of(overdueInvoice), ids(page(INVOICE_TAB, false, null, null, "overdue")));
    assertEquals(
        Set.of(expiredOffer, acceptedOldOffer, offer2025),
        ids(page(OFFER_TAB, false, null, null, null)));
    assertEquals(Set.of(acceptedOldOffer), ids(page(OFFER_TAB, false, null, null, "accepted")));
  }

  @Test
  void expiredMeansValidityPassedAndNoAnswerYet() {
    assertEquals(Set.of(expiredOffer), ids(page(OFFER_TAB, false, null, null, "expired")));
  }

  @Test
  void filtersByYearOfTheDocumentDate() {
    assertEquals(Set.of(offer2025), ids(page(OFFER_TAB, false, 2025, null, null)));
    assertEquals(
        Set.of(expiredOffer, acceptedOldOffer), ids(page(OFFER_TAB, false, 2026, null, null)));
  }

  @Test
  void searchMatchesNumberAndCustomerNameCaseInsensitiveAndLiterally() {
    assertEquals(Set.of(paidInvoice), ids(page(INVOICE_TAB, false, null, "ERIKA", null)));
    assertEquals(Set.of(paidInvoice), ids(page(INVOICE_TAB, false, null, "100%_m", null)));
    // "%" and "_" are literal: "0%" is not a wildcard matching every name.
    assertEquals(Set.of(), ids(page(INVOICE_TAB, false, null, "x%", null)));
    String number = controller.getInvoice(overdueInvoice).getBody().invoiceNumber();
    assertEquals(
        Set.of(overdueInvoice), ids(page(INVOICE_TAB, false, null, number.toLowerCase(), null)));
  }

  @Test
  void pagesAreStableCompleteAndWithoutOverlap() {
    List<Integer> all = new ArrayList<>();
    InvoicePageDto first = controller.getInvoicePage(0, 2, null, false, null, null, null).getBody();
    assertEquals(6, first.total());
    for (int p = 0; p * 2 < first.total(); p++) {
      List<InvoiceDto> items =
          controller.getInvoicePage(p, 2, null, false, null, null, null).getBody().items();
      assertTrue(items.size() <= 2);
      items.forEach(i -> all.add(i.id()));
    }
    assertEquals(6, all.size());
    assertEquals(6, new HashSet<>(all).size(), "no document on two pages");
    assertEquals(draftInvoice, all.get(0), "drafts without number come first");
  }

  @Test
  void pageItemsEqualTheSingleDocumentDto() {
    for (InvoiceDto item :
        controller.getInvoicePage(0, 50, null, false, null, null, null).getBody().items()) {
      assertEquals(controller.getInvoice(item.id()).getBody(), item, "document " + item.id());
    }
  }

  @Test
  void reportsTheYearsThatHaveDocuments() {
    assertEquals(
        List.of(2026, 2025),
        controller.getInvoicePage(0, 1, null, false, null, null, null).getBody().years());
    assertEquals(
        List.of(2026),
        controller.getInvoicePage(0, 1, null, true, null, null, null).getBody().years());
  }

  @Test
  void refusesNegativePagesOversizedPagesAndUnknownStatusKeys() {
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.getInvoicePage(-1, 10, null, false, null, null, null).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.getInvoicePage(0, 0, null, false, null, null, null).getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller
            .getInvoicePage(0, InvoiceController.MAX_PAGE_SIZE + 1, null, false, null, null, null)
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.getInvoicePage(0, 10, null, false, null, null, "bogus").getStatusCode());
    assertEquals(
        HttpStatus.OK,
        controller
            .getInvoicePage(0, InvoiceController.MAX_PAGE_SIZE, null, false, null, null, null)
            .getStatusCode());
  }

  @Test
  void refusesPagesBeyondTheLimitInsteadOfOverflowingTheOffset() {
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller
            .getInvoicePage(
                Integer.MAX_VALUE, InvoiceController.MAX_PAGE_SIZE, null, false, null, null, null)
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller
            .getInvoicePage(InvoiceController.MAX_PAGE + 1, 1, null, false, null, null, null)
            .getStatusCode());
    ResponseEntity<InvoicePageDto> lastAllowed =
        controller.getInvoicePage(InvoiceController.MAX_PAGE, 1, null, false, null, null, null);
    assertEquals(HttpStatus.OK, lastAllowed.getStatusCode());
    assertEquals(
        List.of(), lastAllowed.getBody().items(), "a page past the end is empty, not an error");
  }

  @Test
  void dashboardFiltersByDateRangeAcrossArchiveWithOpenAndIssued() {
    List<Integer> invoiceTypes = List.of(DocumentType.INVOICE);
    LocalDate from = LocalDate.of(2026, 2, 1);
    LocalDate to = LocalDate.of(2026, 4, 30);

    assertEquals(
        Set.of(overdueInvoice, paidInvoice, archivedInvoice),
        ids(dashboard(invoiceTypes, from, to, "issued")),
        "issued covers sent, partly paid and paid, active and archived");
    assertEquals(Set.of(paidInvoice), ids(dashboard(invoiceTypes, from, to, "paid")));
    assertEquals(
        Set.of(overdueInvoice, archivedInvoice), ids(dashboard(invoiceTypes, from, to, "open")));
    assertEquals(
        Set.of(paidInvoice),
        ids(dashboard(invoiceTypes, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 1), "issued")),
        "both bounds are inclusive");
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller
            .getInvoicePage(0, 10, invoiceTypes, false, true, null, to, from, null, null)
            .getStatusCode(),
        "from after to");
  }

  private InvoicePageDto dashboard(
      List<Integer> types, LocalDate from, LocalDate to, String status) {
    ResponseEntity<InvoicePageDto> response =
        controller.getInvoicePage(
            0, InvoiceController.MAX_PAGE_SIZE, types, false, true, null, from, to, null, status);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return response.getBody();
  }

  @Test
  void refusesYearsOutsideTheCalendarRangeInsteadOfFailingWith500() {
    for (int year : new int[] {0, -1, 10_000, 999_999_999, Integer.MAX_VALUE, Integer.MIN_VALUE}) {
      assertEquals(
          HttpStatus.BAD_REQUEST,
          controller.getInvoicePage(0, 10, null, false, year, null, null).getStatusCode(),
          "year " + year);
    }
    assertEquals(
        HttpStatus.OK,
        controller.getInvoicePage(0, 10, null, false, 9999, null, null).getStatusCode());
    assertEquals(
        HttpStatus.OK,
        controller.getInvoicePage(0, 10, null, false, 1, null, null).getStatusCode());
  }

  private InvoicePageDto page(
      List<Integer> types, boolean archived, Integer year, String q, String status) {
    ResponseEntity<InvoicePageDto> response =
        controller.getInvoicePage(
            0, InvoiceController.MAX_PAGE_SIZE, types, archived, year, q, status);
    assertEquals(HttpStatus.OK, response.getStatusCode());
    return response.getBody();
  }

  private static Set<Integer> ids(InvoicePageDto page) {
    Set<Integer> ids = new HashSet<>();
    page.items().forEach(i -> ids.add(i.id()));
    assertEquals(ids.size(), page.total(), "total matches the items when everything fits one page");
    return ids;
  }

  private void status(int id, int status) {
    controller.updateInvoiceStatus(id, new StatusChangeRequest(status, null, null));
  }

  private int issue(int id) {
    status(id, DocumentStatus.SENT);
    return id;
  }

  private static void validUntil(int id, LocalDate date) throws Exception {
    try (Connection conn = Database.getConnection();
        PreparedStatement stmt =
            conn.prepareStatement("UPDATE invoice SET offer_valid_until = ? WHERE id = ?")) {
      stmt.setObject(1, date);
      stmt.setInt(2, id);
      stmt.executeUpdate();
    }
  }

  private static int customer(String first, String last) {
    return TestWiring.customerController()
        .createCustomer(
            new CustomerDto(null, first, last, "1", "Ort", "Weg", 1, "", "", "", "", ""))
        .getId();
  }

  private int draft(int type, int customerId, LocalDate date) {
    InvoiceItemDto item =
        new InvoiceItemDto(
            null,
            null,
            "article",
            0,
            "Beratung",
            null,
            "Std",
            bd(1),
            BigDecimal.valueOf(100),
            null,
            null,
            List.of(),
            new BigDecimal("19"));
    return controller
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
                type,
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
}
