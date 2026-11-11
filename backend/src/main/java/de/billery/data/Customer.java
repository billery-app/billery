package de.billery.data;

/**
 * Represents a customer entity in the invoice management system. Each customer has personal
 * information including contact details and tax identification.
 */
public class Customer {

  private int id;
  private String firstName;
  private String lastName;
  private String postalCode;
  private String city;
  private String street;
  private int houseNumber;
  private String phone;
  private String email;
  private String taxId;
  private String note;
  private String buyerReference = "";

  /**
   * Constructs a new Customer with all properties including ID.
   *
   * @param id the unique identifier for the customer
   * @param firstName the customer's first name
   * @param lastName the customer's last name
   * @param postalCode the customer's postal code
   * @param city the customer's city
   * @param street the customer's street address
   * @param houseNumber the customer's house number
   * @param phone the customer's phone number
   * @param email the customer's email address
   * @param taxId the customer's tax identification number
   * @param note additional notes about the customer
   */
  public Customer(
      int id,
      String firstName,
      String lastName,
      String postalCode,
      String city,
      String street,
      int houseNumber,
      String phone,
      String email,
      String taxId,
      String note) {
    this.id = id;
    this.firstName = firstName;
    this.lastName = lastName;
    this.postalCode = postalCode;
    this.city = city;
    this.street = street;
    this.houseNumber = houseNumber;
    this.phone = phone;
    this.email = email;
    this.taxId = taxId;
    this.note = note;
  }

  /**
   * Constructs a new Customer without specifying an ID. The ID will be set to 0 and should be
   * updated when the customer is saved to the database.
   *
   * @param firstName the customer's first name
   * @param lastName the customer's last name
   * @param address the customer's address
   * @param phone the customer's phone number
   * @param email the customer's email address
   * @param taxId the customer's tax identification number
   * @param note additional notes about the customer
   */
  public Customer(
      String firstName,
      String lastName,
      String postalCode,
      String city,
      String street,
      int houseNumber,
      String phone,
      String email,
      String taxId,
      String note) {
    this(0, firstName, lastName, postalCode, city, street, houseNumber, phone, email, taxId, note);
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getPostalCode() {
    return postalCode;
  }

  public void setPostalCode(String postalCode) {
    this.postalCode = postalCode;
  }

  public String getCity() {
    return city;
  }

  public void setCity(String city) {
    this.city = city;
  }

  public String getStreet() {
    return street;
  }

  public void setStreet(String street) {
    this.street = street;
  }

  public int getHouseNumber() {
    return houseNumber;
  }

  public void setHouseNumber(int houseNumber) {
    this.houseNumber = houseNumber;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getTaxId() {
    return taxId;
  }

  public void setTaxId(String taxId) {
    this.taxId = taxId;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  /**
   * The Käuferreferenz for the XRechnung (BT-10), e.g. the Leitweg-ID a public-sector customer
   * requires. Empty if the customer gave none.
   *
   * @return the reference, never {@code null}
   */
  public String getBuyerReference() {
    return buyerReference;
  }

  public void setBuyerReference(String buyerReference) {
    this.buyerReference = buyerReference != null ? buyerReference : "";
  }

  /**
   * Returns a string representation of the customer in the format "FirstName LastName". This is
   * commonly used for display purposes in UI components.
   *
   * @return a string representation of the customer
   */
  @Override
  public String toString() {
    return firstName + " " + lastName;
  }
}
