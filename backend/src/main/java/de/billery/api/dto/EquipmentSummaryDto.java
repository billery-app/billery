package de.billery.api.dto;

import java.math.BigDecimal;

public record EquipmentSummaryDto(
    Integer id,
    String name,
    BigDecimal purchaseValue,
    String acquisitionDate,
    Integer expectedLifetimeMonths,
    BigDecimal monthlyOperatingCost,
    BigDecimal totalRevenue,
    int usageCount,
    BigDecimal paybackPercent) {}
