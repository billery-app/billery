package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.controller.InvoiceTemplateController.CloneForDocumentRequest;
import de.billery.api.dto.InvoiceTemplateDto;
import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceTemplate;
import de.billery.data.TestDatabaseConfig;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.InvoiceTemplateRepository;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/**
 * Verifies the per-document override lifecycle in the controller: clone-on-save with editor
 * content, the post-clone side effects (template_id persisted, snapshot regenerated), and the
 * cleanup of orphan / dangling rows on delete.
 */
class InvoiceTemplateControllerTest {

  private static InvoiceTemplateController templateController;
  private static InvoiceController invoiceController;
  private static InvoiceTemplateRepository templateRepository;
  private static InvoiceRepository invoiceRepository;
  private static CustomerRepository customerRepository;
  private static int customerId;

  private static final String TYPE_DEFAULT_BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"DefaultHeader\"},\"config\":{\"layout\":\"logo-left\"}}]";
  private static final String EDITOR_DRAFT_BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"EditorHeader\"},\"config\":{\"layout\":\"logo-left\"}}]";

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    templateController = TestWiring.templateController();
    invoiceController = TestWiring.invoiceController();
    templateRepository = TestWiring.templateRepository();
    invoiceRepository = TestWiring.invoiceRepository();
    customerRepository = TestWiring.customerRepository();
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

  private InvoiceTemplate seedTypeDefault() {
    InvoiceTemplate typeDefault = new InvoiceTemplate();
    typeDefault.setName("Type-1 Default");
    typeDefault.setDocumentTypeId(1);
    typeDefault.setBlocks(TYPE_DEFAULT_BLOCKS);
    typeDefault.setDefault(true);
    typeDefault.setDocumentSpecific(false);
    templateRepository.save(typeDefault);
    return typeDefault;
  }

  private Invoice seedDocument(Integer templateId) {
    Invoice document = new Invoice();
    document.setCustomerId(customerId);
    document.setInvoiceDate("2024-01-01");
    document.setDocumentTypeId(1);
    document.setTemplateId(templateId);
    invoiceRepository.save(document);
    return document;
  }

  // --- previewPdf (editor's exact preview) ---

  private static InvoiceTemplateDto draftWithBlocks(String blocks) {
    return new InvoiceTemplateDto(
        null, "Entwurf", 1, null, "", null, null, false, 10, 10, 10, 10, null, null, blocks, false);
  }

  private static void seedOwner() {
    TestWiring.ownerRepository()
        .save(
            new de.billery.data.Owner(
                0,
                "Max",
                "Muster",
                "Muster GmbH",
                "GmbH",
                "12345",
                "Stadt",
                "Weg",
                1,
                "",
                "",
                "",
                "",
                "",
                false));
  }

  @Test
  void previewRendersAnUnsavedDraftWithSampleDataToPdf() throws Exception {
    seedOwner();
    String blocks =
        "[{\"id\":\"m\",\"type\":\"invoice-meta\",\"data\":{\"invoiceId\":\"{{rechnungsnummer}}\"},"
            + "\"config\":{}},{\"id\":\"a\",\"type\":\"address\",\"data\":{\"role\":\"receiver\","
            + "\"name\":\"{{kunde}}\"},\"config\":{}},{\"id\":\"l\",\"type\":\"line-items\",\"data\":{},"
            + "\"config\":{}}]";

    ResponseEntity<byte[]> response =
        templateController.previewPdf(
            new InvoiceTemplateController.PreviewRequest(draftWithBlocks(blocks), null));

    assertEquals(200, response.getStatusCode().value());
    assertTrue(new String(response.getBody(), 0, 4).startsWith("%PDF"));
    // The renderer's default stylesheet must load (restricted user agent): otherwise the base CSS
    // is printed as text and the layout is lost.
    String text =
        new org.openpdf.text.pdf.parser.PdfTextExtractor(
                new org.openpdf.text.pdf.PdfReader(response.getBody()))
            .getTextFromPage(1);
    assertFalse(text.contains(".invoice-box"), "CSS printed as text: " + text);
    assertTrue(text.contains("MUSTER-0001"), text);
    String html =
        TestWiring.pdfGenerator()
            .generateTemplatePreviewHtml(templateController_convert(draftWithBlocks(blocks)), null);
    assertTrue(html.contains("MUSTER-0001"), "sample number");
    assertTrue(html.contains("Anna Müller"), "sample customer");
    assertTrue(html.contains("USt."), "two VAT rates in the sample add the VAT column");
    assertEquals(
        0,
        templateRepository.findAll().stream().filter(t -> "Entwurf".equals(t.getName())).count(),
        "nothing is stored");
  }

