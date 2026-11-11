package de.billery.tax;

import java.math.BigDecimal;
import java.util.List;

/**
 * The VAT totals of a document: one group per rate, plus the document totals.
 *
 * @param groups one entry per VAT rate, ascending by rate
 * @param net sum of all line totals
 * @param tax sum of the groups' tax amounts
 * @param gross {@code net + tax}
 */
public record VatBreakdown(List<Group> groups, BigDecimal net, BigDecimal tax, BigDecimal gross) {

  /**
   * The lines of one VAT rate.
   *
   * @param ratePercent the rate, e.g. {@code 19.00}; 0 for every non-standard treatment
   * @param net sum of the line totals with this rate
   * @param tax {@code net × rate / 100}, rounded half-up to cents once per group (EN 16931
   *     BR-CO-17)
   */
  public record Group(BigDecimal ratePercent, BigDecimal net, BigDecimal tax) {}
}
