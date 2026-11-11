package de.billery.api.dto;

import java.math.BigDecimal;

/**
 * A catalog service.
 *
 * @param taxRate default VAT rate in percent for new invoice lines (19 or 7; default 19)
 */
public record ServiceDto(
    Integer id,
    String name,
    String description,
    BigDecimal hourlyRate,
    Integer articleId,
    Integer equipmentId,
    String unit,
    BigDecimal taxRate) {}
