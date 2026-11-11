package de.billery.tax;

import de.billery.data.InvoiceItem;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The one place where VAT is computed. The API, payments, statistics, the PDF and the XRechnung all
 * use it, so every output shows the same amounts.
 *
 * <p>Rules:
 *
 * <ul>
 *   <li>Every priced line (root or sub-item) counts with its own total and rate; {@code CUSTOM}
 *       note lines count with 0.
 *   <li>Lines are grouped by rate; the tax is rounded half-up to cents once per group, not per line
 *       (EN 16931 BR-CO-17).
 *   <li>A treatment other than {@link TaxTreatment#STANDARD} charges no VAT: all lines fall into
 *       one 0 % group.
 * </ul>
 */
public final class VatCalculator {

  /** The rates a line may carry under {@link TaxTreatment#STANDARD} (§12 UStG). */
  public static final List<BigDecimal> ALLOWED_RATES =
      List.of(new BigDecimal("19.00"), new BigDecimal("7.00"));

  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private VatCalculator() {}

  /**
   * Computes the VAT totals of an item tree.
   *
   * @param items the root items; their {@code children} are included
   * @param treatment the document's tax treatment
   * @return the breakdown; an empty item list gives zero totals and no groups
   */
  public static VatBreakdown calculate(List<InvoiceItem> items, TaxTreatment treatment) {
    Map<BigDecimal, BigDecimal> netByRate = new TreeMap<>();
    collect(items, treatment, netByRate);
    List<VatBreakdown.Group> groups = new ArrayList<>();
    BigDecimal net = cents(BigDecimal.ZERO);
    BigDecimal tax = cents(BigDecimal.ZERO);
    for (Map.Entry<BigDecimal, BigDecimal> entry : netByRate.entrySet()) {
      BigDecimal groupNet = cents(entry.getValue());
      BigDecimal groupTax = cents(groupNet.multiply(entry.getKey()).divide(HUNDRED));
      groups.add(new VatBreakdown.Group(entry.getKey(), groupNet, groupTax));
      net = net.add(groupNet);
      tax = tax.add(groupTax);
    }
    return new VatBreakdown(List.copyOf(groups), net, tax, net.add(tax));
  }

  /**
   * Normalises a rate to two decimals.
   *
   * @param ratePercent the rate in percent
   * @return the rate with scale 2
   */
  public static BigDecimal rate(BigDecimal ratePercent) {
    return ratePercent.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Tells whether a rate may be used under {@link TaxTreatment#STANDARD}.
   *
   * @param ratePercent the rate in percent; null is not allowed
   * @return {@code true} for 19 and 7
   */
  public static boolean isAllowedRate(BigDecimal ratePercent) {
    return ratePercent != null && ALLOWED_RATES.contains(rate(ratePercent));
  }

  private static void collect(
      List<InvoiceItem> items, TaxTreatment treatment, Map<BigDecimal, BigDecimal> netByRate) {
    if (items == null) {
      return;
    }
    for (InvoiceItem item : items) {
      boolean isNote = InvoiceItem.ItemType.CUSTOM.getValue().equalsIgnoreCase(item.getItemType());
      if (!isNote) {
        BigDecimal rate =
            treatment.chargesVat() ? rate(item.getTaxRatePercent()) : cents(BigDecimal.ZERO);
        netByRate.merge(rate, item.getTotalPrice(), BigDecimal::add);
      }
      collect(item.getChildren(), treatment, netByRate);
    }
  }

  private static BigDecimal cents(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP);
  }
}
