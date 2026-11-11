package de.billery.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EquipmentDto(
    Integer id,
    String name,
    BigDecimal purchaseValue,
    LocalDate acquisitionDate,
    Integer expectedLifetimeMonths,
    BigDecimal monthlyOperatingCost,
    BigDecimal residualValue,
    String status,
    String notes) {}
