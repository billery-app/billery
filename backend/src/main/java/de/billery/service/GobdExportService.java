package de.billery.service;

import de.billery.api.error.ErrorCode;
import de.billery.data.Customer;
import de.billery.data.DocumentHistoryEntry;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.data.Payment;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentStrategyRegistry;
import de.billery.document.DocumentType;
import de.billery.repository.CustomerRepository;
import de.billery.repository.DocumentHistoryRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.repository.PaymentRepository;
import de.billery.tax.TaxTreatment;
import de.billery.tax.VatBreakdown;
import de.billery.tax.VatCalculator;
import de.billery.tenant.CurrentTenant;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Data export for a tax audit (Datenzugriff nach §147 Abs. 6 AO, "Z3"): every issued invoice and
 * credit note of a period with its lines, payments and audit trail, as a ZIP file.
 *
 * <p>Contents:
 *
 * <ul>
 *   <li>{@code belege.csv}, {@code positionen.csv}, {@code zahlungen.csv}, {@code historie.csv},
 *       {@code kunden.csv}: semicolon-separated, UTF-8, decimal comma, dates {@code DD.MM.YYYY}.
 *   <li>{@code index.xml}: table description following the GDPdU description standard
 *       (gdpdu-01-09-2004.dtd), which audit software such as IDEA reads to import the CSV files.
 *   <li>{@code dokumente/}: the PDF of each document from its frozen snapshot and, where the master
 *       data allows, its XRechnung (optional, can be slow for many documents).
 * </ul>
 *
 * <p>The period selects documents by their document date. Drafts, offers and cost calculations are
 * not business records and are not exported.
 */
@Service
public class GobdExportService {

  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final String SEP = ";";
  private static final String EOL = "\r\n";

  /** Documents per batch query while the tables are written. */
  private static final int CHUNK = 500;

  private final InvoiceRepository invoiceRepository;
  private final PaymentRepository paymentRepository;
  private final DocumentHistoryRepository historyRepository;
  private final CustomerRepository customerRepository;
  private final DocumentStrategyRegistry registry = new DocumentStrategyRegistry();
  private final EInvoiceService eInvoiceService;
  private final DocumentPdfService documentPdfService;
  private final CurrentTenant currentTenant;
  private final PlatformTransactionManager transactionManager;

  /** The tenants with an export under way; guarded by {@code this}. */
  private final Set<Integer> exportingTenants = new HashSet<>();

  private int maxDocuments = 10_000;
  private int maxDocumentsWithFiles = 5_000;
  private long maxOutputBytes = 1024L * 1024 * 1024;
  private int maxPeriodDays = 3_660;
  private int maxConcurrentExports = 2;
  private Duration maxDuration = Duration.ofMinutes(30);

  /**
   * Creates the service.
   *
   * @param invoiceRepository reads the documents and their line items
   * @param paymentRepository reads the payments
   * @param historyRepository reads the audit trail
   * @param customerRepository reads the customer master data
   * @param eInvoiceService produces the XRechnung of each document
   * @param documentPdfService renders the PDF of each document (ZUGFeRD where possible)
   * @param currentTenant whose export it is (one at a time per tenant)
   * @param transactions whose transaction manager holds one database snapshot per export
   */
  public GobdExportService(
      InvoiceRepository invoiceRepository,
      PaymentRepository paymentRepository,
      DocumentHistoryRepository historyRepository,
      CustomerRepository customerRepository,
      EInvoiceService eInvoiceService,
      DocumentPdfService documentPdfService,
      CurrentTenant currentTenant,
      TransactionTemplate transactions) {
    this.invoiceRepository = invoiceRepository;
    this.paymentRepository = paymentRepository;
    this.historyRepository = historyRepository;
    this.customerRepository = customerRepository;
    this.eInvoiceService = eInvoiceService;
    this.documentPdfService = documentPdfService;
    this.currentTenant = currentTenant;
    this.transactionManager = transactions.getTransactionManager();
  }

