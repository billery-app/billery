package de.billery.service;

import de.billery.data.Customer;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.InvoiceTemplate;
import de.billery.data.PartySnapshot;
import de.billery.data.Payment;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import de.billery.pdf.TemplateBasedPdfGenerator;
import de.billery.repository.CustomerRepository;
import de.billery.repository.DocumentHistoryRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.InvoiceTemplateRepository;
import de.billery.repository.OwnerRepository;
import de.billery.repository.PaymentRepository;
import de.billery.tax.TaxTreatment;
import de.billery.tax.VatCalculator;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The bookkeeping-relevant life of an invoice (Rechnung, Teilrechnung, Endrechnung), following GoBD
 * and §14 UStG:
 *
 * <ol>
 *   <li><b>Draft</b>: editable and deletable, no number ({@link DocumentService}).
 *   <li><b>Issue</b> ({@link #issue}): the number is reserved from the series of the invoice date's
 *       year, the mandatory fields are checked and the HTML snapshot is frozen. From now on the
 *       invoice cannot be changed or deleted.
 *   <li><b>Payments</b> ({@link #recordPayment}, {@link #reversePayment}): append-only rows; the
 *       status SENT / PARTIALLY_PAID / PAID and the paid date are derived from their sum.
 *   <li><b>Cancel</b> ({@link #cancel}): creates a credit note (Stornorechnung) with its own number
 *       that references the invoice; the invoice itself only changes its status to CANCELLED.
 * </ol>
 *
 * <p>Every public method runs in one transaction and writes a {@code document_history} entry. A
 * rule violation throws {@link DocumentStateException} (HTTP 409 with a German message).
 */
@Service
public class InvoiceLifecycleService {

  private static final DateTimeFormatter GERMAN_DATE =
      DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMAN);

  private final InvoiceRepository invoiceRepository;
  private final DocumentNumberService numberService;
  private final PaymentRepository paymentRepository;
  private final DocumentHistoryRepository historyRepository;
  private final InvoiceTemplateRepository templateRepository;
  private final CustomerRepository customerRepository;
  private final OwnerRepository ownerRepository;
  private final TemplateBasedPdfGenerator pdfGenerator;

  /** Runs each write in one database transaction. */
  private final TransactionTemplate transactions;

  /**
   * Creates the service.
   *
   * @param invoiceRepository the document repository
   * @param numberService reserves document numbers
   * @param paymentRepository the append-only payments
   * @param historyRepository the audit trail
   * @param templateRepository cleans up per-document template clones on delete
   * @param customerRepository reads the buyer data for the party snapshot
   * @param ownerRepository reads the seller data for the party snapshot
   * @param pdfGenerator renders the frozen HTML snapshot
   * @param transactions runs each write in one database transaction
   */
  public InvoiceLifecycleService(
      InvoiceRepository invoiceRepository,
      DocumentNumberService numberService,
      PaymentRepository paymentRepository,
      DocumentHistoryRepository historyRepository,
      InvoiceTemplateRepository templateRepository,
      CustomerRepository customerRepository,
      OwnerRepository ownerRepository,
      TemplateBasedPdfGenerator pdfGenerator,
      TransactionTemplate transactions) {
    this.transactions = transactions;
    this.invoiceRepository = invoiceRepository;
    this.numberService = numberService;
    this.paymentRepository = paymentRepository;
    this.historyRepository = historyRepository;
    this.templateRepository = templateRepository;
    this.customerRepository = customerRepository;
    this.ownerRepository = ownerRepository;
    this.pdfGenerator = pdfGenerator;
  }

  /**
   * Gross amount of a document: net plus VAT per rate group ({@link VatCalculator}).
   *
   * @param invoice the document
   * @return the gross amount, scale 2
   */
  public BigDecimal grossAmount(Invoice invoice) {
    return VatCalculator.calculate(
            invoiceRepository.findItemsByInvoiceId(invoice.getId()),
            TaxTreatment.parse(invoice.getTaxTreatment()))
        .gross();
  }

  /**
   * Amount received so far for a document (reversals count negatively).
   *
   * @param invoiceId the document
   * @return the paid amount, scale 2
   */
  public BigDecimal paidAmount(int invoiceId) {
    return paymentRepository.sumByInvoiceId(invoiceId);
  }

  /**
   * Issues a draft invoice: reserves its number, checks the mandatory invoice fields (§14 Abs. 4
   * UStG) and freezes the rendered snapshot. A draft migrated from before this rule may already
   * carry a number; it keeps it.
   *
   * @param id the draft
   * @return the issued invoice
   * @throws DocumentStateException if the document is not an invoice draft or a mandatory field is
   *     missing
   * @throws de.billery.pdf.MissingConfigurationException if it cannot be rendered (no company data
   *     / template); nothing is issued then
   */
  public Invoice issue(int id) {
    return transactions.execute(status -> doIssue(id));
  }

  private Invoice doIssue(int id) {
    Invoice invoice = require(id);
    if (!DocumentType.isInvoice(invoice.getDocumentTypeId())
        || invoice.getDocumentStatusId() != DocumentStatus.DRAFT) {
      throw new DocumentStateException("Nur Rechnungsentwürfe können ausgestellt werden.");
    }
    checkMandatoryFields(invoice);

    String number = invoice.getDocumentNumber();
    if (number == null || number.isEmpty()) {
      int year = LocalDate.parse(invoice.getInvoiceDate()).getYear();
      number = numberService.generateDocumentNumber(invoice.getDocumentTypeId(), year);
    }
    if (!invoiceRepository.issue(id, number, DocumentStatus.SENT, DocumentStatus.DRAFT)) {
      throw new DocumentStateException("Die Rechnung wurde bereits ausgestellt.");
    }
    invoice.setDocumentNumber(number);
    invoice.setDocumentStatusId(DocumentStatus.SENT);
    freezeParties(
        invoice,
        PartySnapshot.capture(
            ownerRepository.findOwner(), customerRepository.findById(invoice.getCustomerId())));
    freezeSnapshot(invoice);
    historyRepository.log(id, DocumentHistoryRepository.ACTION_ISSUED, "Nummer " + number);
    return invoice;
  }

  private void checkMandatoryFields(Invoice invoice) {
    if (invoice.getInvoiceDate() == null || invoice.getInvoiceDate().isEmpty()) {
      throw new DocumentStateException("Bitte ein Rechnungsdatum angeben.");
    }
    if (invoice.getWorkStartDate() == null || invoice.getWorkStartDate().isEmpty()) {
      throw new DocumentStateException(
          "Bitte das Leistungsdatum angeben (Pflichtangabe nach §14 Abs. 4 UStG).");
    }
    List<InvoiceItem> items = invoiceRepository.findItemsByInvoiceId(invoice.getId());
    if (items.isEmpty()) {
      throw new DocumentStateException(
          "Eine Rechnung ohne Positionen kann nicht ausgestellt werden.");
    }
    TaxTreatment treatment = TaxTreatment.parse(invoice.getTaxTreatment());
    if (treatment.chargesVat()) {
      checkRates(items);
    }
    if (treatment == TaxTreatment.TAX_EXEMPT && invoice.getTaxExemptionReason().isBlank()) {
      throw new DocumentStateException(
          "Bitte den Grund der Steuerbefreiung angeben (z. B. \"gemäß § 4 Nr. 14 UStG\").");
    }
    if (treatment == TaxTreatment.REVERSE_CHARGE) {
      Customer customer = customerRepository.findById(invoice.getCustomerId());
      if (customer == null || customer.getTaxId() == null || customer.getTaxId().isBlank()) {
        throw new DocumentStateException(
            "Für das Reverse-Charge-Verfahren (§ 13b UStG) braucht der Kunde eine USt-IdNr."
                + " oder Steuernummer.");
      }
    }
  }

  /** Every priced line of a STANDARD-taxed invoice must carry 19 % or 7 %. */
  private static void checkRates(List<InvoiceItem> items) {
    for (InvoiceItem item : items) {
      boolean isNote = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(item.getItemType());
      if (!isNote && !VatCalculator.isAllowedRate(item.getTaxRatePercent())) {
        throw new DocumentStateException(
            "Position \""
                + item.getItemName()
                + "\" hat den Steuersatz "
                + item.getTaxRatePercent()
                + " % (erlaubt: 19 %, 7 %).");
      }
      if (item.getChildren() != null) {
        checkRates(item.getChildren());
      }
    }
  }

  /**
   * Records a payment for an issued, not yet fully paid invoice and derives the new status.
   *
   * @param invoiceId the invoice
   * @param paymentDate the date the money was received
   * @param amount the amount, more than 0 and at most the open amount
   * @param paymentMethod free text (may be empty)
   * @param reference free text (may be empty)
   * @param notes free text (may be empty)
   * @return the saved payment
   * @throws DocumentStateException if the invoice is not open or the amount is out of range
   */
  public Payment recordPayment(
      int invoiceId,
      LocalDate paymentDate,
      BigDecimal amount,
      String paymentMethod,
      String reference,
      String notes) {
    return transactions.execute(
        status -> {
          Invoice invoice = requireOpenInvoice(invoiceId);
          return doRecordPayment(invoice, paymentDate, amount, paymentMethod, reference, notes);
        });
  }

  /**
   * Records the whole open amount as one payment, which marks the invoice PAID. Used by the
   * "Bezahlt" status button.
   *
   * @param invoiceId the invoice
   * @param paymentDate the date the money was received
   * @return the saved payment
   * @throws DocumentStateException if the invoice is not open
   */
  public Payment recordFullPayment(int invoiceId, LocalDate paymentDate) {
    return transactions.execute(
        status -> {
          Invoice invoice = requireOpenInvoice(invoiceId);
          BigDecimal open = grossAmount(invoice).subtract(paidAmount(invoiceId));
          return doRecordPayment(invoice, paymentDate, open, "", "", "");
        });
  }

  private Payment doRecordPayment(
      Invoice invoice,
      LocalDate paymentDate,
      BigDecimal amount,
      String paymentMethod,
      String reference,
      String notes) {
    if (paymentDate == null) {
      throw new DocumentStateException("Bitte das Zahlungsdatum angeben.");
    }
    BigDecimal open = grossAmount(invoice).subtract(paidAmount(invoice.getId()));
    if (amount == null || amount.signum() <= 0) {
      throw new DocumentStateException("Der Zahlungsbetrag muss größer als 0 sein.");
    }
    BigDecimal cents = amount.setScale(2, RoundingMode.HALF_UP);
    if (cents.compareTo(open) > 0) {
      throw new DocumentStateException(
          "Der Betrag übersteigt den offenen Betrag von " + euro(open) + ".");
    }
    Payment saved =
        paymentRepository.save(
            new Payment(
                0, invoice.getId(), paymentDate, cents, paymentMethod, reference, notes, null));
    historyRepository.log(
        invoice.getId(),
        DocumentHistoryRepository.ACTION_PAYMENT_RECORDED,
        euro(cents) + " am " + paymentDate.format(GERMAN_DATE));
    deriveStatusFromPayments(invoice);
    return saved;
  }

  /**
   * Reverses a mistaken payment by appending a row with the negated amount (payments are never
   * deleted) and derives the new status. Also allowed on a cancelled invoice (refund); its status
   * then stays CANCELLED.
   *
   * @param invoiceId the invoice
   * @param paymentId the payment to reverse
   * @return the reversal row
   * @throws DocumentStateException if the payment does not belong to the invoice, is itself a
   *     reversal, or was already reversed
   */
  public Payment reversePayment(int invoiceId, int paymentId) {
    return transactions.execute(status -> doReversePayment(invoiceId, paymentId));
  }

  private Payment doReversePayment(int invoiceId, int paymentId) {
    Invoice invoice = require(invoiceId);
    Payment original = paymentRepository.findById(paymentId);
    if (original == null || original.invoiceId() != invoiceId) {
      throw new DocumentStateException("Die Zahlung gehört nicht zu dieser Rechnung.");
    }
    if (original.reversesPaymentId() != null) {
      throw new DocumentStateException("Eine Stornobuchung kann nicht storniert werden.");
    }
    if (paymentRepository.isReversed(paymentId)) {
      throw new DocumentStateException("Die Zahlung wurde bereits storniert.");
    }
    Payment reversal =
        paymentRepository.save(
            new Payment(
                0,
                invoiceId,
                LocalDate.now(),
                original.amount().negate(),
                original.paymentMethod(),
                original.reference(),
                "Storno der Zahlung vom " + original.paymentDate().format(GERMAN_DATE),
                paymentId));
    historyRepository.log(
        invoiceId,
        DocumentHistoryRepository.ACTION_PAYMENT_REVERSED,
        euro(original.amount()) + " vom " + original.paymentDate().format(GERMAN_DATE));
    if (invoice.getDocumentStatusId() != DocumentStatus.CANCELLED) {
      deriveStatusFromPayments(invoice);
    }
    return reversal;
  }

  /**
   * Cancels an issued invoice: creates a credit note (Stornorechnung) with its own number from the
   * {@code ST} series, dated today, that repeats the invoice's items and tax rate and references
   * it. The invoice only changes its status to CANCELLED; recorded payments stay (a refund is
   * recorded by reversing them).
   *
   * @param invoiceId the invoice to cancel
   * @return the credit note
   * @throws DocumentStateException if the invoice is a draft (delete it instead) or already
   *     cancelled
   */
  public Invoice cancel(int invoiceId) {
    return transactions.execute(status -> doCancel(invoiceId));
  }

  private Invoice doCancel(int invoiceId) {
    Invoice invoice = require(invoiceId);
    int status = invoice.getDocumentStatusId();
    if (!DocumentType.isInvoice(invoice.getDocumentTypeId())) {
      throw new DocumentStateException("Nur Rechnungen werden mit einer Stornorechnung storniert.");
    }
    if (status == DocumentStatus.DRAFT) {
      throw new DocumentStateException("Entwürfe werden gelöscht, nicht storniert.");
    }
    if (status == DocumentStatus.CANCELLED) {
      throw new DocumentStateException("Die Rechnung ist bereits storniert.");
    }

    LocalDate today = LocalDate.now();
    String number = numberService.generateDocumentNumber(DocumentType.CREDIT_NOTE, today.getYear());
    Invoice creditNote =
        new Invoice(
            0,
            invoice.getCustomerId(),
            today.toString(),
            "",
            invoice.getTotalPrice(),
            // The original rate, even if the owner has since become a Kleinunternehmer.
            invoice.getTaxRate(),
            "",
            "Stornorechnung zur Rechnung "
                + invoice.getFormattedInvoiceNumber()
                + " vom "
                + LocalDate.parse(invoice.getInvoiceDate()).format(GERMAN_DATE),
            number,
            1,
            1,
            DocumentType.CREDIT_NOTE,
            DocumentStatus.SENT,
            0,
            invoice.getWorkStartDate(),
            invoice.getWorkEndDate(),
            0,
            0,
            0,
            "",
            "",
            "",
            0);
    creditNote.setCancelsInvoiceId(invoiceId);
    // Same tax treatment as the invoice it reverses.
    creditNote.setTaxTreatment(invoice.getTaxTreatment());
    creditNote.setTaxExemptionReason(invoice.getTaxExemptionReason());
    invoiceRepository.save(creditNote);
    creditNote.setVersionGroupId(creditNote.getId());
    invoiceRepository.update(creditNote);
    for (InvoiceItem item : invoiceRepository.findItemsByInvoiceId(invoiceId)) {
      copyItemTree(creditNote.getId(), item, 0);
    }

    invoiceRepository.updateStatus(invoiceId, DocumentStatus.CANCELLED, null);
    // The credit note names the same parties as the invoice it reverses.
    PartySnapshot parties = PartySnapshot.fromJson(invoice.getPartySnapshot());
    freezeParties(
        creditNote,
        parties != null
            ? parties
            : PartySnapshot.capture(
                ownerRepository.findOwner(), customerRepository.findById(invoice.getCustomerId())));
    freezeSnapshot(creditNote);
    historyRepository.log(
        creditNote.getId(),
        DocumentHistoryRepository.ACTION_CREATED,
        "Storno zu " + invoice.getFormattedInvoiceNumber());
    historyRepository.log(
        invoiceId, DocumentHistoryRepository.ACTION_CANCELLED, "Stornorechnung " + number);
    return creditNote;
  }

  /**
   * Deletes a draft together with its items, its audit trail and its per-document template clone.
   * Issued documents must be kept (GoBD / §147 AO) and are archived or cancelled instead.
   *
   * @param id the document
   * @throws DocumentStateException if the document is not a draft
   */
  public void deleteDraft(int id) {
    transactions.execute(
        status -> {
          Invoice invoice = require(id);
          if (invoice.getDocumentStatusId() != DocumentStatus.DRAFT) {
            throw new DocumentStateException(
                "Nur Entwürfe können gelöscht werden. Ausgestellte Dokumente bitte archivieren"
                    + " oder stornieren (Aufbewahrungspflicht).");
          }
          historyRepository.deleteDraftHistory(id);
          invoiceRepository.delete(id);
          Integer templateId = invoice.getTemplateId();
          if (templateId != null) {
            InvoiceTemplate template = templateRepository.findById(templateId);
            // Only per-document clones die with their document; a global template is left alone.
            if (template != null && template.isDocumentSpecific()) {
              templateRepository.delete(templateId);
            }
          }
          return null;
        });
  }

  /**
   * Sets SENT, PARTIALLY_PAID or PAID from the sum of payments. PAID carries the date of the latest
   * payment as {@code paid_date}.
   */
  private void deriveStatusFromPayments(Invoice invoice) {
    BigDecimal paid = paidAmount(invoice.getId());
    BigDecimal gross = grossAmount(invoice);
    if (paid.signum() > 0 && paid.compareTo(gross) >= 0) {
      List<Payment> payments = paymentRepository.findByInvoiceId(invoice.getId());
      LocalDate lastPayment =
          payments.stream()
              .filter(p -> p.amount().signum() > 0)
              .map(Payment::paymentDate)
              .max(LocalDate::compareTo)
              .orElse(LocalDate.now());
      invoiceRepository.updateStatus(invoice.getId(), DocumentStatus.PAID, lastPayment.toString());
    } else if (paid.signum() > 0) {
      invoiceRepository.updateStatus(invoice.getId(), DocumentStatus.PARTIALLY_PAID, null);
    } else {
      invoiceRepository.updateStatus(invoice.getId(), DocumentStatus.SENT, null);
    }
  }

  private Invoice requireOpenInvoice(int invoiceId) {
    Invoice invoice = require(invoiceId);
    int status = invoice.getDocumentStatusId();
    if (!DocumentType.isInvoice(invoice.getDocumentTypeId())
        || status != DocumentStatus.SENT && status != DocumentStatus.PARTIALLY_PAID) {
      throw new DocumentStateException(
          "Zahlungen können nur für ausgestellte, noch offene Rechnungen erfasst werden.");
    }
    return invoice;
  }

  private Invoice require(int id) {
    Invoice invoice = invoiceRepository.findById(id);
    if (invoice == null) {
      throw new DocumentNotFoundException(id);
    }
    return invoice;
  }

  /**
   * Renders and stores the snapshot that the PDF download serves from now on. Unlike drafts, an
   * issued document must have one, so a rendering problem aborts the transaction.
   */
  private void freezeSnapshot(Invoice invoice) {
    try {
      String html = pdfGenerator.generateInvoiceHtml(invoice);
      invoice.setRenderedHtml(html);
      invoiceRepository.updateRenderedHtml(invoice.getId(), html);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Stores the frozen seller and buyer data of an issued document. */
  private void freezeParties(Invoice invoice, PartySnapshot parties) {
    String json = parties.toJson();
    invoice.setPartySnapshot(json);
    invoiceRepository.updatePartySnapshot(invoice.getId(), json);
  }

  private void copyItemTree(int invoiceId, InvoiceItem source, int parentId) {
    InvoiceItem copy =
        new InvoiceItem(
            0,
            invoiceId,
            source.getItemType(),
            source.getItemId(),
            source.getQuantity(),
            source.getUnitPrice(),
            source.getTotalPrice());
    copy.setParentItemId(parentId);
    copy.setUnit(source.getUnit());
    copy.setItemName(source.getItemName());
    copy.setItemDescription(source.getItemDescription());
    copy.setTaxRatePercent(source.getTaxRatePercent());
    invoiceRepository.saveInvoiceItem(copy);
    if (source.getChildren() != null) {
      for (InvoiceItem child : source.getChildren()) {
        copyItemTree(invoiceId, child, copy.getId());
      }
    }
  }

  private static String euro(BigDecimal amount) {
    return String.format(Locale.GERMANY, "%,.2f €", amount);
  }
}
