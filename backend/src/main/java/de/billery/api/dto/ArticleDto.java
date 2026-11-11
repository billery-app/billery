package de.billery.api.dto;

import java.math.BigDecimal;

/**
 * A catalog article.
 *
 * @param taxRate default VAT rate in percent for new invoice lines (19 or 7; default 19)
 * @param note internal note, never printed on documents (optional)
 */
public record ArticleDto(
    Integer id,
    String name,
    String description,
    String unit,
    BigDecimal purchasePrice,
    BigDecimal salesPrice,
    BigDecimal taxRate,
    String note) {}