  /**
   * Sets the limits that keep one export from exhausting the database, CPU and the client's
   * patience. Spring injects the values from {@code export.*} (see application.properties); without
   * Spring the defaults above apply. The ZIP itself is streamed, so no limit protects the heap.
   *
   * @param maxDocuments most documents in one export
   * @param maxDocumentsWithFiles most documents when PDFs and XRechnungs are rendered as well
   * @param maxOutputBytes largest ZIP one export may send; beyond it the export breaks off (the
   *     size is only known while streaming, so this cannot answer 413 any more)
   * @param maxPeriodDays longest period between {@code from} and {@code to}, in days
   * @param maxConcurrentExports exports that may run at the same time on this instance; each tenant
   *     has at most one of them, so one tenant cannot take every slot (while streaming, a slow
   *     client holds its slot)
   * @param maxDurationMinutes longest time one export may take from {@link #prepare} on, transfer
   *     included; also the timeout of its database transaction (every query gets the remaining
   *     time). A client that stops reading keeps only the sender thread ({@link ExportSender}),
   *     never the snapshot, the connection or the slot, beyond it
   */
  @Autowired
  public void configureLimits(
      @Value("${export.max-documents:10000}") int maxDocuments,
      @Value("${export.max-documents-with-files:5000}") int maxDocumentsWithFiles,
      @Value("${export.max-output-bytes:1073741824}") long maxOutputBytes,
      @Value("${export.max-period-days:3660}") int maxPeriodDays,
      @Value("${export.max-concurrent:2}") int maxConcurrentExports,
      @Value("${export.max-duration-minutes:30}") int maxDurationMinutes) {
    this.maxDocuments = maxDocuments;
    this.maxDocumentsWithFiles = maxDocumentsWithFiles;
    this.maxOutputBytes = maxOutputBytes;
    this.maxPeriodDays = maxPeriodDays;
    this.maxConcurrentExports = maxConcurrentExports;
    this.maxDuration = Duration.ofMinutes(maxDurationMinutes);
  }

  /**
   * Sets the time limit of the next exports more finely than {@link #configureLimits} (whole
   * minutes, as configured); used by tests that need a limit of seconds.
   *
   * @param maxDuration longest time one export may take from {@link #prepare} on, transfer included
   */
  public void limitDuration(Duration maxDuration) {
    this.maxDuration = maxDuration;
  }

  /**
   * Checks the request and takes an export slot, before anything is sent: a refusal still answers
   * with a proper status (413 / 429). The returned export holds the slot until it is closed.
   *
   * <p>The export reads in one read-only {@code REPEATABLE READ} transaction from here until {@link
   * PreparedExport#close()}, so documents, sums, payments, history and PDFs are one consistent
   * state even while others keep working. Prepare, write and close on the same thread (the
   * transaction is bound to it).
   *
   * @param from first document date (inclusive)
   * @param to last document date (inclusive)
   * @param includeDocuments also add the PDF and XRechnung of every document
   * @return the export, ready to be written; always close it
   * @throws ExportLimitException if the period or the document count exceeds a limit
   * @throws ExportBusyException if this tenant already exports, or all export slots are taken
   */
  public PreparedExport prepare(LocalDate from, LocalDate to, boolean includeDocuments) {
    if (from.plusDays(maxPeriodDays).isBefore(to)) {
      throw new ExportLimitException(ErrorCode.EXPORT_PERIOD_TOO_LONG.with("max", maxPeriodDays));
    }
    int tenant = takeSlot();
    long deadline = System.nanoTime() + maxDuration.toNanos();
    TransactionStatus snapshot;
    try {
      snapshot = transactionManager.getTransaction(snapshotDefinition(maxDuration));
    } catch (RuntimeException e) {
      releaseSlot(tenant);
      throw e;
    }
    try {
      if (!invoiceRepository.tryLockForExport(tenant)) {
        throw new ExportBusyException(ErrorCode.EXPORT_ALREADY_RUNNING.with());
      }
      List<Invoice> documents = documentsIn(from, to);
      checkDocumentCount(documents.size(), includeDocuments);
      return new PreparedExport(tenant, snapshot, deadline, from, to, includeDocuments, documents);
    } catch (RuntimeException e) {
      end(snapshot, tenant);
      throw e;
    }
  }

  /** Ends the export's snapshot (it only read; this also frees the export lock) and its slot. */
  private void end(TransactionStatus snapshot, int tenant) {
    try {
      if (!snapshot.isCompleted()) {
        transactionManager.rollback(snapshot);
      }
    } finally {
      releaseSlot(tenant);
    }
  }

  /**
   * The export's transaction: read-only, {@code REPEATABLE READ} (one snapshot for every table and
   * file) and with the export's time limit as timeout, so every query gets the remaining time as
   * its query timeout and none can hold the snapshot past the deadline.
   */
  private static TransactionDefinition snapshotDefinition(Duration maxDuration) {
    DefaultTransactionDefinition definition = new DefaultTransactionDefinition();
    definition.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    definition.setReadOnly(true);
    definition.setTimeout((int) Math.max(1, Math.min(Integer.MAX_VALUE, maxDuration.toSeconds())));
    definition.setName("GoBD export");
    return definition;
  }

