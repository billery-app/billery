package de.billery.api.controller;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.EquipmentDto;
import de.billery.api.dto.EquipmentSummaryDto;
import de.billery.api.dto.EquipmentUsageDto;
import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.PaymentRequest;
import de.billery.api.dto.ServiceDto;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The amortisation figures of the catalog ({@code GET /api/equipment/summary}) and the list behind
 * them ({@code GET /api/equipment/{id}/usage}).
 *
 * <p>The summary used to run two queries per service; it is one grouped query now. The fixture
 * therefore holds everything that decides whether a line counts: several services per equipment, a
 * sub-item, paid, partly paid, sent and draft invoices, an offer, a service without equipment and
 * an article line that happens to carry a service's id. {@link
 * #figuresEqualTheFormerQueryPerService()} runs the former statements next to the endpoint.
 */
class EquipmentUsageTest {

  private InvoiceController invoices;
  private EquipmentController controller;
  private int customerId;

  private int drill;
  private int scaffold;
  private int unused;
  private int laser;

  private int drilling;
  private int coring;
  private int scaffolding;
  private int advice;
  private int measuring;

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
    customerId =
        TestWiring.customerController()
            .createCustomer(
                new CustomerDto(null, "Max", "Kunde", "1", "Ort", "Weg", 1, "", "", "", "", ""))
            .getId();
    invoices = TestWiring.invoiceController();
    controller = TestWiring.equipmentController();

    drill = equipment("Kernbohrgerät", "1000");
    scaffold = equipment("Gerüst", null);
    unused = equipment("Ersatzgerät", "500");
    laser = equipment("Lasermessgerät", "100");

    drilling = service("Bohren", drill, "Std");
    coring = service("Kernbohrung", drill, "Stk");
    scaffolding = service("Gerüststellung", scaffold, "Tag");
    advice = service("Beratung", null, "Std");
    measuring = service("Aufmaß", laser, "Std");

    // Paid: two services of the drill, a sub-item, a service without equipment, and an article
    // line whose id equals the drilling service's.
    InvoiceItemDto withChild =
        new InvoiceItemDto(
            null,
            null,
            "service",
            drilling,
            "Bohren",
            null,
            "Std",
            bd(2),
            bd(100),
            null,
            null,
            List.of(line("service", drilling, "1", "10")),
            bd(19));
    pay(
        invoice(
            LocalDate.of(2026, 3, 1),
            withChild,
            line("service", coring, "1", "50.50"),
            line("service", advice, "1", "999"),
            line("article", drilling, "1", "777")));
    pay(
        invoice(
            LocalDate.of(2026, 4, 1),
            line("service", drilling, "3", "100"),
            line("service", scaffolding, "1", "70"),
            line("service", measuring, "1", "250")));

