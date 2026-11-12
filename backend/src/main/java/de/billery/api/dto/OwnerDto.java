package de.billery.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

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
    @JsonAlias("smallBusiness") Boolean isSmallBusiness) {

  /**
   * Whether the owner is a Kleinunternehmer (§ 19 UStG). Missing or {@code null} means no: Jackson
   * 3 refuses a missing primitive, and the owner the API returns names the flag {@code
   * smallBusiness} (accepted here as well), so an edited owner sent back as it was read used to
   * fail with 400.
   *
   * @return {@code true} only for an explicit {@code true}
   */
  public boolean smallBusiness() {
    return Boolean.TRUE.equals(isSmallBusiness);
  }
}
