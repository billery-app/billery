package de.billery.data;

import java.math.BigDecimal;

/**
 * One use of a piece of equipment: a service line on a paid invoice whose service is linked to the
 * equipment. The rows of the "Einsätze" dialog; their totals are {@link EquipmentUsageTotals}.
 *
 * @param documentNumber the invoice's number
 * @param invoiceDate the invoice date (ISO, {@code yyyy-MM-dd})
 * @param serviceName the service's current name
 * @param quantity the billed quantity
 * @param unitPrice the net unit price in EUR
 * @param totalPrice the net line total in EUR
 * @param unit the service's current unit, empty when it has none
 */
public record EquipmentUsage(
    String documentNumber,
    String invoiceDate,
    String serviceName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal totalPrice,
    String unit) {}