  /** The refusal when the export took longer than allowed. */
  static ExportLimitException tooLong() {
    return new ExportLimitException(ErrorCode.EXPORT_TOO_SLOW.with());
  }

  private synchronized int takeSlot() {
    int tenant = currentTenant.tenantId();
    if (exportingTenants.contains(tenant)) {
      throw new ExportBusyException(ErrorCode.EXPORT_ALREADY_RUNNING.with());
    }
    if (exportingTenants.size() >= maxConcurrentExports) {
      throw new ExportBusyException(ErrorCode.EXPORT_TOO_MANY.with());
    }
    exportingTenants.add(tenant);
    return tenant;
  }

  private synchronized void releaseSlot(int tenant) {
    exportingTenants.remove(tenant);
  }

  /**
   * One checked export that holds an export slot until {@link #close()}. {@link #writeTo} streams
   * the ZIP: only one document's files and one chunk of rows are in memory at a time.
   */
  public final class PreparedExport implements AutoCloseable {
    private final int tenant;
    private final TransactionStatus snapshot;
    private final long deadline;
    private final LocalDate from;
    private final LocalDate to;
    private final boolean includeDocuments;
    private final List<Invoice> documents;
    private ExportSender sender;
    private boolean closed;

    private PreparedExport(
        int tenant,
        TransactionStatus snapshot,
        long deadline,
        LocalDate from,
        LocalDate to,
        boolean includeDocuments,
        List<Invoice> documents) {
      this.tenant = tenant;
      this.snapshot = snapshot;
      this.deadline = deadline;
      this.from = from;
      this.to = to;
      this.includeDocuments = includeDocuments;
      this.documents = documents;
    }

    /**
     * How many documents the export contains (issued invoices and credit notes of the period).
     *
     * @return the number checked against the limits in {@link #prepare}
     */
    public int documentCount() {
      return documents.size();
    }

    /**
     * Writes the ZIP to {@code out} as it is produced. If anything fails on the way, the ZIP is
     * left without its central directory: a cut-off export is never a readable, seemingly complete
     * archive (the export must stay complete). {@code out} is not closed.
     *
     * @param out where the ZIP goes (the HTTP response)
     * @throws IOException if writing fails (e.g. the client went away)
     * @throws ExportLimitException if the ZIP grows beyond the output limit or takes longer than
     *     allowed (it breaks off)
     */
    public void writeTo(OutputStream out) throws IOException {
      if (sender != null || closed) {
        throw new IllegalStateException("An export is written once");
      }
      sender = new ExportSender(out, deadline);
      CuttableStream guard = new CuttableStream(sender, maxOutputBytes, deadline);
      // Closing releases the deflater; it does not close out.
      try (ZipOutputStream zip = new ZipOutputStream(guard, StandardCharsets.UTF_8)) {
        boolean complete = false;
        try {
          withinTimeLimit(
              () -> {
                writeTables(zip, documents);
                put(zip, "index.xml", GobdIndex.xml(from, to).getBytes(StandardCharsets.UTF_8));
                if (includeDocuments) {
                  addDocuments(zip, documents);
                }
                zip.finish();
              });
          complete = true;
        } finally {
          if (!complete) {
            guard.cut(); // before the close: no central directory reaches the client
          }
        }
      }
      // Everything is read: give the snapshot and its connection back before the transfer ends.
      transactionManager.rollback(snapshot);
      sender.finish();
    }

    /**
     * Ends the snapshot, releases the export slot and the export lock, then waits until the sender
     * is done with the client's stream (once). Call it on the thread that prepared the export.
     */
    @Override
    // The sender outlives writeTo on purpose: it is closed only after the snapshot and the slot.
    @SuppressWarnings("PMD.UseTryWithResources")
    public void close() {
      if (!closed) {
        closed = true;
        try {
          end(snapshot, tenant);
        } finally {
          if (sender != null) {
            sender.close();
          }
        }
      }
    }
  }

