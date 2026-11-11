package de.billery.service;

import static de.billery.TestMoney.assertAmount;
import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.TestDatabaseConfig;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Verifies the CUSTOM ("Freitext") invariant: a note row never carries a price. Any quantity /
 * unit-price / total-price values submitted for a CUSTOM row must be normalised to neutral values
 * (qty=1, price=0, total=0, unit="") by both the repository and the service layer.
 */
class CustomItemInvariantTest {

  private static InvoiceRepository invoiceRepository;
  private static CustomerRepository customerRepository;
  private static DocumentService documentService;
  private static int customerId;
  private static int invoiceId;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    invoiceRepository = TestWiring.invoiceRepository();
    customerRepository = TestWiring.customerRepository();
    documentService = TestWiring.documentService();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clearData() throws Exception {
    TestDatabaseConfig.clearData();
    Customer customer =
        new Customer("Test", "Customer", "12345", "Musterstadt", "Musterstraße", 1, "", "", "", "");
    customerId = customerRepository.save(customer).getId();
    Invoice invoice = new Invoice();
    invoice.setCustomerId(customerId);
    invoice.setInvoiceDate(LocalDate.now().toString());
    invoice.setTaxRate(bd(19.0));
    invoiceRepository.save(invoice);
    invoiceId = invoice.getId();
  }

  @Test
  void repositoryNormalisesCustomItemToZeroPrice() {
    InvoiceItem item =
        new InvoiceItem(
            0,
            invoiceId,
            InvoiceItem.ItemType.CUSTOM,
            0,
            bd(5.0), // quantity
            bd(99.0), // unitPrice — must be cleared
            bd(495.0)); // totalPrice — must be cleared
    item.setUnit("Stk");

    invoiceRepository.saveInvoiceItem(item);

    InvoiceItem loaded = invoiceRepository.findItemsByInvoiceId(invoiceId).get(0);
    assertEquals(0.0, loaded.getItemId(), 0.001, "itemId must be 0 for CUSTOM");
    assertAmount(1.0, loaded.getQuantity(), 0.001, "quantity must be normalised to 1 for CUSTOM");
    assertAmount(0.0, loaded.getUnitPrice(), 0.001, "unitPrice must be 0 for CUSTOM");
    assertAmount(0.0, loaded.getTotalPrice(), 0.001, "totalPrice must be 0 for CUSTOM");
    assertEquals("", loaded.getUnit(), "unit must be empty for CUSTOM");
  }

  @Test
  void serviceNormalisesCustomItemToZeroPrice() {
    InvoiceItemDto custom = customWithBogusPrice("Anfahrt", "50 km Anreise");

    Invoice created =
        documentService.createDocument(
            baseInvoiceDto(java.util.List.of(custom, article("Standard", 1, 50.0))));
    int createdInvoiceId = created.getId();

    List<InvoiceItem> items = invoiceRepository.findItemsByInvoiceId(createdInvoiceId);
    assertEquals(2, items.size(), "one custom note + one priced article expected");

    InvoiceItem noteRow = findByName(items, "Anfahrt");
    assertEquals(0.0, noteRow.getItemId(), 0.001);
    assertAmount(1.0, noteRow.getQuantity(), 0.001);
    assertAmount(0.0, noteRow.getUnitPrice(), 0.001);
    assertAmount(0.0, noteRow.getTotalPrice(), 0.001);
    assertEquals("", noteRow.getUnit());

    InvoiceItem priced = findByName(items, "Standard");
    assertAmount(50.0, priced.getTotalPrice(), 0.001, "priced article must keep its price");
  }

