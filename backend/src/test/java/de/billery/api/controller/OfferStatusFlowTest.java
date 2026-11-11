package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.api.ApiExceptionHandler;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.DocumentHistoryEntry;
import de.billery.data.TestDatabaseConfig;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import de.billery.service.InvalidStatusChangeException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** {@code PATCH /api/invoices/{id}/status} for offers and cost calculations. */
class OfferStatusFlowTest {

  private static final int[] QUOTE_TYPES = {DocumentType.OFFER, DocumentType.COST_CALCULATION};

  private InvoiceController controller;
  private static int customerId;

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
    customerId =
        TestWiring.customerController()
            .createCustomer(
                new CustomerDto(
                    null, "Max", "Kunde", "1", "Ort", "Weg", 1, "", "max@kunde.de", "", "", ""))
            .getId();
    controller = TestWiring.invoiceController();
  }

  @Test
  void aDraftCanBeAcceptedDirectly() {
    for (int type : QUOTE_TYPES) {
      int id = draft(type);

      assertEquals(
          HttpStatus.OK, change(id, DocumentStatus.ACCEPTED).getStatusCode(), "type " + type);
      assertEquals(DocumentStatus.ACCEPTED, statusOf(id));
    }
  }

  @Test
  void aSentOfferIsAcceptedAndThenConverted() {
    for (int type : QUOTE_TYPES) {
      int id = draft(type);

      change(id, DocumentStatus.SENT);
      assertEquals(DocumentStatus.SENT, statusOf(id));
      change(id, DocumentStatus.ACCEPTED);
      change(id, DocumentStatus.CONVERTED);

      assertEquals(DocumentStatus.CONVERTED, statusOf(id), "type " + type);
    }
  }

  @Test
  void aRejectedOfferCanStillBeAccepted() {
    int id = draft(DocumentType.OFFER);
    change(id, DocumentStatus.SENT);
    change(id, DocumentStatus.REJECTED);
    assertEquals(DocumentStatus.REJECTED, statusOf(id));

    assertEquals(HttpStatus.OK, change(id, DocumentStatus.ACCEPTED).getStatusCode());
    assertEquals(DocumentStatus.ACCEPTED, statusOf(id));
  }

  @Test
  void aStatusChangeIsLoggedWithBothStatusNames() {
    int id = draft(DocumentType.OFFER);

    change(id, DocumentStatus.SENT);
    change(id, DocumentStatus.ACCEPTED);

    assertEquals(List.of("Entwurf → Gesendet", "Gesendet → Angenommen"), statusChangeNotes(id));
  }

  @Test
  void aStatusChangeThroughTheFormIsLoggedWithBothStatusNames() {
    int id = draft(DocumentType.OFFER);

    controller.updateInvoice(id, dto(DocumentType.OFFER, DocumentStatus.SENT));

    assertEquals(List.of("Entwurf → Gesendet"), statusChangeNotes(id));
  }

  private static List<String> statusChangeNotes(int id) {
    return TestWiring.historyRepository().findByInvoiceId(id).stream()
        .filter(entry -> "STATUS_CHANGED".equals(entry.action()))
        .map(DocumentHistoryEntry::notes)
        .toList();
  }

  @Test
  void settingTheCurrentStatusAgainIsANoOpWithoutHistory() {
    int id = draft(DocumentType.OFFER);
    change(id, DocumentStatus.ACCEPTED);
    int historyBefore = TestWiring.historyRepository().findByInvoiceId(id).size();

    ResponseEntity<InvoiceDto> again = change(id, DocumentStatus.ACCEPTED);

    assertEquals(HttpStatus.OK, again.getStatusCode());
    assertEquals(DocumentStatus.ACCEPTED, again.getBody().documentStatusId());
    assertEquals(historyBefore, TestWiring.historyRepository().findByInvoiceId(id).size());
  }

  @Test
  void aRefusedChangeNamesBothStatusesAndWhatIsPossible() {
    int id = draft(DocumentType.OFFER);
    change(id, DocumentStatus.SENT);
    change(id, DocumentStatus.REJECTED);

    InvalidStatusChangeException e =
        assertThrows(InvalidStatusChangeException.class, () -> change(id, DocumentStatus.SENT));

    assertTrue(e.getMessage().contains("Abgelehnt"), e.getMessage());
    assertTrue(e.getMessage().contains("Gesendet"), e.getMessage());
    assertTrue(e.getMessage().contains("Möglich: Angenommen, Storniert."), e.getMessage());
    assertEquals(DocumentStatus.REJECTED, statusOf(id), "a refused change leaves the status");
  }

  @Test
  void aFinalStatusSaysThatNoChangeIsPossibleAnymore() {
    int id = draft(DocumentType.COST_CALCULATION);
    change(id, DocumentStatus.ACCEPTED);
    change(id, DocumentStatus.CONVERTED);

    InvalidStatusChangeException e =
        assertThrows(InvalidStatusChangeException.class, () -> change(id, DocumentStatus.ACCEPTED));

    assertTrue(e.getMessage().contains("kein Wechsel mehr möglich"), e.getMessage());
  }

  @Test
  void aRefusedChangeIsAnsweredWithAJsonMessageAndHttp400() {
    ResponseEntity<Map<String, String>> response =
        new ApiExceptionHandler().handleInvalidStatusChange(new InvalidStatusChangeException("x"));

    assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
    assertEquals("x", response.getBody().get("message"));
  }

  private ResponseEntity<InvoiceDto> change(int id, int status) {
    return controller.updateInvoiceStatus(id, new StatusChangeRequest(status, null, null));
  }

  private int statusOf(int id) {
    return controller.getInvoice(id).getBody().documentStatusId();
  }

  private int draft(int documentTypeId) {
    return controller.createInvoice(dto(documentTypeId, null)).id();
  }

  private static InvoiceDto dto(int documentTypeId, Integer statusId) {
    InvoiceItemDto item =
        new InvoiceItemDto(
            null,
            null,
            "article",
            0,
            "Beratung",
            null,
            "Std",
            BigDecimal.ONE,
            BigDecimal.valueOf(100),
            null,
            null,
            List.of(),
            new BigDecimal("19"));
    LocalDate date = LocalDate.of(2026, 3, 1);
    return new InvoiceDto(
        null,
        customerId,
        null,
        null,
        statusId,
        date,
        date.plusDays(14),
        null,
        BigDecimal.valueOf(19),
        null,
        null,
        null,
        null,
        documentTypeId,
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
        null);
  }
}