    // Not paid in full, so none of these count.
    issue(invoice(LocalDate.of(2026, 5, 1), line("service", drilling, "1", "1000")));
    int partlyPaid = issue(invoice(LocalDate.of(2026, 5, 2), line("service", coring, "1", "400")));
    invoices.recordPayment(
        partlyPaid, new PaymentRequest(LocalDate.of(2026, 5, 3), bd(100), "Bar", "", ""));
    invoice(LocalDate.of(2026, 5, 4), line("service", drilling, "1", "2000"));
    int offer =
        document(DocumentType.OFFER, LocalDate.of(2026, 5, 5), line("service", drilling, "1", "9"));
    invoices.updateInvoiceStatus(
        offer, new StatusChangeRequest(DocumentStatus.ACCEPTED, null, null));
  }

  @Test
  void summaryCountsOnlyServiceLinesOnPaidInvoices() {
    List<EquipmentSummaryDto> summary = controller.getSummary();

    assertEquals(
        List.of(drill, scaffold, unused, laser),
        summary.stream().map(EquipmentSummaryDto::id).toList(),
        "one entry per equipment, in the order of the equipment list");

    // 2 x 100 + its sub-item 10 + 50.50 on the first invoice, 3 x 100 on the second.
    EquipmentSummaryDto drillFigures = summary.get(0);
    assertAmount("560.50", drillFigures.totalRevenue());
    assertEquals(4, drillFigures.usageCount());
    assertAmount("56.1", drillFigures.paybackPercent());

    // No purchase price: revenue is reported, a percentage cannot be.
    EquipmentSummaryDto scaffoldFigures = summary.get(1);
    assertAmount("70.00", scaffoldFigures.totalRevenue());
    assertEquals(1, scaffoldFigures.usageCount());
    assertAmount("0", scaffoldFigures.paybackPercent());
    assertAmount("0", scaffoldFigures.purchaseValue());

    // Equipment no service is linked to is still listed, with zeros.
    EquipmentSummaryDto unusedFigures = summary.get(2);
    assertAmount("0.00", unusedFigures.totalRevenue());
    assertEquals(0, unusedFigures.usageCount());
    assertAmount("0", unusedFigures.paybackPercent());
  }

  @Test
  void paybackKeepsTheRealValueAbove100Percent() {
    EquipmentSummaryDto laserFigures = controller.getSummary().get(3);

    assertAmount("250.00", laserFigures.totalRevenue());
    assertAmount("250.0", laserFigures.paybackPercent());
  }

  @Test
  void figuresEqualTheFormerQueryPerService() {
    JdbcClient jdbc = TestWiring.jdbcClient();
    for (EquipmentSummaryDto figures : controller.getSummary()) {
      BigDecimal revenue = BigDecimal.ZERO;
      int count = 0;
      List<Integer> services =
          jdbc.sql("SELECT id FROM service WHERE equipment_id = :id")
              .param("id", figures.id())
              .query(Integer.class)
              .list();
      for (int serviceId : services) {
        String paidLines =
            " FROM invoice_item ii JOIN invoice i ON ii.invoice_id = i.id"
                + " WHERE UPPER(ii.item_type) = 'SERVICE' AND ii.item_id = :serviceId"
                + " AND i.document_status_id = 6";
        revenue =
            revenue.add(
                jdbc.sql("SELECT COALESCE(SUM(ii.total_price), 0)" + paidLines)
                    .param("serviceId", serviceId)
                    .query(BigDecimal.class)
                    .single());
        count +=
            jdbc.sql("SELECT COUNT(*)" + paidLines)
                .param("serviceId", serviceId)
                .query(Integer.class)
                .single();
      }
      assertAmount(revenue.toPlainString(), figures.totalRevenue());
      assertEquals(count, figures.usageCount(), figures.name());
    }
  }

  @Test
  void usageListsTheLinesBehindTheSummaryNewestFirst() {
    List<EquipmentUsageDto> usage = controller.getUsage(drill).getBody();

    assertNotNull(usage);
    assertEquals(
        List.of("2026-04-01", "2026-03-01", "2026-03-01", "2026-03-01"),
        usage.stream().map(EquipmentUsageDto::invoiceDate).toList());
    EquipmentUsageDto newest = usage.get(0);
    assertEquals("Bohren", newest.serviceName());
    assertEquals("Std", newest.unit());
    assertAmount("3", newest.quantity());
    assertAmount("100", newest.unitPrice());
    assertAmount("300", newest.totalPrice());
    assertEquals(paidInvoiceNumber(LocalDate.of(2026, 4, 1)), newest.invoiceNumber());

    // The rows add up to the summary: the dialog's sum row and the table show the same figures.
    EquipmentSummaryDto figures = controller.getSummary().get(0);
    BigDecimal sum =
        usage.stream().map(EquipmentUsageDto::totalPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
    assertAmount(figures.totalRevenue().toPlainString(), sum);
    assertEquals(figures.usageCount(), usage.size());
  }

  @Test
  void usageShowsTheServicesOwnUnitAndName() {
    List<EquipmentUsageDto> usage = controller.getUsage(drill).getBody();

    assertNotNull(usage);
    EquipmentUsageDto coringLine =
        usage.stream().filter(u -> "Kernbohrung".equals(u.serviceName())).findFirst().orElseThrow();
    assertEquals("Stk", coringLine.unit(), "the service's unit, not the line's");
    assertAmount("50.50", coringLine.totalPrice());
  }

  @Test
  void unusedEquipmentHasNoUsageAndUnknownEquipmentIs404() {
    assertEquals(List.of(), controller.getUsage(unused).getBody());
    assertEquals(1, controller.getUsage(laser).getBody().size());
    assertEquals(HttpStatus.NOT_FOUND, controller.getUsage(999_999).getStatusCode());
  }

  /** The number of the paid invoice dated {@code date}, read through the invoice API. */
  private String paidInvoiceNumber(LocalDate date) {
    return invoices
        .getInvoicePage(0, 100, null, false, true, null, null, null, null, null)
        .getBody()
        .items()
        .stream()
        .filter(i -> date.equals(i.invoiceDate()) && i.documentStatusId() == DocumentStatus.PAID)
        .map(InvoiceDto::invoiceNumber)
        .findFirst()
        .orElseThrow();
  }

  /** Compares an amount by value (never with {@code equals}, which also compares the scale). */
  private static void assertAmount(String expected, BigDecimal actual) {
    assertNotNull(actual);
    assertTrue(
        new BigDecimal(expected).compareTo(actual) == 0,
        () -> "expected " + expected + " but was " + actual.toPlainString());
  }

  private int equipment(String name, String purchaseValue) {
    return controller
        .create(
            new EquipmentDto(
                null,
                name,
                purchaseValue == null ? null : new BigDecimal(purchaseValue),
                LocalDate.of(2025, 1, 1),
                48,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                "active",
                ""))
        .id();
  }

  private static int service(String name, Integer equipmentId, String unit) {
    return TestWiring.serviceController()
        .createService(new ServiceDto(null, name, "", bd(100), null, equipmentId, unit, bd(19)))
        .getId();
  }

  private static InvoiceItemDto line(String type, int itemId, String quantity, String unitPrice) {
    return new InvoiceItemDto(
        null,
        null,
        type,
        itemId,
        "Position",
        null,
        "Std",
        new BigDecimal(quantity),
        new BigDecimal(unitPrice),
        null,
        null,
        List.of(),
        bd(19));
  }

  private int invoice(LocalDate date, InvoiceItemDto... items) {
    return document(DocumentType.INVOICE, date, items);
  }

  private int document(int type, LocalDate date, InvoiceItemDto... items) {
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
                bd(19),
                null,
                null,
                null,
                null,
                type,
                List.of(items),
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

  private int issue(int id) {
    invoices.updateInvoiceStatus(id, new StatusChangeRequest(DocumentStatus.SENT, null, null));
    return id;
  }

  /** Issues the invoice and pays it in full, which makes it {@code PAID}. */
  private void pay(int id) {
    issue(id);
    BigDecimal gross = invoices.getInvoice(id).getBody().grossAmount();
    invoices.recordPayment(id, new PaymentRequest(LocalDate.of(2026, 6, 1), gross, "Bar", "", ""));
  }
}
