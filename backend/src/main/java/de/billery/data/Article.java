package de.billery.data;

import java.math.BigDecimal;

/**
 * Represents an article/product entity in the invoice management system.
 *
 * <p>Articles contain information about products or services that can be invoiced to customers,
 * including purchase costs, sales prices, and descriptions. Money and rates are {@link BigDecimal}
 * (the columns are {@code DECIMAL}), so values round-trip without binary floating-point error.
 *
 * @author Invoice Management System
 * @version 1.0
 */
public class Article {

  private int id;
  private String name;
  private BigDecimal purchaseValue;
  private BigDecimal salesValue;
  private String unit;
  private String note;
  private String description;
  private BigDecimal taxRatePercent = BigDecimal.valueOf(19);

  /**
   * Creates a new Article with all properties including ID. This constructor is typically used when
   * loading existing articles from the database.
   *
   * @param id the unique identifier for the article
   * @param name the name of the article
   * @param purchaseValue the purchase cost/value
   * @param salesValue the sales price/value
   * @param unit the unit of measurement
   * @param note additional notes
   * @param description detailed description
   */
  public Article(
      int id,
      String name,
      BigDecimal purchaseValue,
      BigDecimal salesValue,
      String unit,
      String note,
      String description) {
    this.id = id;
    this.name = name;
    this.purchaseValue = purchaseValue;
    this.salesValue = salesValue;
    this.unit = unit;
    this.note = note;
    this.description = description;
  }

  /**
   * Creates a new Article without specifying an ID (defaults to 0). This constructor is typically
   * used when creating new articles before database insertion.
   *
   * @param name the name of the article
   * @param purchaseValue the purchase cost/value
   * @param salesValue the sales price/value
   * @param unit the unit of measurement
   * @param note additional notes
   * @param description detailed description
   */
  public Article(
      String name,
      BigDecimal purchaseValue,
      BigDecimal salesValue,
      String unit,
      String note,
      String description) {
    this(0, name, purchaseValue, salesValue, unit, note, description);
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

  public BigDecimal getPurchaseValue() {
    return purchaseValue;
  }

  public void setPurchaseValue(BigDecimal purchaseValue) {
    this.purchaseValue = purchaseValue;
  }

  public BigDecimal getSalesValue() {
    return salesValue;
  }

  public void setSalesValue(BigDecimal salesValue) {
    this.salesValue = salesValue;
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

  public String getDescription() {
    return description;
  }

  public void setDescription(String description) {
    this.description = description;
  }

  @Override
  public String toString() {
    return "Article{"
        + "id="
        + id
        + ", name='"
        + name
        + '\''
        + ", purchaseValue="
        + purchaseValue
        + ", price="
        + salesValue
        + ", unit='"
        + unit
        + '\''
        + ", note='"
        + note
        + '\''
        + '}';
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
