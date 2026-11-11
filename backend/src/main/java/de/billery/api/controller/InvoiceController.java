package de.billery.api.controller;

import de.billery.api.dto.InvoiceDto;
import de.billery.api.dto.InvoiceItemDto;
import de.billery.api.dto.InvoicePageDto;
import de.billery.api.dto.PaymentRequest;
import de.billery.api.dto.StatusChangeRequest;
import de.billery.data.Customer;
import de.billery.data.DatabaseException;
import de.billery.data.DocumentHistoryEntry;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.Payment;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentStrategyRegistry;
import de.billery.document.DocumentType;
import de.billery.document.DocumentTypeStrategy;
import de.billery.pdf.MissingConfigurationException;
import de.billery.repository.CustomerRepository;
import de.billery.repository.DocumentHistoryRepository;
import de.billery.repository.DocumentNumberSeriesRepository;
import de.billery.repository.InvoiceListQuery;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.PaymentRepository;
import de.billery.repository.UserSettingsRepository;
import de.billery.service.ActorNames;
import de.billery.service.DocumentNotFoundException;
import de.billery.service.DocumentPdfService;
import de.billery.service.DocumentService;
import de.billery.service.DocumentStateException;
import de.billery.service.EInvoiceService;
import de.billery.service.InvalidStatusChangeException;
import de.billery.service.InvoiceLifecycleService;
import de.billery.statistics.InvoiceStatistics;
import de.billery.statistics.InvoiceStatisticsService;
import de.billery.tax.TaxTreatment;
import de.billery.tax.VatBreakdown;
import de.billery.tax.VatCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

  private final InvoiceRepository invoiceRepository;
  private final CustomerRepository customerRepository;
  private final PaymentRepository paymentRepository;
  private final DocumentHistoryRepository historyRepository;
  private final DocumentPdfService documentPdfService;
  private final DocumentService documentService;
  private final InvoiceLifecycleService lifecycleService;
  private final EInvoiceService eInvoiceService;
  private final DocumentStrategyRegistry registry;
  private final DocumentNumberSeriesRepository seriesRepository;
  private final UserSettingsRepository settingsRepository;
  private final InvoiceStatisticsService statisticsService;
  private final ActorNames actorNames;
  private static final Logger logger = Logger.getLogger(InvoiceController.class.getName());

  /** Default page size of {@link #getInvoicePage}. */
  static final int DEFAULT_PAGE_SIZE = 50;

  /** Largest page {@link #getInvoicePage} returns; larger requests are refused. */
  static final int MAX_PAGE_SIZE = 200;

  /**
   * Highest page number {@link #getInvoicePage} accepts. With {@link #MAX_PAGE_SIZE} the offset
   * stays at most 2 000 000, far below {@code int} overflow, and a request cannot ask the database
   * to skip an arbitrary number of rows.
   */
  static final int MAX_PAGE = 10_000;

  /** Years {@link #getInvoicePage} accepts; {@code LocalDate} and the date columns cover them. */
  static final int MIN_YEAR = 1;

  static final int MAX_YEAR = 9999;

  /** Longest period {@link #getStatistics} covers, like the GoBD export's default limit. */
  static final int MAX_STATISTICS_DAYS = 3660;

  /**
   * Creates the controller.
   *
   * @param invoiceRepository the document repository
   * @param customerRepository reads the customer names for the DTOs
   * @param paymentRepository lists the payments of a document
   * @param historyRepository the audit trail
   * @param documentPdfService renders the PDF downloads (ZUGFeRD for issued invoices)
   * @param documentService creates, updates and clones documents
   * @param lifecycleService the bookkeeping steps (issue, payments, cancel)
   * @param eInvoiceService the XRechnung downloads
   * @param registry the document type strategies
   * @param seriesRepository reads the last reserved number (next-number preview)
   * @param settingsRepository reads the number prefixes
   * @param statisticsService the statistics endpoint
   * @param actorNames names the users of history rows (SaaS); none in self-host
   */
  public InvoiceController(
      InvoiceRepository invoiceRepository,
      CustomerRepository customerRepository,
      PaymentRepository paymentRepository,
      DocumentHistoryRepository historyRepository,
      DocumentPdfService documentPdfService,
      DocumentService documentService,
      InvoiceLifecycleService lifecycleService,
      EInvoiceService eInvoiceService,
      DocumentStrategyRegistry registry,
      DocumentNumberSeriesRepository seriesRepository,
      UserSettingsRepository settingsRepository,
      InvoiceStatisticsService statisticsService,
      ActorNames actorNames) {
    this.invoiceRepository = invoiceRepository;
    this.customerRepository = customerRepository;
    this.paymentRepository = paymentRepository;
    this.historyRepository = historyRepository;
    this.documentPdfService = documentPdfService;
    this.documentService = documentService;
    this.lifecycleService = lifecycleService;
    this.eInvoiceService = eInvoiceService;
    this.registry = registry;
    this.seriesRepository = seriesRepository;
    this.settingsRepository = settingsRepository;
    this.statisticsService = statisticsService;
    this.actorNames = actorNames;
  }

  /**
   * One page of the document list, filtered and ordered in the database. The filters are the ones
   * the document list offers; the order is drafts first, then by document number (newest first).
   *
   * @param page 0-based page number, 0 to {@value #MAX_PAGE}; a page past the end is empty
   * @param size documents per page, 1 to {@value #MAX_PAGE_SIZE}
   * @param types document types to show (comma-separated ids); none means all
   * @param archived {@code true} for the archive
   * @param includeArchived {@code true} for active and archived documents together (the dashboard);
   *     overrides {@code archived}
   * @param year calendar year of the document date, {@value #MIN_YEAR} to {@value #MAX_YEAR}
   * @param from first document date (ISO), inclusive
   * @param to last document date (ISO), inclusive
   * @param q text in the document number or the customer's name
   * @param status a status filter key ({@code draft}, {@code overdue}, {@code open}, …)
   * @return the page; 400 for a page outside 0..{@value #MAX_PAGE}, a size outside 1..{@value
   *     #MAX_PAGE_SIZE}, a year outside {@value #MIN_YEAR}..{@value #MAX_YEAR}, {@code from} after
   *     {@code to}, an unparsable date or an unknown status key
   */
  @GetMapping("/page")
  public ResponseEntity<InvoicePageDto> getInvoicePage(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
      @RequestParam(required = false) List<Integer> types,
      @RequestParam(defaultValue = "false") boolean archived,
      @RequestParam(defaultValue = "false") boolean includeArchived,
      @RequestParam(required = false) Integer year,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String status) {
    if (page < 0 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
      return ResponseEntity.badRequest().build();
    }
    if (year != null && (year < MIN_YEAR || year > MAX_YEAR)) {
      return ResponseEntity.badRequest().build();
    }
    if (from != null && to != null && from.isAfter(to)) {
      return ResponseEntity.badRequest().build();
    }
    if (status != null && !status.isBlank() && !InvoiceListQuery.STATUS_FILTERS.contains(status)) {
      return ResponseEntity.badRequest().build();
    }
    InvoiceListQuery query =
        new InvoiceListQuery(
            types, includeArchived ? null : archived, year, from, to, q, status, LocalDate.now());
    List<Invoice> invoices =
        invoiceRepository.findPage(query, page * size, size); // at most 10 000 × 200
    return ResponseEntity.ok(
        new InvoicePageDto(
            convertPageToDto(invoices),
            page,
            size,
            invoiceRepository.count(query),
            invoiceRepository.findYears(archived)));
  }

  /**
   * The document list's call without date range: {@link #getInvoicePage(int, int, List, boolean,
   * boolean, Integer, LocalDate, LocalDate, String, String)} for active or archived documents.
   *
   * @param page 0-based page number
   * @param size documents per page
   * @param types document types to show
   * @param archived {@code true} for the archive
   * @param year calendar year of the document date
   * @param q text in the document number or the customer's name
   * @param status a status filter key
   * @return the page
   */
  public ResponseEntity<InvoicePageDto> getInvoicePage(
      int page,
      int size,
      List<Integer> types,
      boolean archived,
      Integer year,
      String q,
      String status) {
    return getInvoicePage(page, size, types, archived, false, year, null, null, q, status);
  }

  /**
   * Revenue, tax and counts of the issued invoices and credit notes of a period (the dashboard).
   *
   * @param from first document date, ISO {@code yyyy-MM-dd} (default: 1 January of this year)
   * @param to last document date (default: today)
   * @return the statistics; 400 for an unparsable date, {@code from} after {@code to} or a period
   *     longer than {@value #MAX_STATISTICS_DAYS} days. Database failures answer 500 through the
   *     {@code ApiExceptionHandler}.
   */
  @GetMapping("/statistics")
  public ResponseEntity<InvoiceStatistics> getStatistics(
      @RequestParam(required = false) String from, @RequestParam(required = false) String to) {
    LocalDate startDate;
    LocalDate endDate;
    try {
      startDate =
          from != null && !from.isEmpty()
              ? LocalDate.parse(from)
              : LocalDate.now().withDayOfYear(1);
      endDate = to != null && !to.isEmpty() ? LocalDate.parse(to) : LocalDate.now();
    } catch (DateTimeParseException e) {
      return ResponseEntity.badRequest().build();
    }
    if (startDate.isAfter(endDate) || startDate.plusDays(MAX_STATISTICS_DAYS).isBefore(endDate)) {
      return ResponseEntity.badRequest().build();
    }
    return ResponseEntity.ok(statisticsService.generateStatistics(startDate, endDate));
  }

  @GetMapping("/{id}")
  public ResponseEntity<InvoiceDto> getInvoice(@PathVariable Integer id) {
    Invoice invoice = invoiceRepository.findById(id);
    if (invoice == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(convertToDto(invoice));
  }

  @GetMapping("/next-number")
  public ResponseEntity<String> getNextInvoiceNumber(
      @RequestParam(required = false, defaultValue = "1") int documentTypeId) {
    int year = LocalDate.now().getYear();
    int lastNumber = seriesRepository.getLastNumber(documentTypeId, year);
    String prefix = settingsRepository.getPrefixForDocumentType(documentTypeId);
    String nextNumber = String.format("%s-%d-%03d", prefix, year, lastNumber + 1);
    return ResponseEntity.ok(nextNumber);
  }

  @GetMapping("/by-customer/{customerId}")
  public List<InvoiceDto> getInvoicesByCustomer(@PathVariable Integer customerId) {
    return convertAllToDto(
        invoiceRepository.findByCustomerId(customerId),
        invoiceRepository.findItemsGroupedByInvoiceForCustomer(customerId));
  }

  @PostMapping
  public InvoiceDto createInvoice(@RequestBody InvoiceDto dto) {
    Invoice invoice = documentService.createDocument(dto);
    return convertToDto(invoice);
  }

  @PutMapping("/{id}")
  public ResponseEntity<InvoiceDto> updateInvoice(
      @PathVariable Integer id, @RequestBody InvoiceDto dto) {
    Invoice updated = documentService.updateDocument(id, dto);
    if (updated == null) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok(convertToDto(updated));
  }

  @PostMapping("/{id}/clone")
  public ResponseEntity<InvoiceDto> cloneInvoice(
      @PathVariable Integer id, @RequestBody Map<String, String> body) {
    try {
      Invoice cloned = documentService.cloneDocument(id, body);
      if (cloned == null) {
        return ResponseEntity.notFound().build();
      }
      return ResponseEntity.ok(convertToDto(cloned));
    } catch (IllegalArgumentException e) {
      return ResponseEntity.badRequest().build();
    }
  }

  /**
   * Changes a document's status. For invoice types the allowed targets run the bookkeeping steps of
   * {@link InvoiceLifecycleService}: SENT issues the draft (assigns the number), PAID records the
   * open amount as one payment on {@code paymentDate}, CANCELLED creates a credit note. Offers and
   * cost calculations just change their status (see {@link de.billery.document.OfferStrategy} for
   * the lifecycle); asking for the status they already have changes nothing and answers 200.
   *
   * @param id the document
   * @param body the requested status (and payment date / conversion target where needed)
   * @return the updated document; 400 for a transition the document type does not allow, for offers
   *     and cost calculations with a JSON {@code message} naming the statuses that are possible
   */
  @PatchMapping("/{id}/status")
  public ResponseEntity<InvoiceDto> updateInvoiceStatus(
      @PathVariable Integer id, @RequestBody StatusChangeRequest body) {
    Integer newStatus = body != null ? body.documentStatusId() : null;
    if (newStatus == null) {
      return ResponseEntity.badRequest().build();
    }
    Invoice invoice = invoiceRepository.findById(id);
    if (invoice == null) {
      return ResponseEntity.notFound().build();
    }
    DocumentTypeStrategy strategy = registry.getStrategy(invoice.getDocumentTypeId());
    int current = invoice.getDocumentStatusId();
    boolean quote =
        invoice.getDocumentTypeId() == DocumentType.OFFER
            || invoice.getDocumentTypeId() == DocumentType.COST_CALCULATION;
    if (quote && newStatus == current) {
      return ResponseEntity.ok(convertToDto(invoice));
    }
    if (!strategy.isValidTransition(current, newStatus)) {
      if (quote) {
        throw new InvalidStatusChangeException(
            refusedTransitionMessage(strategy, current, newStatus));
      }
      return ResponseEntity.badRequest().build();
    }
    if (DocumentType.isInvoice(invoice.getDocumentTypeId())) {
      switch (newStatus) {
        case DocumentStatus.SENT -> lifecycleService.issue(id);
        case DocumentStatus.PAID -> {
          if (body.paymentDate() == null) {
            throw new DocumentStateException("Bitte das Zahlungsdatum angeben.");
          }
          lifecycleService.recordFullPayment(id, body.paymentDate());
        }
        case DocumentStatus.CANCELLED -> lifecycleService.cancel(id);
        default -> {
          return ResponseEntity.badRequest().build();
        }
      }
    } else {
      invoiceRepository.updateStatus(id, newStatus, null);
      if (newStatus == DocumentStatus.CONVERTED && body.convertedToInvoiceId() != null) {
        invoiceRepository.setConvertedToInvoiceId(id, body.convertedToInvoiceId());
      }
      historyRepository.log(
          id,
          DocumentHistoryRepository.ACTION_STATUS_CHANGED,
          DocumentStatus.transitionNote(current, newStatus));
    }
    return ResponseEntity.ok(convertToDto(invoiceRepository.findById(id)));
  }

  /** Explains a refused offer / cost calculation status change and names what is possible. */
  private static String refusedTransitionMessage(DocumentTypeStrategy strategy, int from, int to) {
    List<String> possible =
        strategy.getAvailableStatuses(from).stream()
            .filter(status -> status != from)
            .map(DocumentStatus::germanName)
            .toList();
    String hint =
        possible.isEmpty()
            ? "Von diesem Status aus ist kein Wechsel mehr möglich."
            : "Möglich: " + String.join(", ", possible) + ".";
    return "Der Status „"
        + DocumentStatus.germanName(from)
        + "“ kann nicht in „"
        + DocumentStatus.germanName(to)
        + "“ geändert werden. "
        + hint;
  }

  /**
   * Cancels an issued invoice by creating a credit note (Stornorechnung).
   *
   * @param id the invoice
   * @return the credit note
   */
  @PostMapping("/{id}/cancel")
  public InvoiceDto cancelInvoice(@PathVariable Integer id) {
    return convertToDto(lifecycleService.cancel(id));
  }

  /**
   * Lists the payments of a document, reversals included.
   *
   * @param id the document
   * @return the payments, oldest first
   */
  @GetMapping("/{id}/payments")
  public List<Payment> getPayments(@PathVariable Integer id) {
    requireDocument(id);
    return paymentRepository.findByInvoiceId(id);
  }

  /**
   * Records a payment for an issued, open invoice; the status follows from the paid sum.
   *
   * @param id the invoice
   * @param body date, amount and optional details
   * @return the saved payment
   */
  @PostMapping("/{id}/payments")
  public Payment recordPayment(@PathVariable Integer id, @RequestBody PaymentRequest body) {
    return lifecycleService.recordPayment(
        id,
        body.paymentDate(),
        body.amount(),
        body.paymentMethod(),
        body.reference(),
        body.notes());
  }

  /**
   * Reverses a mistaken payment with a negative counter-entry (payments are never deleted).
   *
   * @param id the invoice
   * @param paymentId the payment to reverse
   * @return the reversal row
   */
  @PostMapping("/{id}/payments/{paymentId}/reverse")
  public Payment reversePayment(@PathVariable Integer id, @PathVariable Integer paymentId) {
    return lifecycleService.reversePayment(id, paymentId);
  }

  /**
   * Downloads the XRechnung (UBL XML) of an issued invoice or credit note. Answers 409 with the
   * missing fields when the master data is incomplete.
   *
   * @param id the document
   * @return the XML as attachment
   */
  @GetMapping("/{id}/xrechnung")
  public ResponseEntity<byte[]> downloadXRechnung(@PathVariable Integer id) {
    String xml = eInvoiceService.xrechnung(id);
    Invoice invoice = invoiceRepository.findById(id);
    String filename =
        "XRechnung_"
            + invoice.getFormattedInvoiceNumber().replaceAll("[^A-Za-z0-9._-]", "_")
            + ".xml";
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_XML)
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
        .body(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /**
   * Lists what an XRechnung of this document still lacks (for drafts: before issuing).
   *
   * @param id the document
   * @return {@code {"missing": [...]}}, empty when complete
   */
  @GetMapping("/{id}/xrechnung/check")
  public Map<String, List<String>> checkXRechnung(@PathVariable Integer id) {
    return Map.of("missing", eInvoiceService.check(id));
  }

  /**
   * Returns a document's audit trail.
   *
   * @param id the document
   * @return the entries, oldest first
   */
  @GetMapping("/{id}/history")
  public List<DocumentHistoryEntry> getHistory(@PathVariable Integer id) {
    requireDocument(id);
    List<DocumentHistoryEntry> entries = historyRepository.findByInvoiceId(id);
    Map<String, String> names =
        actorNames.namesOf(
            entries.stream()
                .map(DocumentHistoryEntry::performedBy)
                .filter(java.util.Objects::nonNull)
                .toList());
    return entries.stream()
        // Self-host rows have no actor; an immutable map rejects a null key.
        .map(
            entry ->
                entry.performedBy() == null
                    ? entry
                    : entry.withPerformedByName(names.get(entry.performedBy())))
        .toList();
  }

  /**
   * Deletes a draft. Issued documents answer 409: they must be retained and are archived or
   * cancelled instead.
   *
   * @param id the document
   * @return 200, 404 or 409
   */
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteInvoice(@PathVariable Integer id) {
    lifecycleService.deleteDraft(id);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/{id}/archive")
  public ResponseEntity<Void> archiveInvoice(@PathVariable Integer id) {
    if (invoiceRepository.findById(id) == null) {
      return ResponseEntity.notFound().build();
    }
    invoiceRepository.archive(id);
    return ResponseEntity.ok().build();
  }

  @PostMapping("/{id}/unarchive")
  public ResponseEntity<Void> unarchiveInvoice(@PathVariable Integer id) {
    if (invoiceRepository.findById(id) == null) {
      return ResponseEntity.notFound().build();
    }
    invoiceRepository.unarchive(id);
    return ResponseEntity.ok().build();
  }

  @GetMapping("/{id}/pdf")
  public ResponseEntity<Resource> downloadInvoicePdf(@PathVariable Integer id) {
    try {
      Invoice invoice = invoiceRepository.findById(id);
      if (invoice == null) {
        return ResponseEntity.notFound().build();
      }
      // PDF/A-3; an issued invoice or credit note with complete data is a ZUGFeRD invoice (the
      // XRechnung attached). The rules which rendering a document gets are in DocumentPdfService.
      byte[] pdfContent = documentPdfService.pdf(invoice);
      ByteArrayResource resource = new ByteArrayResource(pdfContent);
      String filename =
          String.format(
              "%s_%s.pdf",
              registry.getStrategy(invoice.getDocumentTypeId()).getDisplayName(),
              invoice.getFormattedInvoiceNumber().replaceAll("[^A-Za-z0-9._-]", "_"));
      return ResponseEntity.ok()
          .contentType(MediaType.APPLICATION_PDF)
          .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
          .body(resource);
    } catch (MissingConfigurationException | DatabaseException | DocumentNotFoundException e) {
      // Handled by ApiExceptionHandler (409 with a user-facing message / 500).
      throw e;
    } catch (Exception e) {
      logger.log(Level.SEVERE, "Error generating PDF for invoice " + id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Converts one document, loading its customer, items, payments and credit note individually. List
   * endpoints use {@link #convertAllToDto} instead.
   */
  private InvoiceDto convertToDto(Invoice invoice) {
    BigDecimal paidAmount =
        hasPayments(invoice)
            ? lifecycleService.paidAmount(invoice.getId())
            : BigDecimal.ZERO.setScale(2);
    return toDto(
        invoice,
        customerName(customerRepository.findById(invoice.getCustomerId())),
        invoiceRepository.findItemsByInvoiceId(invoice.getId()),
        paidAmount,
        creditNoteIdOf(invoice));
  }

  /**
   * Converts a list of documents with a fixed number of queries (customers, payment sums, credit
   * notes; the items are passed in) instead of several queries per document — the document list has
   * well over a thousand rows.
   *
   * @param invoices the documents to convert
   * @param itemsByInvoice document id → root items, covering at least {@code invoices}
   * @return the DTOs in the order of {@code invoices}
   */
  private List<InvoiceDto> convertAllToDto(
      List<Invoice> invoices, Map<Integer, List<InvoiceItem>> itemsByInvoice) {
    return convertAllToDto(
        invoices,
        itemsByInvoice,
        customerRepository.findAll(),
        paymentRepository.sumsByInvoice(),
        invoiceRepository.findCreditNoteIdsByCancelledInvoice());
  }

  /**
   * Converts one page of documents; every query is restricted to the page's documents and
   * customers, so the cost depends on the page size, not on the number of stored documents.
   */
  private List<InvoiceDto> convertPageToDto(List<Invoice> invoices) {
    List<Integer> ids = invoices.stream().map(Invoice::getId).toList();
    List<Integer> customerIds = invoices.stream().map(Invoice::getCustomerId).distinct().toList();
    return convertAllToDto(
        invoices,
        invoiceRepository.findItemsGroupedByInvoiceIds(ids),
        customerRepository.findByIds(customerIds),
        paymentRepository.sumsByInvoiceIds(ids),
        invoiceRepository.findCreditNoteIdsForInvoices(ids));
  }

  /** The shared conversion; the callers decide how much related data to load. */
  private List<InvoiceDto> convertAllToDto(
      List<Invoice> invoices,
      Map<Integer, List<InvoiceItem>> itemsByInvoice,
      List<Customer> customers,
      Map<Integer, BigDecimal> paidByInvoice,
      Map<Integer, Integer> creditNotes) {
    Map<Integer, String> customerNames = new HashMap<>();
    for (Customer customer : customers) {
      customerNames.put(customer.getId(), customerName(customer));
    }
    BigDecimal zero = BigDecimal.ZERO.setScale(2);

    List<InvoiceDto> result = new ArrayList<>(invoices.size());
    for (Invoice invoice : invoices) {
      BigDecimal paidAmount =
          hasPayments(invoice) ? paidByInvoice.getOrDefault(invoice.getId(), zero) : zero;
      Integer cancelledBy = isCancelledInvoice(invoice) ? creditNotes.get(invoice.getId()) : null;
      result.add(
          toDto(
              invoice,
              customerNames.getOrDefault(invoice.getCustomerId(), ""),
              itemsByInvoice.getOrDefault(invoice.getId(), List.of()),
              paidAmount,
              cancelledBy));
    }
    return result;
  }

  /** "First Last" of a customer, or {@code ""} when there is none. */
  private static String customerName(Customer customer) {
    if (customer == null) {
      return "";
    }
    String firstName = customer.getFirstName() != null ? customer.getFirstName() : "";
    String lastName = customer.getLastName() != null ? customer.getLastName() : "";
    return firstName + " " + lastName;
  }

  /** Only issued invoice types carry payments; drafts and offers always show 0. */
  private static boolean hasPayments(Invoice invoice) {
    return DocumentType.isInvoice(invoice.getDocumentTypeId())
        && invoice.getDocumentStatusId() != DocumentStatus.DRAFT;
  }

  /** Only a cancelled invoice type can have a credit note pointing at it. */
  private static boolean isCancelledInvoice(Invoice invoice) {
    return invoice.getDocumentStatusId() == DocumentStatus.CANCELLED
        && DocumentType.isInvoice(invoice.getDocumentTypeId());
  }

  /** Builds the DTO from a document and its already loaded related data. */
  private InvoiceDto toDto(
      Invoice invoice,
      String customerName,
      List<InvoiceItem> items,
      BigDecimal paidAmount,
      Integer cancelledBy) {
    List<InvoiceItemDto> itemDtos = convertItemsToDto(items);

    LocalDate invoiceDate =
        invoice.getInvoiceDate() != null && !invoice.getInvoiceDate().isEmpty()
            ? LocalDate.parse(invoice.getInvoiceDate())
            : null;
    LocalDate dueDate =
        invoice.getDueDate() != null && !invoice.getDueDate().isEmpty()
            ? LocalDate.parse(invoice.getDueDate())
            : null;
    LocalDate workStartDate =
        invoice.getWorkStartDate() != null && !invoice.getWorkStartDate().isEmpty()
            ? LocalDate.parse(invoice.getWorkStartDate())
            : null;
    LocalDate workEndDate =
        invoice.getWorkEndDate() != null && !invoice.getWorkEndDate().isEmpty()
            ? LocalDate.parse(invoice.getWorkEndDate())
            : null;
    TaxTreatment treatment = TaxTreatment.parse(invoice.getTaxTreatment());
    VatBreakdown vat = VatCalculator.calculate(items, treatment);

    return new InvoiceDto(
        invoice.getId(),
        invoice.getCustomerId(),
        customerName,
        invoice.getFormattedInvoiceNumber(),
        invoice.getDocumentStatusId(),
        invoiceDate,
        dueDate,
        vat.net(),
        invoice.getTaxRate(),
        vat.tax(),
        vat.gross(),
        invoice.getPaidDateAsLocalDate(),
        invoice.getNotes(),
        invoice.getDocumentTypeId(),
        itemDtos,
        invoice.isArchived(),
        workStartDate,
        workEndDate,
        invoice.getTemplateId(),
        paidAmount,
        invoice.getCancelsInvoiceId() > 0 ? invoice.getCancelsInvoiceId() : null,
        cancelledBy,
        treatment.name(),
        invoice.getTaxExemptionReason(),
        vat.groups());
  }

  /** The id of the credit note that cancelled this invoice, or {@code null}. */
  private Integer creditNoteIdOf(Invoice invoice) {
    if (!isCancelledInvoice(invoice)) {
      return null;
    }
    Invoice creditNote = invoiceRepository.findCreditNoteFor(invoice.getId());
    return creditNote == null ? null : creditNote.getId();
  }

  private Invoice requireDocument(int id) {
    Invoice invoice = invoiceRepository.findById(id);
    if (invoice == null) {
      throw new DocumentNotFoundException(id);
    }
    return invoice;
  }

  private List<InvoiceItemDto> convertItemsToDto(List<InvoiceItem> items) {
    List<InvoiceItemDto> result = new ArrayList<>();
    for (InvoiceItem item : items) {
      List<InvoiceItemDto> children = new ArrayList<>();
      List<InvoiceItem> itemChildren = item.getChildren();
      if (itemChildren != null && !itemChildren.isEmpty()) {
        children = convertItemsToDto(itemChildren);
      }
      result.add(
          new InvoiceItemDto(
              item.getId(),
              item.getInvoiceId(),
              item.getItemType(),
              item.getItemId(),
              item.getItemName(),
              item.getItemDescription(),
              item.getUnit(),
              item.getQuantity(),
              item.getUnitPrice(),
              item.getTotalPrice(),
              item.getParentItemId() > 0 ? item.getParentItemId() : null,
              children,
              item.getTaxRatePercent()));
    }
    return result;
  }
}
