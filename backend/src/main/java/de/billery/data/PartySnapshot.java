package de.billery.data;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;

/**
 * Seller and buyer exactly as they were when a document was issued. Stored as JSON in {@code
 * invoice.party_snapshot}, so the XRechnung of an issued invoice never changes when the company or
 * customer data is edited later (GoBD). Drafts have no snapshot; they use the live data.
 *
 * @param seller the issuing company (from {@link Owner})
 * @param buyer the customer
 */
public record PartySnapshot(Party seller, Party buyer) {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * One party of the invoice.
   *
   * @param name company or person name
   * @param contactName the person to contact (seller only)
   * @param street street and house number
   * @param postalCode postal code
   * @param city city
   * @param countryCode ISO 3166-1 alpha-2; always {@code DE} for now (no country field exists)
   * @param email e-mail address
   * @param phone phone number
   * @param taxId VAT ID ({@code DE…}) or tax number (Steuernummer)
   * @param iban the seller's IBAN (empty for the buyer)
   * @param buyerReference XRechnung Käuferreferenz / Leitweg-ID (buyer only)
   */
  public record Party(
      String name,
      String contactName,
      String street,
      String postalCode,
      String city,
      String countryCode,
      String email,
      String phone,
      String taxId,
      String iban,
      String buyerReference) {}

  /**
   * Captures the current owner and customer data.
   *
   * @param owner the issuing company, may be {@code null} (gives an empty seller)
   * @param customer the customer, may be {@code null} (gives an empty buyer)
   * @return the snapshot
   */
  public static PartySnapshot capture(Owner owner, Customer customer) {
    Party seller =
        owner == null
            ? emptyParty()
            : new Party(
                blank(owner.getCompanyName()).isEmpty()
                    ? personName(owner.getFirstName(), owner.getLastName())
                    : owner.getCompanyName(),
                personName(owner.getFirstName(), owner.getLastName()),
                streetLine(owner.getStreet(), owner.getHouseNumber()),
                blank(owner.getPostalCode()),
                blank(owner.getCity()),
                "DE",
                blank(owner.getEmail()),
                blank(owner.getPhone()),
                blank(owner.getTaxId()),
                blank(owner.getIban()),
                "");
    Party buyer =
        customer == null
            ? emptyParty()
            : new Party(
                personName(customer.getFirstName(), customer.getLastName()),
                "",
                streetLine(customer.getStreet(), customer.getHouseNumber()),
                blank(customer.getPostalCode()),
                blank(customer.getCity()),
                "DE",
                blank(customer.getEmail()),
                blank(customer.getPhone()),
                blank(customer.getTaxId()),
                "",
                blank(customer.getBuyerReference()));
    return new PartySnapshot(seller, buyer);
  }

  /**
   * Serialises the snapshot.
   *
   * @return the JSON text
   */
  public String toJson() {
    try {
      return JSON.writeValueAsString(this);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  /**
   * Reads a stored snapshot.
   *
   * @param json the stored JSON
   * @return the snapshot, or {@code null} for an empty value (drafts, legacy documents)
   */
  public static PartySnapshot fromJson(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return JSON.readValue(json, PartySnapshot.class);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Party emptyParty() {
    return new Party("", "", "", "", "", "DE", "", "", "", "", "");
  }

  private static String personName(String first, String last) {
    return (blank(first) + " " + blank(last)).trim();
  }

  private static String streetLine(String street, int houseNumber) {
    String line = blank(street);
    return houseNumber > 0 ? (line + " " + houseNumber).trim() : line;
  }

  private static String blank(String value) {
    return value == null ? "" : value.trim();
  }
}
