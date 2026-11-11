package de.billery.service;

import static de.billery.TestMoney.assertAmount;
import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.TestDatabaseConfig;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Verifies that {@link DocumentService#cloneDocument(int, Map)} preserves the sub-item tree of the
 * source document, including each child's {@code parent_item_id} reference to its newly assigned
 * parent id.
 */
class DocumentServiceCloneTest {

  private static InvoiceRepository invoiceRepository;
  private static CustomerRepository customerRepository;
  private static DocumentService documentService;
  private static int customerId;

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
  }

  @Test
  void cloneCopiesSubItemsAndPreservesTree() {
    Invoice source = saveInvoice();
    InvoiceItem root1 = saveItem(source.getId(), 0, "article", 1, 2.0, 50.0);
    InvoiceItem child = saveItem(source.getId(), root1.getId(), "service", 1, 1.0, 25.0);
    saveItem(source.getId(), 0, "custom", 0, 1.0, 10.0);

    Invoice clone = documentService.cloneDocument(source.getId(), new HashMap<>());
    assertNotNull(clone, "clone should exist");
    assertNotEquals(source.getId(), clone.getId(), "clone should have a fresh id");

    List<InvoiceItem> cloneItems = invoiceRepository.findItemsByInvoiceId(clone.getId());
    assertEquals(2, cloneItems.size(), "only the two roots should be at top level");
    for (InvoiceItem root : cloneItems) {
      assertEquals(0, root.getParentItemId(), "roots have no parent");
    }
    InvoiceItem clonedRootWithChild = findRootWithChildren(cloneItems);
    assertNotNull(clonedRootWithChild, "the root that had a child should still exist");
    assertEquals(
        1, clonedRootWithChild.getChildren().size(), "child should be cloned with the parent");
    InvoiceItem clonedChild = clonedRootWithChild.getChildren().get(0);
    assertEquals(child.getItemName(), clonedChild.getItemName());
    assertAmount(child.getTotalPrice(), clonedChild.getTotalPrice(), 0.001);
    assertEquals(
        clonedRootWithChild.getId(),
        clonedChild.getParentItemId(),
        "child's parent_item_id should point at the new clone root, not the original");
  }

  @Test
  void cloneCopiesTwoLevelDeepSubtree() {
    Invoice source = saveInvoice();
    InvoiceItem root = saveItem(source.getId(), 0, "article", 1, 1.0, 100.0);
    InvoiceItem mid = saveItem(source.getId(), root.getId(), "custom", 0, 1.0, 10.0);
    saveItem(source.getId(), mid.getId(), "custom", 0, 1.0, 5.0);

    Invoice clone = documentService.cloneDocument(source.getId(), new HashMap<>());
    List<InvoiceItem> cloneItems = invoiceRepository.findItemsByInvoiceId(clone.getId());

    assertEquals(1, cloneItems.size());
    InvoiceItem clonedRoot = cloneItems.get(0);
    assertEquals(1, clonedRoot.getChildren().size());
    InvoiceItem clonedMid = clonedRoot.getChildren().get(0);
    assertEquals(1, clonedMid.getChildren().size());
    assertEquals(clonedMid.getId(), clonedMid.getChildren().get(0).getParentItemId());
  }

  @Test
  void cloneOfInvoiceWithoutSubItemsStillWorks() {
    Invoice source = saveInvoice();
    saveItem(source.getId(), 0, "article", 1, 1.0, 50.0);

    Invoice clone = documentService.cloneDocument(source.getId(), new HashMap<>());
    List<InvoiceItem> cloneItems = invoiceRepository.findItemsByInvoiceId(clone.getId());

    assertEquals(1, cloneItems.size());
    assertNull(cloneItems.get(0).getChildren());
  }

  private Invoice saveInvoice() {
    Invoice invoice = new Invoice();
    invoice.setCustomerId(customerId);
    invoice.setInvoiceDate(LocalDate.now().toString());
    invoice.setTaxRate(bd(19.0));
    invoiceRepository.save(invoice);
    return invoice;
  }

  private InvoiceItem saveItem(
      int invoiceId, int parentId, String type, int itemId, double qty, double price) {
    InvoiceItem item =
        new InvoiceItem(
            0,
            invoiceId,
            InvoiceItem.ItemType.fromValue(type),
            itemId,
            bd(qty),
            bd(price),
            bd(qty * price));
    item.setParentItemId(parentId);
    invoiceRepository.saveInvoiceItem(item);
    return item;
  }

  private InvoiceItem findRootWithChildren(List<InvoiceItem> roots) {
    for (InvoiceItem root : roots) {
      if (root.getChildren() != null && !root.getChildren().isEmpty()) {
        return root;
      }
    }
    return null;
  }
}
