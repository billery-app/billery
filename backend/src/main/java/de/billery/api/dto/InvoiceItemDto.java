package de.billery.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * One line of a document. {@code totalPrice} is computed by the server; a sent value is ignored.
 *
 * @param unit the unit, stored on the line (e.g. "Std", "Stk")
 * @param taxRate VAT rate in percent (19 or 7); only applies to STANDARD-taxed documents
 */
public record InvoiceItemDto(
    Integer id,
    Integer invoiceId,
    String itemType,
    Integer itemId,
    String itemName,
    String itemDescription,
    String unit,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal totalPrice,
    Integer parentItemId,
    List<InvoiceItemDto> children,
    BigDecimal taxRate) {}
