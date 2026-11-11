package de.billery.statistics;

import de.billery.data.Article;
import de.billery.data.Invoice;
import de.billery.data.InvoiceItem;
import de.billery.document.DocumentStatus;
import de.billery.document.DocumentType;
import de.billery.repository.ArticleRepository;
import de.billery.repository.InvoiceRepository;
import de.billery.tax.TaxTreatment;
import de.billery.tax.VatBreakdown;
import de.billery.tax.VatCalculator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.stereotype.Service;

/**
 * Service class for generating comprehensive invoice statistics and analytics. Provides various
 * statistical views over configurable time periods and payment statuses.
 *
 * <p>This service supports:
 *
 * <ul>
 *   <li>Flexible date range selection
 *   <li>Revenue analysis by payment status (paid/unpaid/all)
 *   <li>Invoice count statistics
 *   <li>Average invoice values
 *   <li>Tax calculations
 * </ul>
 *
 * <p>Only bookkeeping-relevant documents count (see {@link #isRevenueRelevant}): issued invoices of
 * the invoice types in the period of their invoice date, and credit notes (Stornorechnungen)
 * negatively in the period of <em>their</em> date, as for the VAT return. Drafts, offers, cost
 * calculations and invoices cancelled without a credit note (legacy data) are ignored. The
 * paid/unpaid split covers open and paid invoices only.
 *
 * @author Invoice Management System
 * @version 1.0
 */
@Service
public class InvoiceStatisticsService {

  /** Logger for this class */
  private static final Logger LOGGER = Logger.getLogger(InvoiceStatisticsService.class.getName());

  /** Repository for accessing invoice data */
  private final InvoiceRepository invoiceRepository;

  /** Repository for the purchase value of the articles sold */
  private final ArticleRepository articleRepository;

  /**
   * Creates a new statistics service instance.
   *
   * @param invoiceRepository reads the documents and their line items
   * @param articleRepository reads the article purchase values
   */
  public InvoiceStatisticsService(
      InvoiceRepository invoiceRepository, ArticleRepository articleRepository) {
    this.invoiceRepository = invoiceRepository;
    this.articleRepository = articleRepository;
  }

