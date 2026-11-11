package de.billery.data;

import java.math.BigDecimal;

/**
 * What one piece of equipment has earned so far: the figures behind the amortisation column of the
 * catalog.
 *
 * <p>Only service lines on <strong>paid</strong> invoices count (status {@code PAID}); a sent or
 * partly paid invoice contributes nothing until it is paid in full. The revenue is the sum of the
 * net line totals, running costs are not deducted.
 *
 * @param revenue the summed net line totals in EUR, 0 when nothing was billed
 * @param usageCount the number of those lines
 */
public record EquipmentUsageTotals(BigDecimal revenue, int usageCount) {

  /** The totals of equipment that no paid invoice line refers to. */
  public static final EquipmentUsageTotals NONE = new EquipmentUsageTotals(BigDecimal.ZERO, 0);
}