  private void checkDocumentCount(int count, boolean includeDocuments) {
    if (count > maxDocuments) {
      throw new ExportLimitException(
          ErrorCode.EXPORT_TOO_MANY_DOCUMENTS.with("count", count, "max", maxDocuments));
    }
    if (includeDocuments && count > maxDocumentsWithFiles) {
      throw new ExportLimitException(
          ErrorCode.EXPORT_TOO_MANY_WITH_FILES.with("count", count, "max", maxDocumentsWithFiles));
    }
  }

  /**
   * Writes the five CSV tables, one pass over the documents each, in chunks of {@link #CHUNK}
   * documents with one query per chunk where the repositories offer it.
   */
  private void writeTables(ZipOutputStream zip, List<Invoice> documents) throws IOException {
    Map<Integer, String> numbers = new HashMap<>();
    documents.forEach(d -> numbers.put(d.getId(), d.getDocumentNumber()));
    Set<Integer> customerIds = new LinkedHashSet<>();

    table(
        zip,
        "belege.csv",
        new String[] {
          "Beleg_ID",
          "Belegart",
          "Belegnummer",
          "Status",
          "Belegdatum",
          "Faellig",
          "Leistungsbeginn",
          "Leistungsende",
          "Kunden_ID",
          "Netto",
          "Steuer",
          "Brutto",
          "Bezahlt",
          "Besteuerung",
          "Befreiungsgrund",
          "Storno_zu"
        },
        out -> {
          for (List<Invoice> chunk : chunks(documents)) {
            List<Integer> ids = ids(chunk);
            Map<Integer, List<InvoiceItem>> items =
                invoiceRepository.findItemsGroupedByInvoiceIds(ids);
            Map<Integer, BigDecimal> paid = paymentRepository.sumsByInvoiceIds(ids);
            for (Invoice doc : chunk) {
              VatBreakdown vat =
                  VatCalculator.calculate(
                      items.getOrDefault(doc.getId(), List.of()),
                      TaxTreatment.parse(doc.getTaxTreatment()));
              String cancels =
                  doc.getCancelsInvoiceId() > 0
                      ? numbers.computeIfAbsent(doc.getCancelsInvoiceId(), this::numberOf)
                      : "";
              row(
                  out,
                  String.valueOf(doc.getId()),
                  registry.getStrategy(doc.getDocumentTypeId()).getDisplayName(),
                  doc.getDocumentNumber(),
                  statusName(doc.getDocumentStatusId()),
                  date(doc.getInvoiceDate()),
                  date(doc.getDueDate()),
                  date(doc.getWorkStartDate()),
                  date(doc.getWorkEndDate()),
                  String.valueOf(doc.getCustomerId()),
                  decimal(vat.net()),
                  decimal(vat.tax()),
                  decimal(vat.gross()),
                  decimal(paid.getOrDefault(doc.getId(), BigDecimal.ZERO)),
                  doc.getTaxTreatment(),
                  doc.getTaxExemptionReason(),
                  cancels);
              customerIds.add(doc.getCustomerId());
            }
          }
        });

    table(
        zip,
        "positionen.csv",
        new String[] {
          "Belegnummer",
          "Position",
          "Uebergeordnete_Position",
          "Art",
          "Bezeichnung",
          "Beschreibung",
          "Menge",
          "Einheit",
          "Einzelpreis",
          "Netto",
          "Steuersatz"
        },
        out -> {
          for (List<Invoice> chunk : chunks(documents)) {
            Map<Integer, List<InvoiceItem>> items =
                invoiceRepository.findItemsGroupedByInvoiceIds(ids(chunk));
            for (Invoice doc : chunk) {
              addItems(
                  out, doc.getDocumentNumber(), items.get(doc.getId()), "", new AtomicInteger());
            }
          }
        });

    table(
        zip,
        "zahlungen.csv",
        new String[] {
          "Zahlung_ID",
          "Belegnummer",
          "Datum",
          "Betrag",
          "Zahlungsart",
          "Referenz",
          "Notiz",
          "Storno_von_Zahlung"
        },
        out -> {
          for (Invoice doc : documents) {
            for (Payment p : paymentRepository.findByInvoiceId(doc.getId())) {
              row(
                  out,
                  String.valueOf(p.id()),
                  doc.getDocumentNumber(),
                  p.paymentDate().format(GERMAN_DATE),
                  decimal(p.amount()),
                  p.paymentMethod(),
                  p.reference(),
                  p.notes(),
                  p.reversesPaymentId() == null ? "" : String.valueOf(p.reversesPaymentId()));
            }
          }
        });

    table(
        zip,
        "historie.csv",
        new String[] {"Eintrag_ID", "Belegnummer", "Zeitpunkt", "Aktion", "Details"},
        out -> {
          for (Invoice doc : documents) {
            for (DocumentHistoryEntry h : historyRepository.findByInvoiceId(doc.getId())) {
              row(
                  out,
                  String.valueOf(h.id()),
                  doc.getDocumentNumber(),
                  h.actionDate(),
                  h.action(),
                  h.notes());
            }
          }
        });

    table(
        zip,
        "kunden.csv",
        new String[] {
          "Kunden_ID",
          "Vorname",
          "Nachname",
          "Strasse",
          "Hausnummer",
          "PLZ",
          "Ort",
          "E_Mail",
          "Steuernummer_USt_IdNr",
          "Kaeuferreferenz"
        },
        out -> {
          List<Integer> all = new ArrayList<>(customerIds);
          for (int i = 0; i < all.size(); i += CHUNK) {
            List<Integer> chunk = all.subList(i, Math.min(all.size(), i + CHUNK));
            Map<Integer, Customer> byId = new HashMap<>();
            customerRepository.findByIds(chunk).forEach(c -> byId.put(c.getId(), c));
            for (Integer id : chunk) {
              Customer c = byId.get(id);
              if (c != null) {
                row(
                    out,
                    String.valueOf(c.getId()),
                    c.getFirstName(),
                    c.getLastName(),
                    c.getStreet(),
                    c.getHouseNumber() > 0 ? String.valueOf(c.getHouseNumber()) : "",
                    c.getPostalCode(),
                    c.getCity(),
                    c.getEmail(),
                    c.getTaxId(),
                    c.getBuyerReference());
              }
            }
          }
        });
  }