  /**
   * Generates the statistics of a period with a fixed number of queries: the documents of the
   * period (selected in SQL), the credit notes of its cancelled invoices, the items of its
   * documents and the articles they sell. Nothing is loaded per document or per item.
   *
   * @param startDate the start date of the period (inclusive)
   * @param endDate the end date of the period (inclusive)
   * @return statistics object containing all calculated metrics
   */
  public InvoiceStatistics generateStatistics(LocalDate startDate, LocalDate endDate) {
    List<Invoice> allInvoices = revenueRelevantInvoicesIn(startDate, endDate);
    List<Integer> ids = allInvoices.stream().map(Invoice::getId).toList();
    Map<Integer, List<InvoiceItem>> items = invoiceRepository.findItemsGroupedByInvoiceIds(ids);
    Map<Integer, VatBreakdown> vat = new HashMap<>();
    for (Invoice invoice : allInvoices) {
      vat.put(
          invoice.getId(),
          VatCalculator.calculate(
              items.getOrDefault(invoice.getId(), List.of()),
              TaxTreatment.parse(invoice.getTaxTreatment())));
    }

    // Credit notes never count as paid or open invoices (the app cannot pay one, but legacy or
    // hand-edited data could carry PAID); they only offset the totals.
    List<Invoice> paidInvoices =
        allInvoices.stream()
            .filter(invoice -> invoice.getDocumentTypeId() != DocumentType.CREDIT_NOTE)
            .filter(invoice -> invoice.getDocumentStatusId() == DocumentStatus.PAID)
            .toList();
    List<Invoice> unpaidInvoices =
        allInvoices.stream()
            .filter(invoice -> invoice.getDocumentTypeId() != DocumentType.CREDIT_NOTE)
            .filter(
                invoice ->
                    invoice.getDocumentStatusId() == DocumentStatus.SENT
                        || invoice.getDocumentStatusId() == DocumentStatus.PARTIALLY_PAID)
            .toList();
    long invoiceCount =
        allInvoices.stream()
            .filter(invoice -> invoice.getDocumentTypeId() != DocumentType.CREDIT_NOTE)
            .count();

    InvoiceStatistics stats = new InvoiceStatistics();
    stats.setPeriodStart(startDate);
    stats.setPeriodEnd(endDate);

    stats.setTotalInvoiceCount((int) invoiceCount);
    stats.setPaidInvoiceCount(paidInvoices.size());
    stats.setUnpaidInvoiceCount(unpaidInvoices.size());

    stats.setTotalRevenue(calculateTotalRevenue(allInvoices));
    stats.setPaidRevenue(calculateTotalRevenue(paidInvoices));
    stats.setUnpaidRevenue(calculateTotalRevenue(unpaidInvoices));

    stats.setAverageInvoiceValue(average(calculateTotalRevenue(allInvoices), invoiceCount));
    stats.setAveragePaidValue(calculateAverageValue(paidInvoices));
    stats.setAverageUnpaidValue(calculateAverageValue(unpaidInvoices));

    stats.setTotalTaxAmount(sumOf(allInvoices, vat, VatBreakdown::tax));
    stats.setPaidTaxAmount(sumOf(paidInvoices, vat, VatBreakdown::tax));
    stats.setUnpaidTaxAmount(sumOf(unpaidInvoices, vat, VatBreakdown::tax));

    stats.setTotalGross(sumOf(allInvoices, vat, VatBreakdown::gross));
    stats.setPaidGross(sumOf(paidInvoices, vat, VatBreakdown::gross));
    stats.setUnpaidGross(sumOf(unpaidInvoices, vat, VatBreakdown::gross));
    // The dashboard's totals (issued = paid + open), summed here so the browser never adds money.
    stats.setIssuedGross(stats.getPaidGross().add(stats.getUnpaidGross()));
    stats.setIssuedTaxAmount(stats.getPaidTaxAmount().add(stats.getUnpaidTaxAmount()));

    BigDecimal sells = calculateTotalSells(allInvoices, items);
    BigDecimal purchase = calculateTotalPurchaseValue(allInvoices, items);
    stats.setTotalSells(sells);
    stats.setTotalPurchaseValue(purchase);
    stats.setTotalGain(sells.subtract(purchase));

    if (LOGGER.isLoggable(Level.FINE)) {
      LOGGER.fine(
          "Statistics " + startDate + " to " + endDate + ": " + invoiceCount + " invoices counted");
    }
    return stats;
  }

  /**
   * The bookkeeping-relevant documents of the period (see {@link #isRevenueRelevant}); the period
   * is selected in SQL, the credit notes of cancelled invoices are looked up in one query.
   */
  private List<Invoice> revenueRelevantInvoicesIn(LocalDate startDate, LocalDate endDate) {
    List<Invoice> inPeriod = invoiceRepository.findByInvoiceDateBetween(startDate, endDate);
    List<Integer> cancelled =
        inPeriod.stream()
            .filter(
                invoice ->
                    DocumentType.isInvoice(invoice.getDocumentTypeId())
                        && invoice.getDocumentStatusId() == DocumentStatus.CANCELLED)
            .map(Invoice::getId)
            .toList();
    Set<Integer> cancelledByCreditNote =
        invoiceRepository.findCreditNoteIdsForInvoices(cancelled).keySet();
    return inPeriod.stream()
        .filter(invoice -> isRevenueRelevant(invoice, cancelledByCreditNote))
        .toList();
  }

