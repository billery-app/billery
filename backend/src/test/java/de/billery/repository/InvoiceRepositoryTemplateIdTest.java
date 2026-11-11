package de.billery.repository;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceTemplate;
import de.billery.data.TestDatabaseConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies that {@code invoice.template_id} survives save/update/find round-trips. */
class InvoiceRepositoryTemplateIdTest {

  private static InvoiceRepository invoiceRepository;
  private static InvoiceTemplateRepository templateRepository;
  private static CustomerRepository customerRepository;
  private static int customerId;

  private static final String BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"X\"},\"config\":{\"layout\":\"logo-left\"}}]";

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    invoiceRepository = TestWiring.invoiceRepository();
    templateRepository = TestWiring.templateRepository();
    customerRepository = TestWiring.customerRepository();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clearData() throws Exception {
    TestDatabaseConfig.clearData();
    Customer customer = new Customer("T", "C", "12345", "X", "Y", 1, "", "", "", "");
    customerId = customerRepository.save(customer).getId();
  }

  @Test
  void savePersistsTemplateIdAndFindReturnsIt() {
    InvoiceTemplate template = new InvoiceTemplate();
    template.setName("T");
    template.setDocumentTypeId(1);
    template.setBlocks(BLOCKS);
    template.setDocumentSpecific(true);
    templateRepository.save(template);

    Invoice invoice = new Invoice();
    invoice.setCustomerId(customerId);
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(template.getId());
    invoiceRepository.save(invoice);

    Invoice reloaded = invoiceRepository.findById(invoice.getId());
    assertEquals(
        template.getId(),
        reloaded.getTemplateId(),
        "template_id should round-trip on save + findById");
  }

  @Test
  void saveWithNullTemplateIdStaysNull() {
    Invoice invoice = new Invoice();
    invoice.setCustomerId(customerId);
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(null);
    invoiceRepository.save(invoice);

    Invoice reloaded = invoiceRepository.findById(invoice.getId());
    assertNull(reloaded.getTemplateId(), "null template_id should stay null after round-trip");
  }

  @Test
  void updateChangesTemplateId() {
    InvoiceTemplate first = new InvoiceTemplate();
    first.setName("First");
    first.setDocumentTypeId(1);
    first.setBlocks(BLOCKS);
    first.setDocumentSpecific(true);
    templateRepository.save(first);

    InvoiceTemplate second = new InvoiceTemplate();
    second.setName("Second");
    second.setDocumentTypeId(1);
    second.setBlocks(BLOCKS);
    second.setDocumentSpecific(true);
    templateRepository.save(second);

    Invoice invoice = new Invoice();
    invoice.setCustomerId(customerId);
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(first.getId());
    invoiceRepository.save(invoice);

    invoice.setTemplateId(second.getId());
    invoiceRepository.update(invoice);

    Invoice reloaded = invoiceRepository.findById(invoice.getId());
    assertEquals(second.getId(), reloaded.getTemplateId());
  }

  @Test
  void updateCanClearTemplateIdBackToNull() {
    InvoiceTemplate template = new InvoiceTemplate();
    template.setName("T");
    template.setDocumentTypeId(1);
    template.setBlocks(BLOCKS);
    template.setDocumentSpecific(true);
    templateRepository.save(template);

    Invoice invoice = new Invoice();
    invoice.setCustomerId(customerId);
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(template.getId());
    invoiceRepository.save(invoice);

    invoice.setTemplateId(null);
    invoiceRepository.update(invoice);

    Invoice reloaded = invoiceRepository.findById(invoice.getId());
    assertNull(reloaded.getTemplateId(), "updating back to null should clear the override");
  }

  @Test
  void nullTemplateIdForTemplateClearsAllReferencingInvoices() {
    InvoiceTemplate template = new InvoiceTemplate();
    template.setName("T");
    template.setDocumentTypeId(1);
    template.setBlocks(BLOCKS);
    template.setDocumentSpecific(true);
    templateRepository.save(template);

    Invoice first = new Invoice();
    first.setCustomerId(customerId);
    first.setInvoiceDate("2024-01-01");
    first.setDocumentTypeId(1);
    first.setTemplateId(template.getId());
    invoiceRepository.save(first);

    Invoice second = new Invoice();
    second.setCustomerId(customerId);
    second.setInvoiceDate("2024-01-02");
    second.setDocumentTypeId(1);
    second.setTemplateId(template.getId());
    invoiceRepository.save(second);

    // A third invoice that does NOT reference the template — must stay
    // untouched by the bulk clear.
    Invoice unrelated = new Invoice();
    unrelated.setCustomerId(customerId);
    unrelated.setInvoiceDate("2024-01-03");
    unrelated.setDocumentTypeId(1);
    unrelated.setTemplateId(null);
    invoiceRepository.save(unrelated);

    int affected = invoiceRepository.nullTemplateIdForTemplate(template.getId());
    assertEquals(2, affected, "two invoices should have been cleared");
    assertNull(
        invoiceRepository.findById(first.getId()).getTemplateId(),
        "first invoice's template_id should be null after the bulk clear");
    assertNull(
        invoiceRepository.findById(second.getId()).getTemplateId(),
        "second invoice's template_id should be null after the bulk clear");
    assertNull(
        invoiceRepository.findById(unrelated.getId()).getTemplateId(),
        "unrelated invoice should remain unchanged");
  }

  @Test
  void nullTemplateIdForTemplateReturnsZeroWhenNothingReferencesTheTemplate() {
    InvoiceTemplate template = new InvoiceTemplate();
    template.setName("T");
    template.setDocumentTypeId(1);
    template.setBlocks(BLOCKS);
    templateRepository.save(template);

    int affected = invoiceRepository.nullTemplateIdForTemplate(template.getId());
    assertEquals(0, affected, "no references → no rows updated");
  }
}
