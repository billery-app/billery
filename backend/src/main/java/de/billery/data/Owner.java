package de.billery.data;

/**
 * Represents the business owner entity in the invoice management system.
 *
 * <p>The owner represents the person or company that owns the invoicing business and appears as the
 * sender/issuer on invoices and documents.
 *
 * @author Invoice Management System
 * @version 1.0
 */
public class Owner {

  private int id;
  private String firstName;
  private String lastName;
  private String companyName;
  private String companyType;
  private String postalCode;
  private String city;
  private String street;
  private int houseNumber;
  private String phone;
  private String email;
  private String url;
  private String taxId;
  private String iban;

  /** Small business exemption (Kleinunternehmer gemäß § 19 UStG) */
  private boolean smallBusiness;

  /**
   * Creates a new Owner with all business information.
   *
   * @param id the unique identifier for the owner
   * @param firstName the first name of the owner
   * @param lastName the last name of the owner
   * @param companyName the company name
   * @param companyType the type of company
   * @param postalCode the postal code
   * @param city the city
   * @param street the street
   * @param houseNumber the house number
   * @param phone the phone number
   * @param email the email address
   * @param url the website URL
   * @param taxId the tax ID
   * @param iban the bank IBAN
   * @param smallBusiness the small business exemption flag
   */
  public Owner(
      int id,
      String firstName,
      String lastName,
      String companyName,
      String companyType,
      String postalCode,
      String city,
      String street,
      int houseNumber,
      String phone,
      String email,
      String url,
      String taxId,
      String iban,
      boolean smallBusiness) {
    this.id = id;
    this.firstName = firstName;
    this.lastName = lastName;
    this.companyName = companyName;
    this.companyType = companyType;
    this.postalCode = postalCode;
    this.city = city;
    this.street = street;
    this.houseNumber = houseNumber;
    this.phone = phone;
    this.email = email;
    this.url = url;
    this.taxId = taxId;
    this.iban = iban;
    this.smallBusiness = smallBusiness; // Default to false, can be set via setter if needed
  }

  public int getId() {
    return id;
  }

  public String getFirstName() {
    return firstName;
  }

  public String getEmail() {
    return email;
  }

  public String getPhone() {
    return phone;
  }

  public String getLastName() {
    return lastName;
  }

  public String getCompanyName() {
    return companyName;
  }

  public String getCompanyType() {
    return companyType;
  }

  public String getStreet() {
    return street;
  }

  public int getHouseNumber() {
    return houseNumber;
  }

  public String getPostalCode() {
    return postalCode;
  }

  public String getCity() {
    return city;
  }

  public String getUrl() {
    return url;
  }

  public String getTaxId() {
    return taxId;
  }

  public String getIban() {
    return iban;
  }

  @SuppressWarnings("PMD.BooleanGetMethodName") // JSON property name used by the SPA
  public Boolean getSmallBusiness() {
    return smallBusiness;
  }

  public void setId(int id) {
    this.id = id;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public void setCompanyName(String companyName) {
    this.companyName = companyName;
  }

  public void setCompanyType(String companyType) {
    this.companyType = companyType;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public void setStreet(String street) {
    this.street = street;
  }

  public void setHouseNumber(int houseNumber) {
    this.houseNumber = houseNumber;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public void setUrl(String url) {
    this.url = url;
  }

  public void setTaxId(String taxId) {
    this.taxId = taxId;
  }

  public void setIban(String iban) {
    this.iban = iban;
  }

  public boolean isSmallBusiness() {
    return smallBusiness;
  }
}
