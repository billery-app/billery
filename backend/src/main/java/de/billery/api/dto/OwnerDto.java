package de.billery.api.dto;

public record OwnerDto(
    Integer id,
    String firstName,
    String lastName,
    String companyName,
    String companyType,
    String postalCode,
    String city,
    String street,
    Integer houseNumber,
    String phone,
    String email,
    String url,
    String taxId,
    String iban,
    boolean isSmallBusiness) {}
