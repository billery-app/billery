package de.billery.service;

import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.PartySnapshot;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import de.billery.repository.CustomerRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.OwnerRepository;
import de.billery.xmlgenerator.CiiGenerator;
import de.billery.xmlgenerator.XRechnungGenerator;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Produces the XRechnung (e-invoice, mandatory for B2B from 2027/2028) of an issued invoice or
 * credit note: as UBL for the separate download, and as CII for the ZUGFeRD PDF ({@link
 * #ciiForPdf}). Both are built from the same data by the same rules.
 *
 * <p>Issued documents use the seller and buyer data frozen at issue ({@link PartySnapshot}); the
 * items are immutable after issue anyway, so the XML is the same on every download. Documents
 * issued before the snapshot existed and drafts (check only) use the current master data.
 */
@Service
public class EInvoiceService {

  private static final String NUMBER_FIELD = "Rechnungsnummer (erst nach dem Ausstellen)";

  private final InvoiceRepository invoiceRepository;
  private final CustomerRepository customerRepository;
  private final OwnerRepository ownerRepository;
  private final XRechnungGenerator generator = new XRechnungGenerator();
  private final CiiGenerator ciiGenerator = new CiiGenerator();

  /**
   * Creates the service.
   *
   * @param invoiceRepository the document repository
   * @param customerRepository reads the buyer data when no party snapshot exists
   * @param ownerRepository reads the seller data when no party snapshot exists
   */
  public EInvoiceService(
      InvoiceRepository invoiceRepository,
      CustomerRepository customerRepository,
      OwnerRepository ownerRepository) {
    this.invoiceRepository = invoiceRepository;
    this.customerRepository = customerRepository;
    this.ownerRepository = ownerRepository;
  }

  /**
   * Lists what is missing for a valid XRechnung. For a draft the number is not reported (it is
   * assigned on issue), so the user can complete the master data before issuing.
   *
   * @param id the document
   * @return German labels of the missing fields; empty if an XRechnung can be produced
   * @throws DocumentStateException if the document is not an invoice or credit note
   */
  public List<String> check(int id) {
    Invoice invoice = requireEInvoiceDocument(id);
    List<InvoiceItem> items = invoiceRepository.findItemsByInvoiceId(id);
    List<String> missing = generator.missingFields(invoice, items, partiesOf(invoice));
    if (invoice.getDocumentStatusId() == DocumentStatus.DRAFT) {
      missing.remove(NUMBER_FIELD);
    }
    return missing;
  }

  /**
   * Generates the XRechnung of an issued document.
   *
   * @param id the document
   * @return the UBL XML
   * @throws DocumentStateException if the document is a draft, not an invoice / credit note, or
   *     required data is missing (the message lists it)
   */
  public String xrechnung(int id) {
    Invoice invoice = requireEInvoiceDocument(id);
    if (invoice.getDocumentStatusId() == DocumentStatus.DRAFT) {
      throw new DocumentStateException("Eine E-Rechnung gibt es erst für ausgestellte Rechnungen.");
    }
    List<InvoiceItem> items = invoiceRepository.findItemsByInvoiceId(id);
    PartySnapshot parties = partiesOf(invoice);
    List<String> missing = generator.missingFields(invoice, items, parties);
    if (!missing.isEmpty()) {
      throw new DocumentStateException(
          "Für die E-Rechnung (XRechnung) fehlen: " + String.join(", ", missing) + ".");
    }
    return generator.generate(invoice, items, parties, cancelledBy(invoice));
  }

  /**
   * The XRechnung in CII syntax for the ZUGFeRD PDF of a document: only for an issued invoice or
   * credit note whose data is complete. The PDF download never fails because of the e-invoice: a
   * document without it is still a valid PDF/A, just without the attachment.
   *
   * @param invoice the document, as loaded for the PDF
   * @return the CII XML, or empty if the document is a draft, not an invoice / credit note, or
   *     required data is missing ({@link #check} names it)
   */
  public Optional<String> ciiForPdf(Invoice invoice) {
    if (!DocumentType.isBookkeepingRecord(invoice.getDocumentTypeId())
        || invoice.getDocumentStatusId() == DocumentStatus.DRAFT) {
      return Optional.empty();
    }
    List<InvoiceItem> items = invoiceRepository.findItemsByInvoiceId(invoice.getId());
    PartySnapshot parties = partiesOf(invoice);
    if (!generator.missingFields(invoice, items, parties).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(ciiGenerator.generate(invoice, items, parties, cancelledBy(invoice)));
  }

  /** The invoice a credit note cancels, or {@code null}. */
  private Invoice cancelledBy(Invoice invoice) {
    return invoice.getCancelsInvoiceId() > 0
        ? invoiceRepository.findById(invoice.getCancelsInvoiceId())
        : null;
  }

  private Invoice requireEInvoiceDocument(int id) {
    Invoice invoice = invoiceRepository.findById(id);
    if (invoice == null) {
      throw new DocumentNotFoundException(id);
    }
    if (!DocumentType.isBookkeepingRecord(invoice.getDocumentTypeId())) {
      throw new DocumentStateException(
          "E-Rechnungen gibt es nur für Rechnungen und Stornorechnungen.");
    }
    return invoice;
  }

  /** The frozen parties of an issued document, or the current master data. */
  private PartySnapshot partiesOf(Invoice invoice) {
    PartySnapshot frozen = PartySnapshot.fromJson(invoice.getPartySnapshot());
    if (frozen != null) {
      return frozen;
    }
    return PartySnapshot.capture(
        ownerRepository.findOwner(), customerRepository.findById(invoice.getCustomerId()));
  }
}