  /** Writes the rows of one CSV table. */
  @FunctionalInterface
  private interface TableRows {
    void write(Writer out) throws IOException;
  }

  /** Writes one CSV table as a ZIP entry, header first, without collecting it in memory. */
  private static void table(ZipOutputStream zip, String name, String[] header, TableRows rows)
      throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    // Not closed: closing the writer would close the ZIP stream.
    Writer out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
    row(out, header);
    rows.write(out);
    out.flush();
    zip.closeEntry();
  }

  private static List<List<Invoice>> chunks(List<Invoice> documents) {
    List<List<Invoice>> result = new ArrayList<>();
    for (int i = 0; i < documents.size(); i += CHUNK) {
      result.add(documents.subList(i, Math.min(documents.size(), i + CHUNK)));
    }
    return result;
  }

  private static List<Integer> ids(List<Invoice> documents) {
    return documents.stream().map(Invoice::getId).toList();
  }

  /** Issued invoices and credit notes whose document date lies in the period. */
  private List<Invoice> documentsIn(LocalDate from, LocalDate to) {
    List<Invoice> result = new ArrayList<>();
    for (Invoice doc : invoiceRepository.findByInvoiceDateBetween(from, to)) {
      if (!DocumentType.isBookkeepingRecord(doc.getDocumentTypeId())
          || doc.getDocumentStatusId() == DocumentStatus.DRAFT
          || doc.getInvoiceDate() == null
          || doc.getInvoiceDate().isEmpty()) {
        continue;
      }
      LocalDate date = LocalDate.parse(doc.getInvoiceDate());
      if (!date.isBefore(from) && !date.isAfter(to)) {
        result.add(doc);
      }
    }
    result.sort(
        (a, b) -> {
          int byDate = a.getInvoiceDate().compareTo(b.getInvoiceDate());
          return byDate == 0 ? a.getDocumentNumber().compareTo(b.getDocumentNumber()) : byDate;
        });
    return result;
  }

  /**
   * Adds the PDF snapshot and, if the master data suffices, the XRechnung of each document. A PDF
   * that cannot be produced breaks the export off (the ZIP stays unreadable): an archive that
   * silently lacks a document's file would look complete, and the export must never be shortened
   * without notice.
   */
  private void addDocuments(ZipOutputStream zip, List<Invoice> documents) throws IOException {
    for (Invoice doc : documents) {
      String base = "dokumente/" + doc.getDocumentNumber().replaceAll("[^A-Za-z0-9._-]", "_");
      // The same file the download hands out: the issue-time snapshot as PDF/A-3, with the
      // XRechnung attached when the data suffices.
      put(zip, base + ".pdf", pdfOf(doc));
      if (eInvoiceService.check(doc.getId()).isEmpty()) {
        put(
            zip,
            base + "_xrechnung.xml",
            eInvoiceService.xrechnung(doc.getId()).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  /** The document's PDF; a rendering failure names the document. */
  private byte[] pdfOf(Invoice doc) {
    try {
      return documentPdfService.pdf(doc);
    } catch (RuntimeException e) {
      throw e; // limits, the snapshot's timeout and database errors keep their meaning
    } catch (Exception e) {
      throw new IllegalStateException(
          "No PDF for " + doc.getDocumentNumber() + " in the GoBD export", e);
    }
  }

  /**
   * Runs the export's work, presenting the end of its snapshot's time (a query after the deadline)
   * like every other overrun of the time limit.
   */
  private static <T extends Exception> void withinTimeLimit(ThrowingRunnable<T> work) throws T {
    try {
      work.run();
    } catch (TransactionTimedOutException e) {
      ExportLimitException tooLong = tooLong();
      tooLong.initCause(e);
      throw tooLong;
    }
  }

  /** A piece of work that may throw a checked exception. */
  @FunctionalInterface
  private interface ThrowingRunnable<T extends Exception> {
    void run() throws T;
  }

  private static void addItems(
      Writer out, String number, List<InvoiceItem> items, String parent, AtomicInteger counter)
      throws IOException {
    if (items == null) {
      return;
    }
    for (InvoiceItem item : items) {
      String position = String.valueOf(counter.incrementAndGet());
      boolean note = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(item.getItemType());
      row(
          out,
          number,
          position,
          parent,
          item.getItemType(),
          item.getItemName(),
          item.getItemDescription(),
          note ? "" : decimal(item.getQuantity()),
          item.getUnit(),
          note ? "" : decimal(item.getUnitPrice()),
          note ? "" : decimal(item.getTotalPrice()),
          note ? "" : decimal(item.getTaxRatePercent()));
      addItems(out, number, item.getChildren(), position, counter);
    }
  }

  private String numberOf(int invoiceId) {
    Invoice invoice = invoiceRepository.findById(invoiceId);
    return invoice == null ? "" : invoice.getDocumentNumber();
  }

  private static String statusName(int statusId) {
    return switch (statusId) {
      case DocumentStatus.SENT -> "Ausgestellt";
      case DocumentStatus.PARTIALLY_PAID -> "Teilweise bezahlt";
      case DocumentStatus.PAID -> "Bezahlt";
      case DocumentStatus.CANCELLED -> "Storniert";
      default -> String.valueOf(statusId);
    };
  }

  /** Writes one CSV record; every value is quoted, quotes are doubled. */
  private static void row(Writer out, String... values) throws IOException {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.write(SEP);
      }
      String value = values[i] == null ? "" : values[i];
      out.write('"');
      out.write(value.replace("\"", "\"\""));
      out.write('"');
    }
    out.write(EOL);
  }

  private static String decimal(BigDecimal value) {
    return value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ',');
  }

  private static String date(String iso) {
    return iso == null || iso.isEmpty() ? "" : LocalDate.parse(iso).format(GERMAN_DATE);
  }

  private static void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content);
    zip.closeEntry();
  }

  /**
   * Passes the ZIP to the client until {@link #cut()}; from then on it drops every byte, so closing
   * the ZIP after a failure releases its resources without completing the archive. Refuses to send
   * more than the output limit and anything after the deadline. Never closes the client's stream
   * (the container does).
   */
  private static final class CuttableStream extends FilterOutputStream {
    private final long limit;
    private final long deadline;
    private long sent;
    private boolean dropping;

    private CuttableStream(OutputStream out, long limit, long deadline) {
      super(out);
      this.limit = limit;
      this.deadline = deadline;
    }

    private void count(long bytes) {
      if (System.nanoTime() - deadline > 0) {
        dropping = true;
        throw tooLong();
      }
      sent += bytes;
      if (sent > limit) {
        dropping = true;
        throw new ExportLimitException(
            ErrorCode.EXPORT_TOO_LARGE.with("max", limit / (1024 * 1024)));
      }
    }

    private void cut() {
      dropping = true;
    }

    @Override
    public void write(int b) throws IOException {
      if (!dropping) {
        count(1);
        out.write(b);
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      if (!dropping) {
        count(len);
        out.write(b, off, len);
      }
    }

    @Override
    public void flush() throws IOException {
      if (!dropping) {
        out.flush();
      }
    }

    @Override
    public void close() {
      // the container closes the response stream
    }
  }
}