  @Test
  void previewWithoutBlocksOrTemplateDataIsRejected() throws Exception {
    seedOwner();
    assertEquals(
        400,
        templateController
            .previewPdf(new InvoiceTemplateController.PreviewRequest(null, null))
            .getStatusCode()
            .value());
    assertThrows(
        de.billery.pdf.MissingConfigurationException.class,
        () ->
            templateController.previewPdf(
                new InvoiceTemplateController.PreviewRequest(draftWithBlocks("[]"), null)));
  }

  private static InvoiceTemplate templateController_convert(InvoiceTemplateDto dto) {
    InvoiceTemplate template = new InvoiceTemplate();
    template.setDocumentTypeId(dto.documentTypeId());
    template.setBlocks(dto.blocks());
    template.setCssContent(dto.cssContent());
    template.setMarginTopMm(10);
    template.setMarginBottomMm(10);
    template.setMarginLeftMm(10);
    template.setMarginRightMm(10);
    return template;
  }

  // --- cloneForDocument (controller) ---

  @Test
  void cloneForDocumentWithEditorDraftPersistsEditsAndTemplateId() {
    InvoiceTemplate typeDefault = seedTypeDefault();
    Invoice document = seedDocument(null);

    InvoiceTemplateDto draft =
        new InvoiceTemplateDto(
            null, // id is ignored — the new row always gets a fresh id
            "Mein Layout",
            1,
            "<html>draft</html>",
            "body { color: red; }",
            null,
            null,
            false,
            12,
            12,
            12,
            12,
            null,
            null,
            EDITOR_DRAFT_BLOCKS,
            null);
    CloneForDocumentRequest request = new CloneForDocumentRequest(typeDefault.getId(), draft);

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> response =
        (ResponseEntity<Map<String, Object>>)
            templateController.cloneForDocument(document.getId(), request);
    assertEquals(200, response.getStatusCode().value());
    Map<String, Object> body = response.getBody();
    assertNotNull(body);
    Integer newId = (Integer) body.get("id");
    assertNotNull(newId);
    assertEquals("Mein Layout", body.get("name"));

    InvoiceTemplate persisted = templateRepository.findById(newId);
    assertNotNull(persisted, "the new clone should be persisted");
    assertTrue(persisted.isDocumentSpecific(), "the new row is marked document-specific");
    assertEquals(0, persisted.getIsDefault(), "the new row is not the type default");
    assertEquals(
        EDITOR_DRAFT_BLOCKS, persisted.getBlocks(), "the editor's draft blocks are applied");
    assertEquals(12, persisted.getMarginTopMm(), "the editor's margin override is applied");
    assertEquals("Mein Layout", persisted.getName(), "the editor's name is applied");
    assertEquals("body { color: red; }", persisted.getCssContent(), "the editor's CSS is applied");
    assertEquals("<html>draft</html>", persisted.getHtmlContent(), "the editor's HTML is applied");

    Invoice reloaded = invoiceRepository.findById(document.getId());
    assertEquals(
        newId,
        reloaded.getTemplateId(),
        "the document's template_id is bound to the new clone immediately");
  }

  @Test
  void cloneForDocumentWithoutEditorDraftClonesSourceContent() {
    InvoiceTemplate typeDefault = seedTypeDefault();
    typeDefault.setCssContent(".x{}");
    typeDefault.setMarginTopMm(15);
    typeDefault.setMarginBottomMm(17);
    templateRepository.update(typeDefault);
    Invoice document = seedDocument(null);

    CloneForDocumentRequest request = new CloneForDocumentRequest(typeDefault.getId(), null);

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> response =
        (ResponseEntity<Map<String, Object>>)
            templateController.cloneForDocument(document.getId(), request);
    assertEquals(200, response.getStatusCode().value());
    Integer newId = (Integer) response.getBody().get("id");

    InvoiceTemplate persisted = templateRepository.findById(newId);
    assertEquals(TYPE_DEFAULT_BLOCKS, persisted.getBlocks(), "no draft → source content is copied");
    assertTrue(persisted.getName().contains("[doc #" + document.getId() + "]"));
    assertEquals(".x{}", persisted.getCssContent(), "the source CSS is copied");
    assertEquals(15, persisted.getMarginTopMm(), "the source margins are copied");
    assertEquals(17, persisted.getMarginBottomMm());
    assertEquals(typeDefault.getDocumentTypeId(), persisted.getDocumentTypeId());
    assertTrue(persisted.isDocumentSpecific(), "the clone is marked document-specific");
    assertFalse(persisted.isDefault(), "the clone is never the type default");
    assertTrue(
        templateRepository.findById(typeDefault.getId()).isDefault(),
        "the source stays the type default");
  }