  @Test
  void cloneNormalisesCustomItemToZeroPrice() {
    InvoiceItemDto parent = article("Service", 1, 100.0);
    InvoiceItemDto note = customWithBogusPrice("Hinweis", "Bitte vor Ort abstimmen");
    Invoice source =
        documentService.createDocument(
            baseInvoiceDto(java.util.List.of(withChildren(parent, java.util.List.of(note)))));
    int sourceId = source.getId();

    Invoice clone = documentService.cloneDocument(sourceId, new HashMap<>());
    int cloneId = clone.getId();
    List<InvoiceItem> cloned = invoiceRepository.findItemsByInvoiceId(cloneId);
    assertEquals(1, cloned.size());
    InvoiceItem clonedRoot = cloned.get(0);
    assertAmount(100.0, clonedRoot.getTotalPrice(), 0.001, "priced parent keeps its price");
    assertEquals(1, clonedRoot.getChildren().size(), "cloned parent should still have a child");
    InvoiceItem clonedNote = clonedRoot.getChildren().get(0);
    assertAmount(0.0, clonedNote.getUnitPrice(), 0.001, "cloned note keeps zero price");
    assertAmount(0.0, clonedNote.getTotalPrice(), 0.001);
    assertEquals("", clonedNote.getUnit());
  }

  @Test
  void customSubItemWithBogusPriceIsNormalised() {
    InvoiceItemDto parent = article("Arbeit", 1, 80.0);
    InvoiceItemDto note = customWithBogusPrice("Anfahrt", "50 km Anreise, pauschal");

    Invoice created =
        documentService.createDocument(
            baseInvoiceDto(java.util.List.of(withChildren(parent, java.util.List.of(note)))));
    int createdInvoiceId = created.getId();

    List<InvoiceItem> loaded = invoiceRepository.findItemsByInvoiceId(createdInvoiceId);
    assertEquals(1, loaded.size(), "one priced root expected");
    InvoiceItem loadedParent = loaded.get(0);
    assertAmount(80.0, loadedParent.getTotalPrice(), 0.001);
    assertEquals(1, loadedParent.getChildren().size(), "sub-item must be present");
    InvoiceItem loadedNote = loadedParent.getChildren().get(0);
    assertAmount(0.0, loadedNote.getUnitPrice(), 0.001);
    assertAmount(0.0, loadedNote.getTotalPrice(), 0.001);
    assertEquals("Anfahrt", loadedNote.getItemName());
  }

  private static de.billery.api.dto.InvoiceDto baseInvoiceDto(List<InvoiceItemDto> items) {
    return new de.billery.api.dto.InvoiceDto(
        null,
        customerId,
        null,
        null,
        1,
        LocalDate.now(),
        null,
        null,
        new BigDecimal("19.0"),
        null,
        null,
        null,
        null,
        1,
        items,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static InvoiceItemDto article(String name, double qty, double price) {
    return new InvoiceItemDto(
        null,
        null,
        "article",
        1,
        name,
        null,
        "Stk",
        bd(qty),
        BigDecimal.valueOf(price),
        BigDecimal.valueOf(qty * price),
        null,
        null,
        null);
  }

  private static InvoiceItemDto customWithBogusPrice(String name, String description) {
    return new InvoiceItemDto(
        null,
        null,
        "custom",
        0,
        name,
        description,
        "Stk",
        bd(7.0), // bogus
        BigDecimal.valueOf(42.0), // bogus
        BigDecimal.valueOf(294.0), // bogus
        null,
        null,
        null);
  }

  private static InvoiceItemDto withChildren(InvoiceItemDto parent, List<InvoiceItemDto> children) {
    return new InvoiceItemDto(
        parent.id(),
        parent.invoiceId(),
        parent.itemType(),
        parent.itemId(),
        parent.itemName(),
        parent.itemDescription(),
        parent.unit(),
        parent.quantity(),
        parent.unitPrice(),
        parent.totalPrice(),
        parent.parentItemId(),
        children,
        null);
  }

  private static InvoiceItem findByName(List<InvoiceItem> items, String name) {
    return items.stream()
        .filter(i -> name.equals(i.getItemName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("item not found: " + name));
  }
}
