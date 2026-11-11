package de.billery.data;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

/**
 * Represents a line item within an invoice in the invoice management system.
 *
 * <p>Invoice items can represent either articles (products) or services, and contain quantity,
 * pricing, and reference information to link back to the original article or service definitions.
 *
 * <p>Quantity, prices and the VAT rate are {@link BigDecimal}s (the columns are {@code
 * DECIMAL(10,2)} / {@code DECIMAL(5,2)}); a total derived here is rounded half-up to cents.
 *
 * <p>Additional display fields are included for UI purposes but are not stored directly in the
 * database.
 *
 * @author Invoice Management System
 * @version 1.0
 */
public class InvoiceItem {

  /**
   * Enumeration defining the types of items that can be included in an invoice. Items can be either
   * articles (physical products) or services (work-based offerings).
   */
  public enum ItemType {
    /** Represents a physical article or product */
    ARTICLE("article"),
    /** Represents a service or work-based offering */
    SERVICE("service"),
    /** Represents a free-text custom line item */
    CUSTOM("custom");

    private final String value;

    /**
     * Constructor for ItemType enum values.
     *
     * @param value the string representation of the item type
     */
    ItemType(String value) {
      this.value = value;
    }

    /**
     * Gets the string value representation of the item type.
     *
     * @return the string value
     */
    public String getValue() {
      return value;
    }

    /**
     * Converts a string value to the corresponding ItemType enum.
     *
     * @param value the string value to convert
     * @return the corresponding ItemType
     * @throws IllegalArgumentException if the value doesn't match any ItemType
     */
    public static ItemType fromValue(String value) {
      if (value == null) {
        return ARTICLE;
      }
      String lowerValue = value.toLowerCase(Locale.ROOT);
      for (ItemType type : values()) {
        if (lowerValue.equals(type.getValue())) {
          return type;
        }
      }
      throw new IllegalArgumentException("Unknown ItemType: " + value);
    }
  }

  private int id;
  private int invoiceId;
  private String itemType;
  private int itemId;
  private BigDecimal quantity;
  private BigDecimal unitPrice;
  private BigDecimal totalPrice;

  // Stored on the line, frozen like the price (catalog changes never alter existing documents).
  private String itemName;
  private String itemDescription;
  private String unit;

  /** VAT rate of this line in percent (19 or 7); ignored unless the document is STANDARD-taxed. */
  private BigDecimal taxRatePercent = BigDecimal.valueOf(19);

  private int parentItemId;
  private List<InvoiceItem> children;

  /**
   * Default constructor creating an empty invoice item. Initializes with default values for a
   * single article item.
   */
  public InvoiceItem() {
    this(0, 0, ItemType.ARTICLE, 0, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO);
  }

  /**
   * Creates an unsaved item whose total is quantity × unit price, rounded half-up to cents.
   *
   * @param invoiceId the document id
   * @param itemType the item type
   * @param itemId the referenced article or service id
   * @param quantity the quantity
   * @param unitPrice the unit price
   */
  public InvoiceItem(
      int invoiceId, ItemType itemType, int itemId, BigDecimal quantity, BigDecimal unitPrice) {
    this(0, invoiceId, itemType, itemId, quantity, unitPrice, lineTotal(quantity, unitPrice));
  }

  public InvoiceItem(
      int id,
      int invoiceId,
      ItemType itemType,
      int itemId,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal totalPrice) {
    this.id = id;
    this.invoiceId = invoiceId;
    this.itemType = itemType.getValue();
    this.itemId = itemId;
    this.quantity = quantity;
    this.unitPrice = unitPrice;
    this.totalPrice = totalPrice;
    this.itemName = "";
    this.itemDescription = "";
    this.unit = "";
  }

  /** Constructor that takes the item type as a string (for database hydration). */
  public InvoiceItem(
      int id,
      int invoiceId,
      String itemType,
      int itemId,
      BigDecimal quantity,
      BigDecimal unitPrice,
      BigDecimal totalPrice) {
    this(id, invoiceId, ItemType.fromValue(itemType), itemId, quantity, unitPrice, totalPrice);
  }

