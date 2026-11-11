package de.billery.api.controller;

import de.billery.api.dto.EquipmentDto;
import de.billery.api.dto.EquipmentSummaryDto;
import de.billery.api.dto.EquipmentUsageDto;
import de.billery.data.Equipment;
import de.billery.data.EquipmentUsage;
import de.billery.data.EquipmentUsageTotals;
import de.billery.repository.EquipmentRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/equipment")
public class EquipmentController {

  private final EquipmentRepository equipmentRepository;

  /**
   * Creates the controller.
   *
   * @param equipmentRepository the equipment and its usage figures
   */
  public EquipmentController(EquipmentRepository equipmentRepository) {
    this.equipmentRepository = equipmentRepository;
  }

  @GetMapping
  public List<EquipmentDto> getAll() {
    List<EquipmentDto> result = new ArrayList<>();
    for (Equipment e : equipmentRepository.findAll()) {
      result.add(convertToDto(e));
    }
    return result;
  }

  @PostMapping
  public EquipmentDto create(@RequestBody EquipmentDto dto) {
    Equipment equipment =
        new Equipment(
            0,
            dto.name(),
            dto.purchaseValue(),
            dto.acquisitionDate() != null ? dto.acquisitionDate().toString() : null,
            dto.expectedLifetimeMonths() != null ? dto.expectedLifetimeMonths() : 0,
            dto.monthlyOperatingCost(),
            dto.residualValue(),
            dto.status(),
            dto.notes());
    equipmentRepository.save(equipment);
    return convertToDto(equipment);
  }

  @PutMapping("/{id}")
  public ResponseEntity<EquipmentDto> update(
      @PathVariable Integer id, @RequestBody EquipmentDto dto) {
    Equipment existing = equipmentRepository.findById(id);
    if (existing == null) {
      return ResponseEntity.notFound().build();
    }
    existing.setName(dto.name() != null ? dto.name() : existing.getName());
    existing.setPurchaseValue(
        dto.purchaseValue() != null ? dto.purchaseValue() : existing.getPurchaseValue());
    if (dto.acquisitionDate() != null) {
      existing.setAcquisitionDate(dto.acquisitionDate().toString());
    }
    existing.setExpectedLifetimeMonths(
        dto.expectedLifetimeMonths() != null
            ? dto.expectedLifetimeMonths()
            : existing.getExpectedLifetimeMonths());
    existing.setMonthlyOperatingCost(
        dto.monthlyOperatingCost() != null
            ? dto.monthlyOperatingCost()
            : existing.getMonthlyOperatingCost());
    existing.setResidualValue(
        dto.residualValue() != null ? dto.residualValue() : existing.getResidualValue());
    existing.setStatus(dto.status() != null ? dto.status() : existing.getStatus());
    existing.setNotes(dto.notes() != null ? dto.notes() : existing.getNotes());
    equipmentRepository.update(existing);
    return ResponseEntity.ok(convertToDto(existing));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable Integer id) {
    equipmentRepository.delete(id);
    return ResponseEntity.ok().build();
  }

  private EquipmentDto convertToDto(Equipment e) {
    LocalDate acquisitionDate =
        e.getAcquisitionDate() != null && !e.getAcquisitionDate().isEmpty()
            ? LocalDate.parse(e.getAcquisitionDate())
            : null;
    return new EquipmentDto(
        e.getId(),
        e.getName(),
        e.getPurchaseValue(),
        acquisitionDate,
        e.getExpectedLifetimeMonths() > 0 ? e.getExpectedLifetimeMonths() : null,
        e.getMonthlyOperatingCost(),
        e.getResidualValue(),
        e.getStatus(),
        e.getNotes());
  }

  /**
   * The amortisation figures of all equipment: revenue and number of uses on paid invoices (see
   * {@link EquipmentUsageTotals} for what counts) and the revenue as a percentage of the purchase
   * price.
   *
   * <p>{@code paybackPercent} is the real value and may exceed 100 (equipment that has earned more
   * than it cost); the catalog caps what it shows. It is 0 when no purchase price is set. The
   * totals of all equipment come from one grouped query.
   *
   * @return one entry per equipment, ordered like the equipment list
   */
  @GetMapping("/summary")
  public List<EquipmentSummaryDto> getSummary() {
    Map<Integer, EquipmentUsageTotals> totalsByEquipment = equipmentRepository.findUsageTotals();
    List<EquipmentSummaryDto> result = new ArrayList<>();
    for (Equipment eq : equipmentRepository.findAll()) {
      EquipmentUsageTotals totals =
          totalsByEquipment.getOrDefault(eq.getId(), EquipmentUsageTotals.NONE);
      BigDecimal totalRevenue = totals.revenue();
      BigDecimal purchaseValue =
          eq.getPurchaseValue() != null ? eq.getPurchaseValue() : BigDecimal.ZERO;
      BigDecimal paybackPercent = BigDecimal.ZERO;
      if (purchaseValue.compareTo(BigDecimal.ZERO) > 0) {
        paybackPercent =
            totalRevenue
                .divide(purchaseValue, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
      }
      result.add(
          new EquipmentSummaryDto(
              eq.getId(),
              eq.getName(),
              purchaseValue,
              eq.getAcquisitionDate(),
              eq.getExpectedLifetimeMonths() > 0 ? eq.getExpectedLifetimeMonths() : null,
              eq.getMonthlyOperatingCost(),
              totalRevenue.setScale(2, RoundingMode.HALF_UP),
              totals.usageCount(),
              paybackPercent));
    }
    return result;
  }

  /**
   * The uses of one piece of equipment (service lines on paid invoices), newest invoice first. The
   * rows add up to the equipment's entry in {@link #getSummary()}.
   *
   * @param id the equipment id
   * @return the lines, or 404 when the equipment does not exist
   */
  @GetMapping("/{id}/usage")
  public ResponseEntity<List<EquipmentUsageDto>> getUsage(@PathVariable Integer id) {
    if (equipmentRepository.findById(id) == null) {
      return ResponseEntity.notFound().build();
    }
    List<EquipmentUsageDto> result = new ArrayList<>();
    for (EquipmentUsage usage : equipmentRepository.findUsage(id)) {
      result.add(
          new EquipmentUsageDto(
              usage.documentNumber(),
              usage.invoiceDate(),
              usage.serviceName(),
              usage.quantity(),
              usage.unitPrice(),
              usage.totalPrice(),
              usage.unit()));
    }
    return ResponseEntity.ok(result);
  }
}
