package de.billery.api.controller;

import de.billery.api.dto.InvoiceTemplateDto;
import de.billery.data.Invoice;
import de.billery.data.InvoiceTemplate;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import de.billery.pdf.MissingConfigurationException;
import de.billery.pdf.TemplateBasedPdfGenerator;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.InvoiceTemplateRepository;
import de.billery.service.DocumentStateException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/templates")
public class InvoiceTemplateController {

  private static final Logger logger = Logger.getLogger(InvoiceTemplateController.class.getName());

  private final InvoiceTemplateRepository templateRepository;
  private final InvoiceRepository invoiceRepository;
  private final TemplateBasedPdfGenerator pdfGenerator;

  /** Runs each write in one database transaction. */
  private final TransactionTemplate transactions;

  /**
   * Creates the controller.
   *
   * @param templateRepository the template repository
   * @param invoiceRepository the document repository (template references, snapshots)
   * @param pdfGenerator regenerates a document's snapshot after its override template changed
   * @param transactions runs each write endpoint in one database transaction
   */
  public InvoiceTemplateController(
      InvoiceTemplateRepository templateRepository,
      InvoiceRepository invoiceRepository,
      TemplateBasedPdfGenerator pdfGenerator,
      TransactionTemplate transactions) {
    this.transactions = transactions;
    this.templateRepository = templateRepository;
    this.invoiceRepository = invoiceRepository;
    this.pdfGenerator = pdfGenerator;
  }

  @GetMapping
  public List<InvoiceTemplateDto> getAllTemplates(
      @RequestParam(required = false, defaultValue = "false") boolean includeDocumentSpecific) {
    List<InvoiceTemplate> templates =
        includeDocumentSpecific
            ? templateRepository.findAllIncludingDocumentSpecific()
            : templateRepository.findAll();
    List<InvoiceTemplateDto> result = new ArrayList<>();
    for (InvoiceTemplate template : templates) {
      result.add(convertToDto(template));
    }
    return result;
  }