  public int getId() {
    return id;
  }

  public int getInvoiceId() {
    return invoiceId;
  }

  public String getItemType() {
    return itemType;
  }

  public ItemType getItemTypeEnum() {
    return ItemType.fromValue(itemType);
  }

  public int getItemId() {
    return itemId;
  }

  public BigDecimal getQuantity() {
    return quantity;
  }

  public BigDecimal getUnitPrice() {
    return unitPrice;
  }

  public BigDecimal getTotalPrice() {
    return totalPrice;
  }

  /**
   * The stored display name of this line. Rows saved by the SPA always carry one; legacy rows
   * without one get the catalog name resolved by {@code InvoiceRepository.nestInvoiceItems} on
   * read. The entity itself never touches the database.
   *
   * @return the stored item name, or {@code ""} when none was saved
   */
  public String getItemName() {
    return itemName;
  }

  public String getItemDescription() {
    return itemDescription;
  }

  public String getUnit() {
    return unit;
  }

  public void setId(int id) {
    this.id = id;
  }

  public void setInvoiceId(int invoiceId) {
    this.invoiceId = invoiceId;
  }

  public void setItemType(ItemType itemType) {
    this.itemType = itemType.getValue();
  }

  public void setItemType(String itemType) {
    this.itemType = itemType;
  }

  public void setItemId(int itemId) {
    this.itemId = itemId;
  }

  public void setQuantity(BigDecimal quantity) {
    this.quantity = quantity;
    autoUpdateTotalPrice();
  }

  public void setUnitPrice(BigDecimal unitPrice) {
    this.unitPrice = unitPrice;
    autoUpdateTotalPrice();
  }

  public void setTotalPrice(BigDecimal totalPrice) {
    this.totalPrice = totalPrice;
  }

  public void setItemName(String itemName) {
    this.itemName = itemName != null ? itemName : "";
  }

  public void setItemDescription(String itemDescription) {
    this.itemDescription = itemDescription != null ? itemDescription : "";
  }

  public void setUnit(String unit) {
    this.unit = unit != null ? unit : "";
  }

  /**
   * The VAT rate of this line in percent. Only applies when the document's tax treatment is {@code
   * STANDARD}; see {@code VatCalculator}.
   *
   * @return the rate, e.g. 19.00 or 7.00
   */
  public BigDecimal getTaxRatePercent() {
    return taxRatePercent;
  }

  public void setTaxRatePercent(BigDecimal taxRatePercent) {
    this.taxRatePercent = taxRatePercent;
  }

  /** Recomputes the total as quantity × unit price, rounded half-up to cents. */
  public void updateTotalPrice() {
    autoUpdateTotalPrice();
  }

  private void autoUpdateTotalPrice() {
    setTotalPrice(lineTotal(quantity, unitPrice));
  }

  /**
   * Quantity × unit price, rounded half-up to cents; a missing factor counts as 0.
   *
   * @param quantity the quantity
   * @param unitPrice the unit price
   * @return the line total with scale 2
   */
  public static BigDecimal lineTotal(BigDecimal quantity, BigDecimal unitPrice) {
    if (quantity == null || unitPrice == null) {
      return BigDecimal.ZERO.setScale(2);
    }
    return quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
  }

  public boolean isArticle() {
    return getItemTypeEnum() == ItemType.ARTICLE;
  }

  public boolean isService() {
    return getItemTypeEnum() == ItemType.SERVICE;
  }

  @Override
  public String toString() {
    return String.format(
        "%s: %s (%.2f x %.2f€ = %.2f€)",
        getItemTypeEnum(), getItemName(), getQuantity(), getUnitPrice(), getTotalPrice());
  }

  public int getParentItemId() {
    return parentItemId;
  }

  public void setParentItemId(int parentItemId) {
    this.parentItemId = parentItemId;
  }

  public List<InvoiceItem> getChildren() {
    return children;
  }

  public void setChildren(List<InvoiceItem> children) {
    this.children = children;
  }
}
