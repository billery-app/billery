package de.billery.service;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.controller.InvoiceTemplateController.CloneForDocumentRequest;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceTemplate;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import de.billery.pdf.TemplateBasedPdfGenerator;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.InvoiceTemplateRepository;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the per-document template override is persisted on save/update and inherited on clone.
 */
class DocumentServiceTemplateIdTest {

  private static InvoiceRepository invoiceRepository;
  private static InvoiceTemplateRepository templateRepository;
  private static CustomerRepository customerRepository;
  private static DocumentService documentService;
  private static int customerId;

  private static final String DEFAULT_BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"DefaultHeader\"},\"config\":{\"layout\":\"logo-left\"}}]";
  private static final String OVERRIDE_BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"OVERRIDE_HEADER\"},\"config\":{\"layout\":\"logo-left\"}}]";

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    invoiceRepository = TestWiring.invoiceRepository();
    templateRepository = TestWiring.templateRepository();
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
    // Rendering requires configured company data (there is no placeholder owner).
    TestWiring.ownerRepository()
        .save(
            new Owner(
                0, "Max", "Muster", "Firma", "GmbH", "12345", "Ort", "Weg", 1, "", "", "", "", "",
                false));
  }

  @Test
  void cloneInheritsTemplateIdFromSource() {
    InvoiceTemplate override = new InvoiceTemplate();
    override.setName("Override");
    override.setDocumentTypeId(1);
    override.setBlocks(OVERRIDE_BLOCKS);
    override.setDefault(false);
    override.setDocumentSpecific(true);
    templateRepository.save(override);

    Invoice source = new Invoice();
    source.setCustomerId(customerId);
    source.setInvoiceDate("2024-01-01");
    source.setDocumentTypeId(1);
    source.setTemplateId(override.getId());
    invoiceRepository.save(source);

    Invoice clone = documentService.cloneDocument(source.getId(), new HashMap<>());
    assertNotNull(clone);
    assertEquals(
        override.getId(),
        clone.getTemplateId(),
        "the clone should inherit the source's per-document template override");

    Invoice reloaded = invoiceRepository.findById(clone.getId());
    assertEquals(
        override.getId(),
        reloaded.getTemplateId(),
        "the inherited template_id should be persisted on the clone row");
  }

  @Test
  void cloneOfInvoiceWithoutOverrideHasNullTemplateId() {
    Invoice source = new Invoice();
    source.setCustomerId(customerId);
    source.setInvoiceDate("2024-01-01");
    source.setDocumentTypeId(1);
    source.setTemplateId(null);
    invoiceRepository.save(source);

    Invoice clone = documentService.cloneDocument(source.getId(), new HashMap<>());
    assertNotNull(clone);
    assertNull(clone.getTemplateId(), "a source with no override clones to no override");
  }

  /**
   * Simulates the "Customize layout for this document…" flow: the controller clones the source
   * template, sets the document's {@code template_id} to the clone's id, and regenerates the
   * snapshot. The test verifies the end state matches what the controller promises — {@code
   * template_id} persisted and the new {@code rendered_html} contains the override's content.
   */
  @Test
  void customizeFlowPersistsAssociationAndRegeneratesSnapshot() throws Exception {
    InvoiceTemplate typeDefault = new InvoiceTemplate();
    typeDefault.setName("Type-1 Default");
    typeDefault.setDocumentTypeId(1);
    typeDefault.setBlocks(DEFAULT_BLOCKS);
    typeDefault.setDefault(true);
    typeDefault.setDocumentSpecific(false);
    templateRepository.save(typeDefault);

    // User's existing document with no per-document override yet.
    Invoice document = new Invoice();
    document.setCustomerId(customerId);
    document.setInvoiceDate("2024-01-01");
    document.setDocumentTypeId(1);
    document.setTemplateId(null);
    invoiceRepository.save(document);

    // Step 1: clone the type default through the real endpoint
    // (POST /api/templates/from-document/{id}). The clone starts as a
    // copy of the source's content.
    InvoiceTemplate clone = cloneViaController(typeDefault.getId(), document.getId());
    assertNotNull(clone);
    assertTrue(clone.getBlocks().contains("DefaultHeader"));
    assertTrue(clone.isDocumentSpecific(), "the clone is marked document-specific");
    assertEquals(0, clone.getIsDefault(), "the clone is not the type default");

    // Step 2: simulate the user editing the clone via the template
    // editor (a separate PUT /api/templates/{id} request, which is
    // already covered by InvoiceTemplateRepositoryTest). The new
    // content has a distinguishing marker.
    clone.setBlocks(OVERRIDE_BLOCKS);
    templateRepository.update(clone);

    // Step 3: persist the association. Without this, the document's
    // template_id stays null and the download endpoint's snapshot
    // branch would keep returning the stale default layout.
    document.setTemplateId(clone.getId());
    invoiceRepository.update(document);

    // Step 4: regenerate the snapshot using the new override. This is
    // what renderSnapshotForDocument does in the controller.
    TemplateBasedPdfGenerator pdfGenerator = TestWiring.pdfGenerator();
    String newSnapshot = pdfGenerator.generateInvoiceHtml(document);
    invoiceRepository.updateRenderedHtml(document.getId(), newSnapshot);

    // The document now has the override's template_id persisted and a
    // fresh snapshot that contains the override's distinguishing
    // content. The download endpoint will see template_id != null and
    // use fresh generation; even if it didn't, this snapshot would
    // already show the override.
    Invoice reloaded = invoiceRepository.findById(document.getId());
    assertEquals(
        clone.getId(),
        reloaded.getTemplateId(),
        "the per-document override should be persisted on the document");
    assertTrue(
        reloaded.getRenderedHtml().contains("OVERRIDE_HEADER"),
        "the regenerated snapshot should be rendered from the override template");
  }

  /**
   * Cloning a type default does not modify the original. The type default stays as the default for
   * other documents that don't have a per-document override; the clone is a fresh row marked {@code
   * is_document_specific}.
   */
  @Test
  void cloneLeavesTypeDefaultUntouched() {
    InvoiceTemplate typeDefault = new InvoiceTemplate();
    typeDefault.setName("Type-1 Default");
    typeDefault.setDocumentTypeId(1);
    typeDefault.setBlocks(DEFAULT_BLOCKS);
    typeDefault.setDefault(true);
    typeDefault.setDocumentSpecific(false);
    templateRepository.save(typeDefault);

    Invoice document = new Invoice();
    document.setCustomerId(customerId);
    document.setInvoiceDate("2024-01-01");
    document.setDocumentTypeId(1);
    invoiceRepository.save(document);

    InvoiceTemplate clone = cloneViaController(typeDefault.getId(), document.getId());
    assertNotNull(clone);
    assertNotEquals(typeDefault.getId(), clone.getId(), "clone has a fresh id");
    assertTrue(clone.isDocumentSpecific());
    assertEquals(0, clone.getIsDefault(), "the clone is not the type default");
    // The original default keeps its identity and defaults.
    InvoiceTemplate reloadedDefault = templateRepository.findById(typeDefault.getId());
    assertEquals(1, reloadedDefault.getIsDefault(), "the type default stays the default");
    assertFalse(
        reloadedDefault.isDocumentSpecific(), "the default is not marked document-specific");
    assertEquals(DEFAULT_BLOCKS, reloadedDefault.getBlocks(), "the default's blocks are unchanged");
  }

  /**
   * Clones {@code sourceId} for {@code documentId} through {@code
   * InvoiceTemplateController.cloneForDocument}, the code path the SPA uses, and returns the stored
   * clone.
   */
  private static InvoiceTemplate cloneViaController(int sourceId, int documentId) {
    Map<String, Object> body =
        TestWiring.templateController()
            .cloneForDocument(documentId, new CloneForDocumentRequest(sourceId, null))
            .getBody();
    assertNotNull(body, "the clone endpoint should answer 200 with the new id");
    return templateRepository.findById((Integer) body.get("id"));
  }
}
