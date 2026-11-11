package de.billery.api.dto;

import java.math.BigDecimal;

public record EquipmentUsageDto(
    String invoiceNumber,
    String invoiceDate,
    String serviceName,
    BigDecimal quantity,
    BigDecimal unitPrice,
    BigDecimal totalPrice,
    String unit) {}
