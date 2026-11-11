package de.billery.api.controller;

import de.billery.api.dto.OwnerDto;
import de.billery.data.Owner;
import de.billery.repository.OwnerRepository;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/owners")
public class OwnerController {

  private final OwnerRepository ownerRepository;

  public OwnerController(OwnerRepository ownerRepository) {
    this.ownerRepository = ownerRepository;
  }

  @GetMapping
  public List<Owner> getAllOwners() {
    return ownerRepository.findAll();
  }

  @GetMapping("/primary")
  public ResponseEntity<Owner> getPrimaryOwner() {
    Owner owner = ownerRepository.findOwner();
    return owner != null ? ResponseEntity.ok(owner) : ResponseEntity.notFound().build();
  }

  @PostMapping
  public Owner createOwner(@RequestBody OwnerDto ownerDto) {
    Owner owner =
        new Owner(
            0,
            ownerDto.firstName(),
            ownerDto.lastName(),
            ownerDto.companyName(),
            ownerDto.companyType(),
            ownerDto.postalCode(),
            ownerDto.city(),
            ownerDto.street(),
            houseNumberOrZero(ownerDto.houseNumber()),
            ownerDto.phone(),
            ownerDto.email(),
            ownerDto.url(),
            ownerDto.taxId(),
            ownerDto.iban(),
            ownerDto.isSmallBusiness());
    return ownerRepository.save(owner);
  }

  @PutMapping("/{id}")
  public ResponseEntity<Owner> updateOwner(
      @PathVariable Integer id, @RequestBody OwnerDto ownerDto) {
    Owner existing = ownerRepository.findById(id);
    if (existing == null) {
      return ResponseEntity.notFound().build();
    }
    Owner updated =
        new Owner(
            id,
            ownerDto.firstName(),
            ownerDto.lastName(),
            ownerDto.companyName(),
            ownerDto.companyType(),
            ownerDto.postalCode(),
            ownerDto.city(),
            ownerDto.street(),
            houseNumberOrZero(ownerDto.houseNumber()),
            ownerDto.phone(),
            ownerDto.email(),
            ownerDto.url(),
            ownerDto.taxId(),
            ownerDto.iban(),
            ownerDto.isSmallBusiness());
    ownerRepository.update(updated);
    return ResponseEntity.ok(updated);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deleteOwner(@PathVariable Integer id) {
    ownerRepository.delete(id);
    return ResponseEntity.ok().build();
  }

  /** The house number is optional (e.g. P.O. boxes); the entity stores 0 for "none". */
  private static int houseNumberOrZero(Integer houseNumber) {
    return houseNumber != null ? houseNumber : 0;
  }
}
