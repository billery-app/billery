package de.billery.service;

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
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

  private static final Logger LOGGER = Logger.getLogger(GobdExportService.class.getName());
  private static final DateTimeFormatter GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
  private static final String SEP = ";";
  private static final String EOL = "\r\n";

  private final InvoiceRepository invoiceRepository;
  private final PaymentRepository paymentRepository;
  private final DocumentHistoryRepository historyRepository;
  private final CustomerRepository customerRepository;
  private final DocumentStrategyRegistry registry = new DocumentStrategyRegistry();
  private final EInvoiceService eInvoiceService;
  private final DocumentPdfService documentPdfService;
  private final AtomicInteger runningExports = new AtomicInteger();

  private int maxDocuments = 10_000;
  private int maxDocumentsWithFiles = 2_000;
  private long maxOutputBytes = 128L * 1024 * 1024;
  private int maxPeriodDays = 3_660;
  private int maxConcurrentExports = 1;

  /**
   * Creates the service.
   *
   * @param invoiceRepository reads the documents and their line items
   * @param paymentRepository reads the payments
   * @param historyRepository reads the audit trail
   * @param customerRepository reads the customer master data
   * @param eInvoiceService produces the XRechnung of each document
   * @param documentPdfService renders the PDF of each document (ZUGFeRD where possible)
   */
  public GobdExportService(
      InvoiceRepository invoiceRepository,
      PaymentRepository paymentRepository,
      DocumentHistoryRepository historyRepository,
      CustomerRepository customerRepository,
      EInvoiceService eInvoiceService,
      DocumentPdfService documentPdfService) {
    this.invoiceRepository = invoiceRepository;
    this.paymentRepository = paymentRepository;
    this.historyRepository = historyRepository;
    this.customerRepository = customerRepository;
    this.eInvoiceService = eInvoiceService;
    this.documentPdfService = documentPdfService;
  }

  /**
   * Sets the limits that keep one export from exhausting heap and CPU. Spring injects the values
   * from {@code export.*} (see application.properties); without Spring the defaults above apply.
   *
   * @param maxDocuments most documents in one export
   * @param maxDocumentsWithFiles most documents when PDFs and XRechnungs are rendered as well
   * @param maxOutputBytes largest ZIP that is assembled in memory
   * @param maxPeriodDays longest period between {@code from} and {@code to}, in days
   * @param maxConcurrentExports exports that may run at the same time
   */
  @Autowired
  public void configureLimits(
      @Value("${export.max-documents:10000}") int maxDocuments,
      @Value("${export.max-documents-with-files:2000}") int maxDocumentsWithFiles,
      @Value("${export.max-output-bytes:134217728}") long maxOutputBytes,
      @Value("${export.max-period-days:3660}") int maxPeriodDays,
      @Value("${export.max-concurrent:1}") int maxConcurrentExports) {
    this.maxDocuments = maxDocuments;
    this.maxDocumentsWithFiles = maxDocumentsWithFiles;
    this.maxOutputBytes = maxOutputBytes;
    this.maxPeriodDays = maxPeriodDays;
    this.maxConcurrentExports = maxConcurrentExports;
  }

  /**
   * Builds the export ZIP.
   *
   * @param from first document date (inclusive)
   * @param to last document date (inclusive)
   * @param includeDocuments also add the PDF and XRechnung of every document
   * @return the ZIP file
   * @throws ExportLimitException if the period, document count or output size exceeds a limit
   * @throws ExportBusyException if the maximum number of concurrent exports is already running
   */
  public byte[] export(LocalDate from, LocalDate to, boolean includeDocuments) {
    if (from.plusDays(maxPeriodDays).isBefore(to)) {
      throw new ExportLimitException(
          "Der Zeitraum ist zu lang (höchstens " + maxPeriodDays + " Tage pro Export).");
    }
    if (runningExports.incrementAndGet() > maxConcurrentExports) {
      runningExports.decrementAndGet();
      throw new ExportBusyException(
          "Es läuft bereits ein Export. Bitte in einigen Minuten erneut versuchen.");
    }
    try {
      return build(from, to, includeDocuments);
    } finally {
      runningExports.decrementAndGet();
    }
  }

  private void checkDocumentCount(int count, boolean includeDocuments) {
    if (count > maxDocuments) {
      throw new ExportLimitException(
          "Zu viele Belege für einen Export ("
              + count
              + ", höchstens "
              + maxDocuments
              + "). Bitte den Zeitraum verkleinern.");
    }
    if (includeDocuments && count > maxDocumentsWithFiles) {
      throw new ExportLimitException(
          "Zu viele Belege für einen Export mit PDF und XRechnung ("
              + count
              + ", höchstens "
              + maxDocumentsWithFiles
              + "). Bitte den Zeitraum verkleinern oder ohne Belegdateien exportieren.");
    }
  }

  private byte[] build(LocalDate from, LocalDate to, boolean includeDocuments) {
    List<Invoice> documents = documentsIn(from, to);
    checkDocumentCount(documents.size(), includeDocuments);
    Map<String, StringBuilder> tables = new LinkedHashMap<>();
    StringBuilder belege =
        header(
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
            "Storno_zu");
    StringBuilder positionen =
        header(
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
            "Steuersatz");
    StringBuilder zahlungen =
        header(
            "Zahlung_ID",
            "Belegnummer",
            "Datum",
            "Betrag",
            "Zahlungsart",
            "Referenz",
            "Notiz",
            "Storno_von_Zahlung");
    StringBuilder historie = header("Eintrag_ID", "Belegnummer", "Zeitpunkt", "Aktion", "Details");
    Map<Integer, Customer> customers = new LinkedHashMap<>();
    Map<Integer, String> numbers = new LinkedHashMap<>();
    documents.forEach(d -> numbers.put(d.getId(), d.getDocumentNumber()));

    for (Invoice doc : documents) {
      List<InvoiceItem> items = invoiceRepository.findItemsByInvoiceId(doc.getId());
      VatBreakdown vat = VatCalculator.calculate(items, TaxTreatment.parse(doc.getTaxTreatment()));
      String cancels =
          doc.getCancelsInvoiceId() > 0
              ? numbers.getOrDefault(doc.getCancelsInvoiceId(), numberOf(doc.getCancelsInvoiceId()))
              : "";
      row(
          belege,
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
          decimal(paymentRepository.sumByInvoiceId(doc.getId())),
          doc.getTaxTreatment(),
          doc.getTaxExemptionReason(),
          cancels);
      addItems(positionen, doc.getDocumentNumber(), items, "", new AtomicInteger());
      for (Payment p : paymentRepository.findByInvoiceId(doc.getId())) {
        row(
            zahlungen,
            String.valueOf(p.id()),
            doc.getDocumentNumber(),
            p.paymentDate().format(GERMAN_DATE),
            decimal(p.amount()),
            p.paymentMethod(),
            p.reference(),
            p.notes(),
            p.reversesPaymentId() == null ? "" : String.valueOf(p.reversesPaymentId()));
      }
      for (DocumentHistoryEntry h : historyRepository.findByInvoiceId(doc.getId())) {
        row(
            historie,
            String.valueOf(h.id()),
            doc.getDocumentNumber(),
            h.actionDate(),
            h.action(),
            h.notes());
      }
      customers.computeIfAbsent(doc.getCustomerId(), customerRepository::findById);
    }

    StringBuilder kunden =
        header(
            "Kunden_ID",
            "Vorname",
            "Nachname",
            "Strasse",
            "Hausnummer",
            "PLZ",
            "Ort",
            "E_Mail",
            "Steuernummer_USt_IdNr",
            "Kaeuferreferenz");
    for (Map.Entry<Integer, Customer> entry : customers.entrySet()) {
      Customer c = entry.getValue();
      if (c != null) {
        row(
            kunden,
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
    tables.put("belege.csv", belege);
    tables.put("positionen.csv", positionen);
    tables.put("zahlungen.csv", zahlungen);
    tables.put("historie.csv", historie);
    tables.put("kunden.csv", kunden);

    ByteArrayOutputStream bytes = new BoundedBytes(maxOutputBytes);
    try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
      for (Map.Entry<String, StringBuilder> table : tables.entrySet()) {
        put(zip, table.getKey(), table.getValue().toString().getBytes(StandardCharsets.UTF_8));
      }
      put(zip, "index.xml", GobdIndex.xml(from, to).getBytes(StandardCharsets.UTF_8));
      if (includeDocuments) {
        addDocuments(zip, documents);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return bytes.toByteArray();
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

  /** Adds the PDF snapshot and, if the master data suffices, the XRechnung of each document. */
  private void addDocuments(ZipOutputStream zip, List<Invoice> documents) throws IOException {
    for (Invoice doc : documents) {
      String base = "dokumente/" + doc.getDocumentNumber().replaceAll("[^A-Za-z0-9._-]", "_");
      try {
        // The same file the download hands out: the issue-time snapshot as PDF/A-3, with the
        // XRechnung attached when the data suffices.
        byte[] content = documentPdfService.pdf(doc);
        put(zip, base + ".pdf", content);
      } catch (Exception e) {
        LOGGER.log(Level.WARNING, "No PDF for " + doc.getDocumentNumber() + " in GoBD export", e);
      }
      if (eInvoiceService.check(doc.getId()).isEmpty()) {
        put(
            zip,
            base + "_xrechnung.xml",
            eInvoiceService.xrechnung(doc.getId()).getBytes(StandardCharsets.UTF_8));
      }
    }
  }

  private static void addItems(
      StringBuilder out,
      String number,
      List<InvoiceItem> items,
      String parent,
      AtomicInteger counter) {
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

  private static StringBuilder header(String... columns) {
    StringBuilder out = new StringBuilder();
    row(out, columns);
    return out;
  }

  /** Appends one CSV record; every value is quoted, quotes are doubled. */
  private static void row(StringBuilder out, String... values) {
    for (int i = 0; i < values.length; i++) {
      if (i > 0) {
        out.append(SEP);
      }
      String value = values[i] == null ? "" : values[i];
      out.append('"').append(value.replace("\"", "\"\"")).append('"');
    }
    out.append(EOL);
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

  /** Collects the ZIP in memory and aborts the export as soon as it outgrows the limit. */
  private static final class BoundedBytes extends ByteArrayOutputStream {
    private final long limit;

    private BoundedBytes(long limit) {
      super();
      this.limit = limit;
    }

    @Override
    public synchronized void write(int b) {
      check(1);
      super.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len) {
      check(len);
      super.write(b, off, len);
    }

    private void check(int additional) {
      if ((long) size() + additional > limit) {
        throw new ExportLimitException(
            "Der Export ist zu groß (höchstens "
                + limit / (1024 * 1024)
                + " MB). Bitte den Zeitraum verkleinern oder ohne Belegdateien exportieren.");
      }
    }
  }
}
