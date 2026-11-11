package de.billery.data;

import java.math.BigDecimal;

/**
 * Represents a service entity in the invoice management system.
 *
 * <p>Services are work-based offerings that can be invoiced to customers, optionally linked to
 * articles for materials used in the service. The price and VAT rate are {@link BigDecimal} (the
 * columns are {@code DECIMAL}).
 *
 * @author Invoice Management System
 * @version 1.0
 */
public class Service {

  private int id;
  private String name;
  private String description;
  private BigDecimal price;
  private String unit;
  private String note;
  private int articleId;

  private int equipmentId;
  private BigDecimal taxRatePercent = BigDecimal.valueOf(19);

  /**
   * Creates a new Service with all properties including ID. This constructor is typically used when
   * loading existing services from the database.
   *
   * @param id the unique identifier for the service
   * @param name the name of the service
   * @param articleId the ID of the associated article (0 if none)
   * @param description detailed description of the service
   * @param price the price charged for the service
   * @param unit the unit of measurement
   * @param note additional notes
   */
  public Service(
      int id,
      String name,
      int articleId,
      String description,
      BigDecimal price,
      String unit,
      String note) {
    this.id = id;
    this.name = name;
    this.articleId = articleId;
    this.description = description;
    this.price = price;
    this.unit = unit;
    this.note = note;
  }

  /**
   * Creates a new Service without specifying an ID (defaults to 0). This constructor is typically
   * used when creating new services before database insertion.
   *
   * @param name the name of the service
   * @param articleId the ID of the associated article (0 if none)
   * @param description detailed description of the service
   * @param price the price charged for the service
   * @param unit the unit of measurement
   * @param note additional notes
   */
  public Service(
      String name, int articleId, String description, BigDecimal price, String unit, String note) {
    this(0, name, articleId, description, price, unit, note);
  }

  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  public BigDecimal getPrice() {
    return price;
  }

  public void setPrice(BigDecimal price) {
    this.price = price;
  }

  public String getUnit() {
    return unit;
  }

  public void setUnit(String unit) {
    this.unit = unit;
  }

  public String getNote() {
    return note;
  }

  public void setNote(String note) {
    this.note = note;
  }

  /**
   * Gets the ID of the associated article.
   *
   * @return the article ID (0 if no article is associated)
   */
  public int getArticleId() {
    return articleId;
  }

  /**
   * Sets the ID of the associated article.
   *
   * @param articleId the article ID (0 if no article is associated)
   */
  public void setArticleId(int articleId) {
    this.articleId = articleId;
  }

  @Override
  public String toString() {
    return "Service{"
        + "id="
        + id
        + ", name='"
        + name
        + '\''
        + ", articleId="
        + articleId
        + ", description='"
        + description
        + '\''
        + ", price="
        + price
        + ", unit='"
        + unit
        + '\''
        + ", note='"
        + note
        + '\''
        + '}';
  }

  public int getEquipmentId() {
    return equipmentId;
  }

  public void setEquipmentId(int equipmentId) {
    this.equipmentId = equipmentId;
  }

  /**
   * Default VAT rate in percent that new invoice lines with this entry get (19 or 7).
   *
   * @return the rate
   */
  public BigDecimal getTaxRatePercent() {
    return taxRatePercent;
  }

  public void setTaxRatePercent(BigDecimal taxRatePercent) {
    this.taxRatePercent = taxRatePercent;
  }
}
