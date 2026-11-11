const PERCENT_FORMAT = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 1 })

/**
 * What the amortisation cell shows for one row of `GET /api/equipment/summary`.
 *
 * The API reports the real payback (250 % for equipment that earned its price two and a half
 * times); the catalog only answers "how far, and is it done": below the purchase price a bar and
 * the percentage, from the purchase price on a full bar and "amortisiert". "Done" is decided by
 * the amounts, not by the rounded percentage, so 999,60 € of 1.000 € reads "99,9 %" and not
 * "100 %". Equipment without a purchase price has nothing to amortise: no bar, no percentage.
 *
 * @param {object} [summary] a summary row (`purchaseValue`, `totalRevenue`, `usageCount`, `paybackPercent`)
 * @returns {{hasPurchaseValue: boolean, done: boolean, barWidth: number, percentText: string,
 *   revenue: number, usageCount: number}|null} null without a summary row
 */
export function describeAmortisation(summary) {
  if (!summary) return null
  const purchaseValue = Number(summary.purchaseValue) || 0
  const revenue = Number(summary.totalRevenue) || 0
  const hasPurchaseValue = purchaseValue > 0
  const done = hasPurchaseValue && revenue >= purchaseValue
  const percent = Math.max(Number(summary.paybackPercent) || 0, 0)
  const shown = done ? 100 : Math.min(percent, 99.9)
  return {
    hasPurchaseValue,
    done,
    barWidth: shown,
    percentText: `${PERCENT_FORMAT.format(shown)} %`,
    revenue,
    usageCount: Number(summary.usageCount) || 0
  }
}
