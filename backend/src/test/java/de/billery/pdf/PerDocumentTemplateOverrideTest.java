package de.billery.pdf;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceTemplate;
import de.billery.data.TestDatabaseConfig;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.InvoiceTemplateRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the PDF generator's per-document template override wins over the type-default
 * template picker, and that broken overrides fall back gracefully.
 */
class PerDocumentTemplateOverrideTest {

  private static InvoiceRepository invoiceRepository;
  private static InvoiceTemplateRepository templateRepository;
  private static CustomerRepository customerRepository;
  private static TemplateBasedPdfGenerator generator;

  private static final String VALID_BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"X\"},\"config\":{\"layout\":\"logo-left\"}}]";
  private static final String ALT_BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"ALTERNATE\"},\"config\":{\"layout\":\"logo-right\"}}]";

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    invoiceRepository = TestWiring.invoiceRepository();
    templateRepository = TestWiring.templateRepository();
    customerRepository = TestWiring.customerRepository();
    generator = TestWiring.pdfGenerator();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clearData() throws Exception {
    TestDatabaseConfig.clearData();
    seedTypeDefault();
  }

  @Test
  void overrideBeatsTypeDefault() {
    InvoiceTemplate typeDefault = templateRepository.findDefaultByDocumentTypeId(1);
    assertNotNull(typeDefault, "type-1 default template should have been seeded");

    InvoiceTemplate override = new InvoiceTemplate();
    override.setName("Override");
    override.setDocumentTypeId(1);
    override.setBlocks(ALT_BLOCKS);
    override.setDefault(false);
    override.setDocumentSpecific(true);
    templateRepository.save(override);
    assertNotEquals(override.getId(), typeDefault.getId());

    Invoice invoice = new Invoice();
    invoice.setCustomerId(seedCustomer());
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(override.getId());
    invoiceRepository.save(invoice);

    InvoiceTemplate chosen = generator.loadTemplateForInvoice(invoice);
    assertEquals(
        override.getId(),
        chosen.getId(),
        "override on the invoice should win over the type-default template picker");
  }

  @Test
  void missingOverrideFallsBackToTypeDefault() {
    InvoiceTemplate typeDefault = templateRepository.findDefaultByDocumentTypeId(1);
    assertNotNull(typeDefault);

    Invoice invoice = new Invoice();
    invoice.setCustomerId(seedCustomer());
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(99999);
    invoiceRepository.save(invoice);

    InvoiceTemplate chosen = generator.loadTemplateForInvoice(invoice);
    assertEquals(
        typeDefault.getId(),
        chosen.getId(),
        "an override id that does not exist should fall back to the type default");
  }

  @Test
  void overrideWithEmptyBlocksFallsBackToTypeDefault() {
    InvoiceTemplate typeDefault = templateRepository.findDefaultByDocumentTypeId(1);
    assertNotNull(typeDefault);

    InvoiceTemplate broken = new InvoiceTemplate();
    broken.setName("Broken");
    broken.setDocumentTypeId(1);
    broken.setBlocks("");
    broken.setDefault(false);
    templateRepository.save(broken);

    Invoice invoice = new Invoice();
    invoice.setCustomerId(seedCustomer());
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(broken.getId());
    invoiceRepository.save(invoice);

    InvoiceTemplate chosen = generator.loadTemplateForInvoice(invoice);
    assertEquals(
        typeDefault.getId(),
        chosen.getId(),
        "an override with no usable blocks should fall back to the type default");
  }

  @Test
  void noOverrideFallsBackToTypeDefault() {
    InvoiceTemplate typeDefault = templateRepository.findDefaultByDocumentTypeId(1);
    assertNotNull(typeDefault);

    Invoice invoice = new Invoice();
    invoice.setCustomerId(seedCustomer());
    invoice.setInvoiceDate("2024-01-01");
    invoice.setDocumentTypeId(1);
    invoice.setTemplateId(null);
    invoiceRepository.save(invoice);

    InvoiceTemplate chosen = generator.loadTemplateForInvoice(invoice);
    assertEquals(
        typeDefault.getId(),
        chosen.getId(),
        "an invoice with no override should resolve to the type default");
  }

  private int seedCustomer() {
    Customer customer = new Customer("Test", "Cust", "12345", "X", "Y", 1, "", "", "", "");
    return customerRepository.save(customer).getId();
  }

  private void seedTypeDefault() {
    InvoiceTemplate typeDefault = new InvoiceTemplate();
    typeDefault.setName("Type-1 Default");
    typeDefault.setDocumentTypeId(1);
    typeDefault.setBlocks(VALID_BLOCKS);
    typeDefault.setDefault(true);
    typeDefault.setDocumentSpecific(false);
    templateRepository.save(typeDefault);
  }
}