  @Test
  void cloneForDocumentFallsBackToTypeDefaultWhenSourceIdIsNull() {
    InvoiceTemplate typeDefault = seedTypeDefault();
    Invoice document = seedDocument(null);

    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> response =
        (ResponseEntity<Map<String, Object>>)
            templateController.cloneForDocument(document.getId(), (CloneForDocumentRequest) null);
    assertEquals(200, response.getStatusCode().value());
    Integer newId = (Integer) response.getBody().get("id");

    InvoiceTemplate persisted = templateRepository.findById(newId);
    assertEquals(
        typeDefault.getDocumentTypeId(),
        persisted.getDocumentTypeId(),
        "null source resolves to the type default");
  }

  // --- deleteTemplate nulls references ---

  @Test
  void deleteTemplateNullsReferencingInvoicesBeforeRemovingRow() {
    InvoiceTemplate template = seedTypeDefault();
    Invoice document = seedDocument(template.getId());
    assertEquals(
        template.getId(),
        invoiceRepository.findById(document.getId()).getTemplateId(),
        "precondition: the document references the template");

    ResponseEntity<Void> response = templateController.deleteTemplate(template.getId());
    assertEquals(200, response.getStatusCode().value());

    assertNull(templateRepository.findById(template.getId()), "the template row should be deleted");
    assertNull(
        invoiceRepository.findById(document.getId()).getTemplateId(),
        "the document's template_id should be nulled, not left dangling");
  }

  @Test
  void deleteTemplateSucceedsWhenNothingReferencesIt() {
    InvoiceTemplate template = seedTypeDefault();

    ResponseEntity<Void> response = templateController.deleteTemplate(template.getId());
    assertEquals(200, response.getStatusCode().value());
    assertNull(templateRepository.findById(template.getId()));
  }

  @Test
  void deleteTemplateReturnsNotFoundForUnknownId() {
    ResponseEntity<Void> response = templateController.deleteTemplate(99999);
    assertEquals(404, response.getStatusCode().value());
  }

  // --- deleteInvoice cleans up the per-document clone ---

  @Test
  void deleteInvoiceCleansUpItsDocumentSpecificTemplateClone() {
    InvoiceTemplate typeDefault = seedTypeDefault();
    Invoice document = seedDocument(null);

    CloneForDocumentRequest request = new CloneForDocumentRequest(typeDefault.getId(), null);
    @SuppressWarnings("unchecked")
    ResponseEntity<Map<String, Object>> cloneResponse =
        (ResponseEntity<Map<String, Object>>)
            templateController.cloneForDocument(document.getId(), request);
    Integer cloneId = (Integer) cloneResponse.getBody().get("id");

    Invoice reloaded = invoiceRepository.findById(document.getId());
    assertEquals(
        cloneId, reloaded.getTemplateId(), "precondition: the document references the clone");

    ResponseEntity<Void> deleteResponse = invoiceController.deleteInvoice(document.getId());
    assertEquals(200, deleteResponse.getStatusCode().value());

    assertNull(invoiceRepository.findById(document.getId()), "the document row should be deleted");
    assertNull(
        templateRepository.findById(cloneId),
        "the per-document clone should be cleaned up to avoid orphan rows");
  }

  @Test
  void deleteInvoiceLeavesGlobalTemplateAlone() {
    InvoiceTemplate typeDefault = seedTypeDefault();
    // Corrupt-but-possible state: a document referencing a global template.
    // The cleanup must not delete a non-document-specific template.
    Invoice document = seedDocument(typeDefault.getId());

    ResponseEntity<Void> deleteResponse = invoiceController.deleteInvoice(document.getId());
    assertEquals(200, deleteResponse.getStatusCode().value());

    assertNotNull(
        templateRepository.findById(typeDefault.getId()),
        "the global template must not be deleted when its referencing invoice is removed");
  }
}
