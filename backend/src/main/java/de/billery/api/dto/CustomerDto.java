package de.billery.api.dto;

/**
 * A customer as sent by the SPA.
 *
 * @param buyerReference XRechnung Käuferreferenz (BT-10), e.g. a Leitweg-ID; optional
 */
public record CustomerDto(
    Integer id,
    String firstName,
    String lastName,
    String postalCode,
    String city,
    String street,
    Integer houseNumber,
    String phone,
    String email,
    String taxId,
    String note,
    String buyerReference) {}