  /**
   * Tells whether a document counts for revenue and tax: an issued invoice of an invoice type
   * (open, partly paid, paid, or cancelled by a credit note, which then offsets it), or a credit
   * note.
   */
  private static boolean isRevenueRelevant(Invoice invoice, Set<Integer> cancelledByCreditNote) {
    int type = invoice.getDocumentTypeId();
    if (type == DocumentType.CREDIT_NOTE) {
      return true;
    }
    if (!DocumentType.isInvoice(type)) {
      return false;
    }
    int status = invoice.getDocumentStatusId();
    if (status == DocumentStatus.CANCELLED) {
      return cancelledByCreditNote.contains(invoice.getId());
    }
    return status == DocumentStatus.SENT
        || status == DocumentStatus.PARTIALLY_PAID
        || status == DocumentStatus.PAID;
  }

  /** +1 for invoices, -1 for credit notes, whose amounts are stored positive. */
  private static int sign(Invoice invoice) {
    return invoice.getDocumentTypeId() == DocumentType.CREDIT_NOTE ? -1 : 1;
  }

  /** The amount with the document's sign: negative for credit notes. */
  private static BigDecimal signed(Invoice invoice, BigDecimal amount) {
    return sign(invoice) < 0 ? amount.negate() : amount;
  }

  /** Net revenue: the stored total of each document, credit notes negative. */
  private static BigDecimal calculateTotalRevenue(List<Invoice> invoices) {
    return invoices.stream()
        .map(invoice -> signed(invoice, invoice.getTotalPrice()))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static BigDecimal calculateAverageValue(List<Invoice> invoices) {
    return average(calculateTotalRevenue(invoices), invoices.size());
  }

  /** {@code sum / count} rounded half-up to cents; 0 for an empty set. */
  private static BigDecimal average(BigDecimal sum, long count) {
    if (count == 0) {
      return BigDecimal.ZERO;
    }
    return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
  }

  /** Sums one part of each document's VAT breakdown, credit notes negative. */
  private static BigDecimal sumOf(
      List<Invoice> invoices,
      Map<Integer, VatBreakdown> vat,
      Function<VatBreakdown, BigDecimal> part) {
    return invoices.stream()
        .map(invoice -> signed(invoice, part.apply(vat.get(invoice.getId()))))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** Unit price × quantity of the root article lines (sub-items are not counted). */
  private static BigDecimal calculateTotalSells(
      List<Invoice> invoices, Map<Integer, List<InvoiceItem>> items) {
    BigDecimal totalSells = BigDecimal.ZERO;
    for (Invoice invoice : invoices) {
      for (InvoiceItem item : items.getOrDefault(invoice.getId(), List.of())) {
        if (item.getItemTypeEnum() == InvoiceItem.ItemType.ARTICLE) {
          totalSells =
              totalSells.add(signed(invoice, item.getUnitPrice().multiply(item.getQuantity())));
        }
      }
    }
    return totalSells;
  }

  /** Purchase value × quantity of the root article lines, with all articles read in one query. */
  private BigDecimal calculateTotalPurchaseValue(
      List<Invoice> invoices, Map<Integer, List<InvoiceItem>> items) {
    List<Integer> articleIds = new ArrayList<>();
    for (Invoice invoice : invoices) {
      for (InvoiceItem item : items.getOrDefault(invoice.getId(), List.of())) {
        if (item.getItemTypeEnum() == InvoiceItem.ItemType.ARTICLE) {
          articleIds.add(item.getItemId());
        }
      }
    }
    Map<Integer, Article> articles = new HashMap<>();
    for (Article article : articleRepository.findByIds(articleIds.stream().distinct().toList())) {
      articles.put(article.getId(), article);
    }
    BigDecimal totalPurchaseValue = BigDecimal.ZERO;
    for (Invoice invoice : invoices) {
      for (InvoiceItem item : items.getOrDefault(invoice.getId(), List.of())) {
        Article article = articles.get(item.getItemId());
        if (item.getItemTypeEnum() == InvoiceItem.ItemType.ARTICLE && article != null) {
          totalPurchaseValue =
              totalPurchaseValue.add(
                  article
                      .getPurchaseValue()
                      .multiply(item.getQuantity())
                      .multiply(BigDecimal.valueOf(sign(invoice))));
        }
      }
    }
    return totalPurchaseValue;
  }
}
