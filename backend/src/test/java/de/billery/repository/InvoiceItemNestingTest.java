package de.billery.repository;

import static de.billery.TestMoney.bd;
import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.Customer;
import de.billery.data.DatabaseException;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.TestDatabaseConfig;
import java.util.List;
import org.junit.jupiter.api.*;

/**
 * Tests the tree-shaping behaviour of {@link InvoiceRepository#findItemsByInvoiceId(int)}, which
 * internally uses {@code nestInvoiceItems} to group child rows under their parent.
 */
class InvoiceItemNestingTest {

  private static InvoiceRepository repository;
  private static CustomerRepository customerRepository;
  private static int customerId;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    repository = TestWiring.invoiceRepository();
    customerRepository = TestWiring.customerRepository();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clearData() throws Exception {
    TestDatabaseConfig.clearData();
    customerId = insertCustomer();
  }

  @Test
  void singleRootHasNoChildren() {
    int invoiceId = insertInvoice();
    InvoiceItem root = newRoot(invoiceId, 1, 2.0, 50.0);
    repository.saveInvoiceItem(root);

    List<InvoiceItem> items = repository.findItemsByInvoiceId(invoiceId);

    assertEquals(1, items.size(), "single root should yield one root");
    assertNull(items.get(0).getChildren(), "root with no children should have null children list");
    assertEquals(0, items.get(0).getParentItemId());
  }

  @Test
  void childIsAttachedToItsParent() {
    int invoiceId = insertInvoice();
    InvoiceItem root = newRoot(invoiceId, 1, 1.0, 100.0);
    repository.saveInvoiceItem(root);
    InvoiceItem child = newChild(invoiceId, root.getId(), "service", 1, 1.0, 25.0);
    repository.saveInvoiceItem(child);

    List<InvoiceItem> items = repository.findItemsByInvoiceId(invoiceId);

    assertEquals(1, items.size(), "only the root should be returned at top level");
    InvoiceItem loadedRoot = items.get(0);
    assertNotNull(loadedRoot.getChildren());
    assertEquals(1, loadedRoot.getChildren().size());
    InvoiceItem loadedChild = loadedRoot.getChildren().get(0);
    assertEquals(child.getId(), loadedChild.getId());
    assertEquals(root.getId(), loadedChild.getParentItemId());
  }

  @Test
  void twoLevelsOfNestingArePreserved() {
    int invoiceId = insertInvoice();
    InvoiceItem root = newRoot(invoiceId, 1, 1.0, 100.0);
    repository.saveInvoiceItem(root);
    InvoiceItem mid = newChild(invoiceId, root.getId(), "custom", 0, 1.0, 10.0);
    repository.saveInvoiceItem(mid);
    InvoiceItem leaf = newChild(invoiceId, mid.getId(), "custom", 0, 1.0, 5.0);
    repository.saveInvoiceItem(leaf);

    List<InvoiceItem> items = repository.findItemsByInvoiceId(invoiceId);

    assertEquals(1, items.size(), "only the root at top level");
    InvoiceItem loadedRoot = items.get(0);
    assertEquals(1, loadedRoot.getChildren().size());
    InvoiceItem loadedMid = loadedRoot.getChildren().get(0);
    assertEquals(mid.getId(), loadedMid.getId());
    assertEquals(1, loadedMid.getChildren().size());
    assertEquals(leaf.getId(), loadedMid.getChildren().get(0).getId());
  }

  @Test
  void mixedRootsAndChildrenReturnRootsOnly() {
    int invoiceId = insertInvoice();
    InvoiceItem root1 = newRoot(invoiceId, 1, 1.0, 10.0);
    repository.saveInvoiceItem(root1);
    InvoiceItem root2 = newRoot(invoiceId, 2, 2.0, 20.0);
    repository.saveInvoiceItem(root2);
    InvoiceItem childOf1 = newChild(invoiceId, root1.getId(), "custom", 0, 1.0, 1.0);
    repository.saveInvoiceItem(childOf1);
    InvoiceItem childOf2 = newChild(invoiceId, root2.getId(), "custom", 0, 1.0, 2.0);
    repository.saveInvoiceItem(childOf2);

    List<InvoiceItem> items = repository.findItemsByInvoiceId(invoiceId);

    assertEquals(2, items.size());
    for (InvoiceItem root : items) {
      assertEquals(1, root.getChildren().size(), "each root should have one child");
    }
  }

  @Test
  void savingSubItemWithMissingParentFailsAndLeavesTreeIntact() {
    int invoiceId = insertInvoice();
    InvoiceItem root = newRoot(invoiceId, 1, 1.0, 10.0);
    repository.saveInvoiceItem(root);
    InvoiceItem orphan = newChild(invoiceId, 9999, "custom", 0, 1.0, 1.0);

    // The parent_item_id FK rejects the row; the repository must report it, not swallow it.
    assertThrows(DatabaseException.class, () -> repository.saveInvoiceItem(orphan));

    List<InvoiceItem> items = repository.findItemsByInvoiceId(invoiceId);
    assertEquals(1, items.size());
    assertEquals(root.getId(), items.get(0).getId());
  }

  @Test
  void deletingParentCascadesToChildren() {
    int invoiceId = insertInvoice();
    InvoiceItem root = newRoot(invoiceId, 1, 1.0, 10.0);
    repository.saveInvoiceItem(root);
    InvoiceItem child = newChild(invoiceId, root.getId(), "custom", 0, 1.0, 1.0);
    repository.saveInvoiceItem(child);

    TestWiring.jdbcClient()
        .sql("DELETE FROM invoice_item WHERE id = :id")
        .param("id", root.getId())
        .update();

    List<InvoiceItem> items = repository.findItemsByInvoiceId(invoiceId);
    assertEquals(0, items.size(), "ON DELETE CASCADE should remove the child too");
  }

  private int insertCustomer() {
    Customer customer =
        new Customer("Test", "Customer", "12345", "Musterstadt", "Musterstraße", 1, "", "", "", "");
    Customer saved = customerRepository.save(customer);
    return saved.getId();
  }

  private int insertInvoice() {
    Invoice invoice = new Invoice();
    invoice.setCustomerId(customerId);
    invoice.setInvoiceDate("2026-01-01");
    invoice.setTaxRate(bd(19.0));
    repository.save(invoice);
    return invoice.getId();
  }

  private InvoiceItem newRoot(int invoiceId, int itemId, double qty, double price) {
    InvoiceItem item =
        new InvoiceItem(
            0,
            invoiceId,
            InvoiceItem.ItemType.ARTICLE,
            itemId,
            bd(qty),
            bd(price),
            bd(qty * price));
    item.setParentItemId(0);
    return item;
  }

  private InvoiceItem newChild(
      int invoiceId, int parentId, String type, int itemId, double qty, double price) {
    InvoiceItem.ItemType itemType = InvoiceItem.ItemType.fromValue(type);
    InvoiceItem item =
        new InvoiceItem(0, invoiceId, itemType, itemId, bd(qty), bd(price), bd(qty * price));
    item.setParentItemId(parentId);
    return item;
  }
}
