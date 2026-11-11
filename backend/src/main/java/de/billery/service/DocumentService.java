package de.billery.service;

import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.Owner;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentStrategyRegistry;
import de.billery.document.DocumentType;
import de.billery.document.DocumentTypeStrategy;
import de.billery.pdf.MissingConfigurationException;
import de.billery.pdf.TemplateBasedPdfGenerator;
import de.billery.repository.DocumentHistoryRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.OwnerRepository;
import de.billery.tax.TaxTreatment;
import de.billery.tax.VatCalculator;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates, updates and clones documents (invoices, offers, ...). Each public write method runs in
 * one database transaction (the injected {@link TransactionTemplate}): the document row, its items,
 * the reserved document number and the rendered-HTML snapshot are committed together or not at all.
 *
 * <p>Invoice types ({@link DocumentType#isInvoice}) are only created and edited as drafts here, and
 * without a document number. Issuing, payments and cancellation are {@link
 * InvoiceLifecycleService}'s job; once issued, invoices and credit notes can no longer be changed
 * ({@link DocumentStateException}).
 */
@Service
public class DocumentService {

  private static final int STATUS_DRAFT = DocumentStatus.DRAFT;

  /** User-facing reason why an issued invoice cannot be edited. */
  static final String LOCKED_MESSAGE =
      "Ausgestellte Rechnungen können nicht mehr geändert werden (GoBD)."
          + " Bitte stornieren und eine korrigierte Rechnung erstellen.";

  private final InvoiceRepository invoiceRepository;
  private final DocumentHistoryRepository historyRepository;
  private final OwnerRepository ownerRepository;
  private final DocumentNumberService documentNumberService;
  private final DocumentStrategyRegistry registry;
  private final TemplateBasedPdfGenerator pdfGenerator;

  /** Runs each write in one database transaction. */
  private final TransactionTemplate transactions;

  /**
   * Creates the service.
   *
   * @param invoiceRepository persists documents
   * @param historyRepository writes the audit trail
   * @param ownerRepository reads the company data (Kleinunternehmer rule)
   * @param documentNumberService reserves document numbers
   * @param registry the document type strategies
   * @param pdfGenerator renders the save-time HTML snapshot
   * @param transactions runs each write in one database transaction
   */
  public DocumentService(
      InvoiceRepository invoiceRepository,
      DocumentHistoryRepository historyRepository,
      OwnerRepository ownerRepository,
      DocumentNumberService documentNumberService,
      DocumentStrategyRegistry registry,
      TemplateBasedPdfGenerator pdfGenerator,
      TransactionTemplate transactions) {
    this.transactions = transactions;
    this.invoiceRepository = invoiceRepository;
    this.historyRepository = historyRepository;
    this.ownerRepository = ownerRepository;
    this.documentNumberService = documentNumberService;
    this.registry = registry;
    this.pdfGenerator = pdfGenerator;
  }

  private boolean isKleinunternehmer() {
    Owner owner = ownerRepository.findOwner();
    return owner != null && owner.isSmallBusiness();
  }

  /**
   * The tax treatment a draft gets: {@code SMALL_BUSINESS} exactly when the owner is a
   * Kleinunternehmer (§19 UStG can neither be chosen by others nor dropped by a Kleinunternehmer),
   * otherwise what the client asked for.
   */
  private TaxTreatment draftTreatment(TaxTreatment requested) {
    if (isKleinunternehmer()) {
      return TaxTreatment.SMALL_BUSINESS;
    }
    return requested == TaxTreatment.SMALL_BUSINESS ? TaxTreatment.STANDARD : requested;
  }

  /**
   * The document's default VAT rate ({@code invoice.tax_rate}): what new lines without their own
   * rate get. 0 for every treatment that charges no VAT.
   */
  private static BigDecimal defaultRate(TaxTreatment treatment, BigDecimal requested) {
    if (!treatment.chargesVat()) {
      return BigDecimal.ZERO;
    }
    return VatCalculator.isAllowedRate(requested) ? requested : BigDecimal.valueOf(19);
  }

  /**
   * Creates a document from the DTO. An empty number reserves the next number of the series; a
   * number ending in {@code /} creates the next version of that document.
   *
   * <p>Invoice types are always created as drafts without a number (it is assigned when the draft
   * is issued) and cannot be versioned. Credit notes cannot be created here at all; they result
   * from cancelling an invoice.
   *
   * @param dto the document data sent by the client
   * @return the saved document
   */
  public Invoice createDocument(InvoiceDto dto) {
    return transactions.execute(status -> doCreateDocument(dto));
  }

  private Invoice doCreateDocument(InvoiceDto dto) {
    String invoiceDate = dto.invoiceDate() != null ? dto.invoiceDate().toString() : "";
    String dueDate = dto.dueDate() != null ? dto.dueDate().toString() : "";
    String workStartDate = dto.workStartDate() != null ? dto.workStartDate().toString() : "";
    String workEndDate = dto.workEndDate() != null ? dto.workEndDate().toString() : "";
    BigDecimal totalPrice = calculateTotalFromItems(dto.items());
    TaxTreatment treatment = draftTreatment(TaxTreatment.parse(dto.taxTreatment()));
    BigDecimal taxRate = defaultRate(treatment, dto.taxRate());
    String paidDate = dto.paidDate() != null ? dto.paidDate().toString() : "";
    String notes = dto.notes() != null ? dto.notes() : "";
    int customerId = dto.customerId() != null ? dto.customerId() : 0;
    String documentNumber = dto.invoiceNumber() != null ? dto.invoiceNumber() : "";
    int documentStatusId = dto.documentStatusId() != null ? dto.documentStatusId() : 1;
    int documentTypeId = dto.documentTypeId() != null ? dto.documentTypeId() : 1;
    if (documentTypeId == DocumentType.CREDIT_NOTE) {
      throw new DocumentStateException(
          "Stornorechnungen entstehen nur durch das Stornieren einer Rechnung.");
    }
    boolean invoiceType = DocumentType.isInvoice(documentTypeId);
    if (invoiceType && documentNumber.endsWith("/")) {
      throw new DocumentStateException(LOCKED_MESSAGE);
    }

    int version = 1;
    int versionGroupId = 0;
    boolean isVersioned = false;
    Integer templateId = dto.templateId();

    DocumentTypeStrategy strategy = registry.getStrategy(documentTypeId);

    if (invoiceType) {
      // Numbered on issue (InvoiceLifecycleService.issue), so deleted drafts leave no gaps.
      documentNumber = "";
      documentStatusId = DocumentStatus.DRAFT;
      paidDate = "";
    } else if (documentNumber.endsWith("/")) {
      String baseNumber = documentNumber.substring(0, documentNumber.length() - 1);
      versionGroupId = invoiceRepository.resolveVersionGroupId(baseNumber);
      version = invoiceRepository.getNextVersion(versionGroupId);
      documentNumber = baseNumber + "/" + version;
      isVersioned = true;
      documentStatusId = 1;
    } else if (documentNumber.isEmpty()) {
      documentNumber = documentNumberService.generateDocumentNumber(documentTypeId);
      documentStatusId = strategy.getDefaultStatus();
    }

    Invoice invoice =
        new Invoice(
            0,
            customerId,
            invoiceDate,
            dueDate,
            totalPrice,
            taxRate,
            paidDate,
            notes,
            documentNumber,
            version,
            1,
            documentTypeId,
            documentStatusId,
            versionGroupId,
            workStartDate,
            workEndDate,
            0,
            0,
            0,
            "",
            "",
            "",
            0);
    // Set before the INSERT so every path (including a new version of an existing group) keeps
    // the per-document template override.
    invoice.setTemplateId(templateId);
    invoice.setTaxTreatment(treatment.name());
    invoice.setTaxExemptionReason(dto.taxExemptionReason());
    invoiceRepository.save(invoice);

    if (versionGroupId == 0) {
      // First document of its group: the group is identified by the document's own id.
      versionGroupId = invoice.getId();
      invoice.setVersionGroupId(versionGroupId);
      invoiceRepository.update(invoice);
    }
    if (isVersioned) {
      invoiceRepository.resetIsLatestForGroup(versionGroupId, invoice.getId());
    }

    saveItems(invoice.getId(), dto.items(), treatment, taxRate);
    generateAndStoreRenderedHtml(invoice);
    historyRepository.log(
        invoice.getId(), DocumentHistoryRepository.ACTION_CREATED, invoice.getDocumentNumber());

    return invoice;
  }

  /**
   * Updates a document. Fields missing from the DTO keep their stored value, except {@code
   * templateId}, where {@code null} means "use the type default". Items, when sent, replace all
   * stored items. The document number is never taken from the DTO.
   *
   * <p>Invoice types and credit notes can only be updated while they are drafts, and their status
   * and paid date are not changed here (see {@link InvoiceLifecycleService}).
   *
   * @param id the document id
   * @param dto the new data
   * @return the updated document, or {@code null} if it does not exist
   * @throws DocumentStateException if the document is an issued invoice or a credit note
   */
  public Invoice updateDocument(int id, InvoiceDto dto) {
    return transactions.execute(status -> doUpdateDocument(id, dto));
  }

  private Invoice doUpdateDocument(int id, InvoiceDto dto) {
    Invoice existing = invoiceRepository.findById(id);
    if (existing == null) {
      return null;
    }

    DocumentTypeStrategy strategy = registry.getStrategy(existing.getDocumentTypeId());
    boolean bookkeepingRecord = DocumentType.isBookkeepingRecord(existing.getDocumentTypeId());
    if (bookkeepingRecord && existing.getDocumentStatusId() != STATUS_DRAFT) {
      throw new DocumentStateException(LOCKED_MESSAGE);
    }

    int newStatus =
        dto.documentStatusId() != null ? dto.documentStatusId() : existing.getDocumentStatusId();

    if (bookkeepingRecord
        || dto.documentStatusId() != null
            && dto.documentStatusId() != existing.getDocumentStatusId()
            && !strategy.isValidTransition(existing.getDocumentStatusId(), newStatus)) {
      newStatus = existing.getDocumentStatusId();
    }

    String invoiceDate =
        dto.invoiceDate() != null ? dto.invoiceDate().toString() : existing.getInvoiceDate();
    String dueDate = dto.dueDate() != null ? dto.dueDate().toString() : existing.getDueDate();
    BigDecimal totalPrice =
        dto.items() != null ? calculateTotalFromItems(dto.items()) : existing.getTotalPrice();
    TaxTreatment treatment =
        dto.taxTreatment() != null
            ? TaxTreatment.parse(dto.taxTreatment())
            : TaxTreatment.parse(existing.getTaxTreatment());
    // Invoice drafts have no paid date; a paid date is derived from payments after issue.
    String paidDate =
        !bookkeepingRecord && dto.paidDate() != null
            ? dto.paidDate().toString()
            : existing.getPaidDate();
    String notes = dto.notes() != null ? dto.notes() : existing.getNotes();

    // The small-business rule applies to documents still being drafted. A document that was
    // already issued keeps the treatment it was issued with, even if the owner later becomes a
    // Kleinunternehmer (issued invoices cannot be updated at all; offers keep theirs here).
    if (existing.getDocumentStatusId() == STATUS_DRAFT) {
      treatment = draftTreatment(treatment);
    }
    BigDecimal taxRate =
        defaultRate(treatment, dto.taxRate() != null ? dto.taxRate() : existing.getTaxRate());
    String exemptionReason =
        dto.taxExemptionReason() != null
            ? dto.taxExemptionReason()
            : existing.getTaxExemptionReason();
    int customerId = dto.customerId() != null ? dto.customerId() : existing.getCustomerId();
    // Numbers come from the series only; a client cannot rename a document.
    String documentNumber = existing.getDocumentNumber();

    String workStartDate =
        dto.workStartDate() != null ? dto.workStartDate().toString() : existing.getWorkStartDate();
    String workEndDate =
        dto.workEndDate() != null ? dto.workEndDate().toString() : existing.getWorkEndDate();

    Invoice updated =
        new Invoice(
            id,
            customerId,
            invoiceDate,
            dueDate,
            totalPrice,
            taxRate,
            paidDate,
            notes,
            documentNumber,
            existing.getVersion(),
            existing.getIsLatest(),
            existing.getDocumentTypeId(),
            newStatus,
            existing.getVersionGroupId(),
            workStartDate,
            workEndDate,
            existing.getParentInvoiceId(),
            existing.getPeriodId(),
            existing.getConvertedToInvoiceId(),
            existing.getOfferValidUntil(),
            existing.getCostCalculationBasis(),
            existing.getTermsAndConditions(),
            existing.getArchived());
    // Template id: pass through whatever the client sent. The DTO is the source of truth for
    // the per-document override; null means "use the type default".
    updated.setTemplateId(dto.templateId());
    updated.setTaxTreatment(treatment.name());
    updated.setTaxExemptionReason(exemptionReason);
    invoiceRepository.update(updated);

    if (dto.items() != null) {
      invoiceRepository.deleteAllItemsByInvoiceId(id);
      saveItems(id, dto.items(), treatment, taxRate);
    }

    generateAndStoreRenderedHtml(updated);
    if (newStatus == existing.getDocumentStatusId()) {
      historyRepository.log(id, DocumentHistoryRepository.ACTION_UPDATED, "");
    } else {
      historyRepository.log(
          id,
          DocumentHistoryRepository.ACTION_STATUS_CHANGED,
          DocumentStatus.transitionNote(existing.getDocumentStatusId(), newStatus));
    }

    return updated;
  }

  /**
   * Copies a document (with its item tree) into a new draft with a fresh number. A copy of an
   * invoice type gets no number until it is issued; credit notes cannot be copied.
   *
   * @param sourceId the document to copy
   * @param body optional {@code invoiceDate}, {@code dueDate}, {@code workStartDate}, {@code
   *     workEndDate} overrides
   * @return the copy, or {@code null} if the source does not exist
   */
  public Invoice cloneDocument(int sourceId, Map<String, String> body) {
    return transactions.execute(status -> doCloneDocument(sourceId, body));
  }

  private Invoice doCloneDocument(int sourceId, Map<String, String> body) {
    Invoice source = invoiceRepository.findById(sourceId);
    if (source == null) {
      return null;
    }
    if (source.getDocumentTypeId() == DocumentType.CREDIT_NOTE) {
      throw new DocumentStateException("Stornorechnungen können nicht dupliziert werden.");
    }

    String invoiceDate = body.getOrDefault("invoiceDate", LocalDate.now().toString());
    String dueDate = body.getOrDefault("dueDate", "");
    String workStartDate = body.getOrDefault("workStartDate", source.getWorkStartDate());
    String workEndDate = body.getOrDefault("workEndDate", source.getWorkEndDate());
    String notes = source.getNotes();
    TaxTreatment treatment = draftTreatment(TaxTreatment.parse(source.getTaxTreatment()));
    BigDecimal taxRate = defaultRate(treatment, source.getTaxRate());
    String documentNumber =
        DocumentType.isInvoice(source.getDocumentTypeId())
            ? ""
            : documentNumberService.generateDocumentNumber(source.getDocumentTypeId());

    Invoice clone =
        new Invoice(
            0,
            source.getCustomerId(),
            invoiceDate,
            dueDate,
            source.getTotalPrice(),
            taxRate,
            "",
            notes,
            documentNumber,
            1,
            1,
            source.getDocumentTypeId(),
            1,
            0,
            workStartDate,
            workEndDate,
            0,
            0,
            0,
            source.getOfferValidUntil(),
            source.getCostCalculationBasis(),
            source.getTermsAndConditions(),
            0);
    // Inherit the per-document template override from the source. The override-template row is not
    // duplicated: the clone renders with the same override the source uses.
    clone.setTemplateId(source.getTemplateId());
    clone.setTaxTreatment(treatment.name());
    clone.setTaxExemptionReason(source.getTaxExemptionReason());
    invoiceRepository.save(clone);

    int versionGroupId = clone.getId();
    clone.setVersionGroupId(versionGroupId);
    invoiceRepository.update(clone);

    List<InvoiceItem> sourceItems = invoiceRepository.findItemsByInvoiceId(sourceId);
    for (InvoiceItem srcItem : sourceItems) {
      cloneItemTree(clone.getId(), srcItem, 0, taxRate);
    }

    generateAndStoreRenderedHtml(clone);
    historyRepository.log(
        clone.getId(),
        DocumentHistoryRepository.ACTION_CREATED,
        "Kopie von " + source.getFormattedInvoiceNumber());

    return clone;
  }

  /**
   * Stores the save-time HTML snapshot. A document can still be saved when it cannot be rendered
   * yet (e.g. no company data configured); it then has no snapshot and the PDF download renders
   * fresh, which reports the missing configuration to the user.
   */
  private void generateAndStoreRenderedHtml(Invoice invoice) {
    try {
      String renderedHtml = pdfGenerator.generateInvoiceHtml(invoice);
      invoice.setRenderedHtml(renderedHtml);
      invoiceRepository.updateRenderedHtml(invoice.getId(), renderedHtml);
    } catch (IOException | MissingConfigurationException e) {
      Logger.getLogger(DocumentService.class.getName())
          .log(Level.WARNING, "No HTML snapshot for invoice " + invoice.getId(), e);
    }
  }

  /**
   * Copies a line and its sub-items. A line whose rate is not allowed under STANDARD (e.g. 0 from a
   * Kleinunternehmer document) gets the copy's default rate.
   */
  private void cloneItemTree(
      int invoiceId, InvoiceItem source, int parentId, BigDecimal defaultRate) {
    boolean isNote = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(source.getItemType());
    InvoiceItem copy =
        new InvoiceItem(
            0,
            invoiceId,
            source.getItemType(),
            isNote ? 0 : source.getItemId(),
            isNote ? BigDecimal.ONE : source.getQuantity(),
            isNote ? BigDecimal.ZERO : source.getUnitPrice(),
            isNote ? BigDecimal.ZERO : source.getTotalPrice());
    copy.setParentItemId(parentId);
    copy.setItemName(source.getItemName() != null ? source.getItemName() : "");
    copy.setItemDescription(source.getItemDescription() != null ? source.getItemDescription() : "");
    copy.setUnit(isNote ? "" : source.getUnit());
    copy.setTaxRatePercent(
        VatCalculator.isAllowedRate(source.getTaxRatePercent())
            ? source.getTaxRatePercent()
            : defaultRate);
    invoiceRepository.saveInvoiceItem(copy);
    if (source.getChildren() != null) {
      for (InvoiceItem child : source.getChildren()) {
        cloneItemTree(invoiceId, child, copy.getId(), defaultRate);
      }
    }
  }

  /**
   * Saves an item tree depth-first.
   *
   * @param treatment the document's treatment; under STANDARD every priced line must carry 19 % or
   *     7 %
   * @param defaultRate the rate of lines that were sent without one
   * @throws DocumentStateException for a rate that is not allowed
   */
  private void saveItems(
      int invoiceId, List<InvoiceItemDto> items, TaxTreatment treatment, BigDecimal defaultRate) {
    saveChildItems(invoiceId, items, 0, treatment, defaultRate);
  }

  private void saveChildItems(
      int invoiceId,
      List<InvoiceItemDto> items,
      int parentId,
      TaxTreatment treatment,
      BigDecimal defaultRate) {
    if (items == null) {
      return;
    }
    for (InvoiceItemDto itemDto : items) {
      InvoiceItem item = buildItem(invoiceId, itemDto, treatment, defaultRate);
      item.setParentItemId(parentId);
      invoiceRepository.saveInvoiceItem(item);
      if (itemDto.children() != null && !itemDto.children().isEmpty()) {
        saveChildItems(invoiceId, itemDto.children(), item.getId(), treatment, defaultRate);
      }
    }
  }

  /**
   * Builds an {@link InvoiceItem} from a DTO, enforcing the CUSTOM invariant: a "Freitext" row
   * (item type {@code CUSTOM}) is a free-text note and must never carry a price. Any quantity,
   * unit-price or total-price value sent for a CUSTOM row is forced to neutral values; the unit
   * field is cleared.
   *
   * <p>The line's VAT rate is the sent one or the document default. Under STANDARD only 19 % and 7
   * % are accepted; under every other treatment the rate is kept but not charged.
   */
  private InvoiceItem buildItem(
      int invoiceId, InvoiceItemDto itemDto, TaxTreatment treatment, BigDecimal defaultRate) {
    String itemType = itemDto.itemType();
    boolean isNote = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(itemType);
    BigDecimal quantity =
        isNote ? BigDecimal.ONE : cents(orDefault(itemDto.quantity(), BigDecimal.ONE));
    BigDecimal unitPrice =
        isNote ? BigDecimal.ZERO : cents(orDefault(itemDto.unitPrice(), BigDecimal.ZERO));
    BigDecimal totalPrice = isNote ? BigDecimal.ZERO : lineTotal(quantity, unitPrice);
    String unit = isNote ? "" : (itemDto.unit() != null ? itemDto.unit() : "");
    int itemId = isNote ? 0 : (itemDto.itemId() != null ? itemDto.itemId() : 0);

    InvoiceItem item =
        new InvoiceItem(0, invoiceId, itemType, itemId, quantity, unitPrice, totalPrice);
    item.setUnit(unit);
    item.setItemName(itemDto.itemName() != null ? itemDto.itemName() : "");
    item.setItemDescription(itemDto.itemDescription() != null ? itemDto.itemDescription() : "");
    BigDecimal rate = orDefault(itemDto.taxRate(), defaultRate);
    if (treatment.chargesVat() && !isNote && !VatCalculator.isAllowedRate(rate)) {
      throw new DocumentStateException(
          "Ungültiger Steuersatz "
              + rate.stripTrailingZeros().toPlainString()
              + " % für \""
              + item.getItemName()
              + "\" (erlaubt: 19 %, 7 %).");
    }
    item.setTaxRatePercent(isNote ? defaultRate : rate);
    return item;
  }

  /**
   * Rounds half-up to two decimals, the precision of the {@code quantity} and {@code unit_price}
   * columns. Inputs are rounded <em>before</em> computing the line total, so a reloaded row always
   * satisfies total = quantity × unit price.
   */
  static BigDecimal cents(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Line total = quantity × unit price, rounded half-up to cents. The server always computes it; a
   * {@code totalPrice} sent by the client is ignored.
   */
  static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice) {
    return InvoiceItem.lineTotal(quantity, unitPrice);
  }

  private static BigDecimal orDefault(BigDecimal value, BigDecimal fallback) {
    return value != null ? value : fallback;
  }

  /**
   * Net sum of all items (roots and sub-items) as the server will store them: priced rows
   * contribute quantity × unit price, {@code CUSTOM} note rows contribute nothing.
   */
  public static BigDecimal calculateTotalFromItems(List<InvoiceItemDto> items) {
    return sumItemsRecursive(items).setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal sumItemsRecursive(List<InvoiceItemDto> items) {
    BigDecimal total = BigDecimal.ZERO;
    if (items == null) {
      return total;
    }
    for (InvoiceItemDto item : items) {
      boolean isNote = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(item.itemType());
      if (!isNote) {
        BigDecimal quantity = cents(orDefault(item.quantity(), BigDecimal.ONE));
        BigDecimal unitPrice = cents(orDefault(item.unitPrice(), BigDecimal.ZERO));
        total = total.add(lineTotal(quantity, unitPrice));
      }
      if (item.children() != null) {
        total = total.add(sumItemsRecursive(item.children()));
      }
    }
    return total;
  }
}
