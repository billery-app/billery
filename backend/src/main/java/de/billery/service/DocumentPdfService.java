package de.billery.service;

import de.billery.data.Invoice;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import de.billery.pdf.FacturXEmbedder;
import de.billery.pdf.TemplateBasedPdfGenerator;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * The PDF of a document, the one the download and the GoBD export hand out.
 *
 * <p>Every PDF is PDF/A-3b. An issued invoice or credit note whose data suffices for an e-invoice
 * is a ZUGFeRD / Factur-X invoice: the visible PDF with the XRechnung (CII syntax) attached ({@link
 * FacturXEmbedder}). Both come from the data frozen at issue, so the attachment says what the page
 * shows; {@code ZugferdTest} compares them. Without complete data the PDF has no attachment; {@code
 * GET /api/invoices/{id}/xrechnung/check} names what is missing.
 */
@Service
public class DocumentPdfService {

  private final TemplateBasedPdfGenerator pdfGenerator;
  private final EInvoiceService eInvoiceService;

  /**
   * Creates the service.
   *
   * @param pdfGenerator renders the visible PDF
   * @param eInvoiceService produces the XML to attach
   */
  public DocumentPdfService(
      TemplateBasedPdfGenerator pdfGenerator, EInvoiceService eInvoiceService) {
    this.pdfGenerator = pdfGenerator;
    this.eInvoiceService = eInvoiceService;
  }

  /**
   * Renders the PDF of a document.
   *
   * @param invoice the document
   * @return the PDF/A-3, with the e-invoice attached where possible
   * @throws Exception if rendering fails
   */
  public byte[] pdf(Invoice invoice) throws Exception {
    byte[] visible = visiblePdf(invoice);
    Optional<String> xml = eInvoiceService.ciiForPdf(invoice);
    if (xml.isEmpty()) {
      return visible;
    }
    return FacturXEmbedder.embed(visible, xml.get(), issueDate(invoice));
  }

  /**
   * Which rendering a document gets. Issued invoices and credit notes are frozen: always the
   * snapshot taken when they were issued, even if the template, the customer or the company data
   * changed since (GoBD).
   */
  private byte[] visiblePdf(Invoice invoice) throws Exception {
    boolean bookkeeping = DocumentType.isBookkeepingRecord(invoice.getDocumentTypeId());
    boolean issuedRecord = bookkeeping && invoice.getDocumentStatusId() != DocumentStatus.DRAFT;
    boolean hasSnapshot = invoice.getRenderedHtml() != null && !invoice.getRenderedHtml().isEmpty();
    if (issuedRecord && hasSnapshot) {
      return pdfGenerator.convertHtmlToPdf(invoice.getRenderedHtml());
    }
    if (bookkeeping || invoice.getTemplateId() != null || !hasSnapshot) {
      // Drafts show the current state. A per-document template override always renders with the
      // current override template, so edits to it show without saving the document again. Legacy
      // rows without snapshot are generated fresh.
      return pdfGenerator.generatePdfInvoice(invoice);
    }
    // Offers and cost calculations without override: the snapshot freezes them at save time, so
    // the PDF stays the same when the (global) template is edited later.
    return pdfGenerator.convertHtmlToPdf(invoice.getRenderedHtml());
  }

  /** The invoice date: the attachment's dates, the same on every download. */
  private static LocalDate issueDate(Invoice invoice) throws IOException {
    if (invoice.getInvoiceDate() == null || invoice.getInvoiceDate().isEmpty()) {
      throw new IOException("An issued document without invoice date: " + invoice.getId());
    }
    return LocalDate.parse(invoice.getInvoiceDate());
  }
}