  @GetMapping("/{id}")
  public ResponseEntity<InvoiceTemplateDto> getTemplate(@PathVariable Integer id) {
    InvoiceTemplate template = templateRepository.findById(id);
    if (template == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(convertToDto(template));
  }

  @GetMapping("/by-type/{documentTypeId}")
  public List<InvoiceTemplateDto> getTemplatesByDocumentType(@PathVariable Integer documentTypeId) {
    // Always exclusive: the document modal must never see another document's per-document clone.
    List<InvoiceTemplate> templates = templateRepository.findByDocumentTypeId(documentTypeId);
    List<InvoiceTemplateDto> result = new ArrayList<>();
    for (InvoiceTemplate template : templates) {
      result.add(convertToDto(template));
    }
    return result;
  }

  @GetMapping("/default/{documentTypeId}")
  public ResponseEntity<InvoiceTemplateDto> getDefaultTemplate(
      @PathVariable Integer documentTypeId) {
    InvoiceTemplate template = templateRepository.findDefaultByDocumentTypeId(documentTypeId);
    if (template == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(convertToDto(template));
  }

  @PostMapping
  public ResponseEntity<InvoiceTemplateDto> createTemplate(@RequestBody InvoiceTemplateDto dto) {
    return transactions.execute(status -> createTemplateInTransaction(dto));
  }

  private ResponseEntity<InvoiceTemplateDto> createTemplateInTransaction(InvoiceTemplateDto dto) {
    InvoiceTemplate template = convertFromDto(dto);

    // If this is set as default, clear other defaults for this document type
    if (Boolean.TRUE.equals(dto.isDefault())) {
      templateRepository.clearDefaultForDocumentType(template.getDocumentTypeId());
    }

    InvoiceTemplate saved = templateRepository.save(template);
    if (saved == null) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(convertToDto(saved));
  }

  @PutMapping("/{id}")
  public ResponseEntity<InvoiceTemplateDto> updateTemplate(
      @PathVariable Integer id, @RequestBody InvoiceTemplateDto dto) {
    return transactions.execute(status -> updateTemplateInTransaction(id, dto));
  }

  private ResponseEntity<InvoiceTemplateDto> updateTemplateInTransaction(
      Integer id, InvoiceTemplateDto dto) {
    InvoiceTemplate existing = templateRepository.findById(id);
    if (existing == null) {
      return ResponseEntity.notFound().build();
    }

    InvoiceTemplate template = convertFromDto(dto);
    template.setId(id);

    // If this is set as default, clear other defaults for this document type
    if (Boolean.TRUE.equals(dto.isDefault()) && !existing.isDefault()) {
      templateRepository.clearDefaultForDocumentType(template.getDocumentTypeId());
    }

    InvoiceTemplate updated = templateRepository.update(template);
    if (updated == null) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(convertToDto(updated));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteTemplate(@PathVariable Integer id) {
    return transactions.execute(status -> deleteTemplateInTransaction(id));
  }

  private ResponseEntity<Void> deleteTemplateInTransaction(Integer id) {
    InvoiceTemplate template = templateRepository.findById(id);
    if (template == null) {
      return ResponseEntity.notFound().build();
    }
    // The override is not a foreign key, so a plain DELETE would leave
    // referencing invoices with a dangling integer in invoice.template_id.
    // Null it out first so the renderer falls back to the type default at
    // PDF time (loadTemplateForInvoice logs a warning and continues).
    int affected = invoiceRepository.nullTemplateIdForTemplate(id);
    if (affected > 0 && logger.isLoggable(Level.WARNING)) {
      logger.log(
          Level.WARNING,
          "Template "
              + id
              + " was referenced by "
              + affected
              + " invoice(s); their template_id was nulled to fall back to the type default.");
    }
    boolean deleted = templateRepository.delete(id);
    if (!deleted) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok().build();
  }

  @PostMapping("/{id}/set-default")
  public ResponseEntity<Void> markAsDefault(@PathVariable Integer id) {
    templateRepository.setAsDefault(id);
    return ResponseEntity.ok().build();
  }

  /**
   * Creates a per-document template override for the given document and returns the new template's
   * id. The new row is a copy of {@code sourceTemplateId} (or the document's type default when
   * null) marked {@code is_document_specific = 1} so the global {@code /templates} list hides it.
   * Called by the "Customize layout for this document…" button in the document modal when the user
   * saves the editor for the first time.
   *
   * <p>Body: {@code { "sourceTemplateId": int | null, "templateData": InvoiceTemplateDto? }}. When
   * {@code sourceTemplateId} is null, the document's type-default template is used as the source.
   * When {@code templateData} is provided, it is applied on top of the source's content (name,
   * blocks, CSS, margins, …) — the SPA uses this to submit a freshly-edited template in one round
   * trip. {@code templateData.id} is ignored; the new row always gets a fresh id.
   *
   * <p>The new row is persisted, the document's {@code template_id} is set to its id, and the
   * save-time {@code rendered_html} snapshot is regenerated. Without the snapshot regen, the user
   * would have to also click "Speichern" in the document modal for the override to take effect at
   * PDF download time.
   */
  @PostMapping("/from-document/{documentId}")
  public ResponseEntity<Map<String, Object>> cloneForDocument(
      @PathVariable Integer documentId,
      @RequestBody(required = false) CloneForDocumentRequest body) {
    return transactions.execute(status -> cloneForDocumentInTransaction(documentId, body));
  }

  private ResponseEntity<Map<String, Object>> cloneForDocumentInTransaction(
      Integer documentId, CloneForDocumentRequest body) {
    Invoice document = invoiceRepository.findById(documentId);
    if (document == null) {
      return ResponseEntity.notFound().build();
    }
    if (DocumentType.isBookkeepingRecord(document.getDocumentTypeId())
        && document.getDocumentStatusId() != DocumentStatus.DRAFT) {
      // The clone would re-render the frozen snapshot of an issued invoice.
      throw new DocumentStateException(
          "Das Layout einer ausgestellten Rechnung kann nicht mehr geändert werden.");
    }
    Integer sourceTemplateId =
        body != null && body.sourceTemplateId() != null ? body.sourceTemplateId() : null;
    if (sourceTemplateId == null) {
      InvoiceTemplate typeDefault =
          templateRepository.findDefaultByDocumentTypeId(document.getDocumentTypeId());
      if (typeDefault == null) {
        return ResponseEntity.badRequest().build();
      }
      sourceTemplateId = typeDefault.getId();
    }
    InvoiceTemplate source = templateRepository.findById(sourceTemplateId);
    if (source == null) {
      return ResponseEntity.badRequest().build();
    }

    InvoiceTemplate copy = buildCloneFromSource(source, documentId);
    if (body != null && body.templateData() != null) {
      applyTemplateData(copy, body.templateData());
    }
    // Per-document clones are never type defaults.
    copy.setDefault(false);
    copy.setDocumentSpecific(true);

    InvoiceTemplate saved = templateRepository.save(copy);
    if (saved == null) {
      return ResponseEntity.badRequest().build();
    }

    // Persist the association so the document is immediately tied to its
    // per-document override, and the snapshot is fresh. Without this, the
    // user has to also click "Speichern" in the document modal for the
    // override to take effect at PDF download time.
    document.setTemplateId(saved.getId());
    invoiceRepository.update(document);
    renderSnapshotForDocument(document);

    return ResponseEntity.ok(Map.of("id", saved.getId(), "name", saved.getName()));
  }

  /**
   * Body for {@link #previewPdf}.
   *
   * @param templateData the editor's unsaved template
   * @param invoiceId the document to render with; {@code null} uses a sample document
   */
  public record PreviewRequest(InvoiceTemplateDto templateData, Integer invoiceId) {}

  /**
   * Renders an unsaved template to PDF exactly like a real document (the editor's "exact preview").
   * Read-only.
   *
   * @param request the draft and the optional document
   * @return {@code application/pdf}; 400 without template data, 409 without blocks or company data
   * @throws IOException if rendering fails (mapped to 500)
   */
  @PostMapping("/preview")
  public ResponseEntity<byte[]> previewPdf(@RequestBody PreviewRequest request) throws IOException {
    if (request == null || request.templateData() == null) {
      return ResponseEntity.badRequest().build();
    }
    InvoiceTemplate draft = convertFromDto(request.templateData());
    byte[] pdf = pdfGenerator.generateTemplatePreviewPdf(draft, request.invoiceId());
    return ResponseEntity.ok()
        .header("Content-Type", "application/pdf")
        .header("Content-Disposition", "inline; filename=\"vorschau.pdf\"")
        .body(pdf);
  }

  /**
   * Body for {@link #cloneForDocument}. {@code sourceTemplateId} is the template to clone (null
   * resolves to the document's type default); {@code templateData} is an optional editor form to
   * apply on top of the source's content (its id is ignored).
   */
  public record CloneForDocumentRequest(
      Integer sourceTemplateId, InvoiceTemplateDto templateData) {}

  private InvoiceTemplate buildCloneFromSource(InvoiceTemplate source, int documentId) {
    InvoiceTemplate copy = new InvoiceTemplate();
    copy.setName("[doc #" + documentId + "] " + source.getName());
    copy.setDocumentTypeId(source.getDocumentTypeId());
    copy.setHtmlContent(source.getHtmlContent());
    copy.setCssContent(source.getCssContent());
    copy.setHeaderHtml(source.getHeaderHtml());
    copy.setFooterHtml(source.getFooterHtml());
    copy.setMarginTopMm(source.getMarginTopMm());
    copy.setMarginBottomMm(source.getMarginBottomMm());
    copy.setMarginLeftMm(source.getMarginLeftMm());
    copy.setMarginRightMm(source.getMarginRightMm());
    copy.setBlocks(source.getBlocks());
    return copy;
  }

  private void applyTemplateData(InvoiceTemplate target, InvoiceTemplateDto data) {
    if (data.name() != null) {
      target.setName(data.name());
    }
    if (data.htmlContent() != null) {
      target.setHtmlContent(data.htmlContent());
    }
    if (data.cssContent() != null) {
      target.setCssContent(data.cssContent());
    }
    if (data.headerHtml() != null) {
      target.setHeaderHtml(data.headerHtml());
    }
    if (data.footerHtml() != null) {
      target.setFooterHtml(data.footerHtml());
    }
    if (data.blocks() != null) {
      target.setBlocks(data.blocks());
    }
    if (data.marginTopMm() != null) {
      target.setMarginTopMm(data.marginTopMm());
    }
    if (data.marginBottomMm() != null) {
      target.setMarginBottomMm(data.marginBottomMm());
    }
    if (data.marginLeftMm() != null) {
      target.setMarginLeftMm(data.marginLeftMm());
    }
    if (data.marginRightMm() != null) {
      target.setMarginRightMm(data.marginRightMm());
    }
    // documentSpecific / isDefault are forced to (true, false) by the caller; we don't
    // trust the editor to set them.
  }

  /**
   * Regenerates {@code invoice.rendered_html} from the current template (using {@link
   * TemplateBasedPdfGenerator#generateInvoiceHtml} which honours {@code invoice.templateId} via
   * {@code loadTemplateForInvoice}). Best-effort: a failure here just leaves the snapshot as-is,
   * and the next document save will refresh it.
   */
  private void renderSnapshotForDocument(Invoice document) {
    try {
      String renderedHtml = pdfGenerator.generateInvoiceHtml(document);
      invoiceRepository.updateRenderedHtml(document.getId(), renderedHtml);
    } catch (IOException | MissingConfigurationException e) {
      logger.log(Level.WARNING, "Failed to regenerate snapshot for invoice " + document.getId(), e);
    }
  }

  private InvoiceTemplateDto convertToDto(InvoiceTemplate template) {
    return new InvoiceTemplateDto(
        template.getId(),
        template.getName(),
        template.getDocumentTypeId(),
        template.getHtmlContent(),
        template.getCssContent(),
        template.getHeaderHtml(),
        template.getFooterHtml(),
        template.isDefault(),
        template.getMarginTopMm(),
        template.getMarginBottomMm(),
        template.getMarginLeftMm(),
        template.getMarginRightMm(),
        template.getCreatedAt(),
        template.getUpdatedAt(),
        template.getBlocks(),
        template.isDocumentSpecific());
  }

  private InvoiceTemplate convertFromDto(InvoiceTemplateDto dto) {
    InvoiceTemplate template = new InvoiceTemplate();
    template.setId(dto.id() != null ? dto.id() : 0);
    template.setName(dto.name());
    template.setDocumentTypeId(dto.documentTypeId() != null ? dto.documentTypeId() : 1);
    template.setHtmlContent(dto.htmlContent());
    template.setCssContent(dto.cssContent());
    template.setHeaderHtml(dto.headerHtml());
    template.setFooterHtml(dto.footerHtml());
    template.setDefault(Boolean.TRUE.equals(dto.isDefault()));
    template.setMarginTopMm(dto.marginTopMm() != null ? dto.marginTopMm() : 10);
    template.setMarginBottomMm(dto.marginBottomMm() != null ? dto.marginBottomMm() : 10);
    template.setMarginLeftMm(dto.marginLeftMm() != null ? dto.marginLeftMm() : 10);
    template.setMarginRightMm(dto.marginRightMm() != null ? dto.marginRightMm() : 10);
    template.setBlocks(dto.blocks());
    template.setDocumentSpecific(Boolean.TRUE.equals(dto.documentSpecific()));
    return template;
  }
}
