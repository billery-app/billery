package de.billery.api.controller;

import de.billery.api.dto.ServiceDto;
import de.billery.data.Service;
import de.billery.repository.ServiceRepository;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/services")
public class ServiceController {

  private final ServiceRepository serviceRepository;

  public ServiceController(ServiceRepository serviceRepository) {
    this.serviceRepository = serviceRepository;
  }

  @GetMapping
  public List<ServiceDto> getAllServices() {
    List<Service> services = serviceRepository.findAll();
    List<ServiceDto> result = new ArrayList<>();
    for (Service service : services) {
      result.add(convertToDto(service));
    }
    return result;
  }

  @GetMapping("/{id}")
  public ResponseEntity<ServiceDto> getService(@PathVariable Integer id) {
    Service service = serviceRepository.findById(id);
    return service != null
        ? ResponseEntity.ok(convertToDto(service))
        : ResponseEntity.notFound().build();
  }

  private ServiceDto convertToDto(Service service) {
    return new ServiceDto(
        service.getId(),
        service.getName(),
        service.getDescription(),
        service.getPrice(),
        service.getArticleId(),
        service.getEquipmentId() > 0 ? service.getEquipmentId() : null,
        service.getUnit(),
        service.getTaxRatePercent());
  }

  @PostMapping
  public Service createService(@RequestBody ServiceDto dto) {
    Service service =
        new Service(
            0,
            dto.name(),
            dto.articleId() != null ? dto.articleId() : 0,
            dto.description(),
            ArticleController.orZero(dto.hourlyRate()),
            dto.unit() != null ? dto.unit() : "Std",
            "");
    service.setEquipmentId(dto.equipmentId() != null ? dto.equipmentId() : 0);
    service.setTaxRatePercent(ArticleController.catalogRate(dto.taxRate()));
    return serviceRepository.save(service);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Service> updateService(
      @PathVariable Integer id, @RequestBody ServiceDto dto) {
    Service existing = serviceRepository.findById(id);
    if (existing == null) {
      return ResponseEntity.notFound().build();
    }
    Service updated =
        new Service(
            id,
            dto.name(),
            dto.articleId() != null ? dto.articleId() : 0,
            dto.description(),
            ArticleController.orZero(dto.hourlyRate()),
            dto.unit() != null ? dto.unit() : "Std",
            "");
    updated.setEquipmentId(dto.equipmentId() != null ? dto.equipmentId() : 0);
    updated.setTaxRatePercent(ArticleController.catalogRate(dto.taxRate()));
    serviceRepository.update(updated);
    return ResponseEntity.ok(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteService(@PathVariable Integer id) {
    serviceRepository.delete(id);
    return ResponseEntity.ok().build();
  }
}
