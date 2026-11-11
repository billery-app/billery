package de.billery.data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Represents an invoice entity in the invoice management system. This is a plain Java class for
 * REST API serialization.
 *
 * <p>Invoices contain information about billing transactions including customer details, dates,
 * pricing, tax information, and payment status. Supports versioning for document updates.
 *
 * @author Invoice Management System
 * @version 2.0
 */
public class Invoice {

  /** Shown instead of a document number while an invoice is still a draft. */
  public static final String DRAFT_LABEL = "ENTWURF";

  private int id;
  private int customerId;
  private String invoiceDate;
  private String dueDate;
  private BigDecimal totalPrice;

  /** Default VAT rate in percent (19 means 19 %); stored as a fraction in the database. */
  private BigDecimal taxRate;

  private String paidDate;
  private String notes;

  // === v2 fields ===
  private String documentNumber;
  private int version;
  private int latest;
  private int versionGroupId;
  private int documentTypeId;
  private int documentStatusId;
  private String workStartDate;
  private String workEndDate;
  private int parentInvoiceId;
  private int periodId;
  private int convertedToInvoiceId;
  private String offerValidUntil;
  private String costCalculationBasis;
  private String termsAndConditions;
  private int archived;
  private String renderedHtml;
  private Integer templateId;
  private int cancelsInvoiceId;
  private String taxTreatment = "STANDARD";
  private String taxExemptionReason = "";
  private String partySnapshot = "";

  /** Tax of the rendered document as computed by {@code VatCalculator}; not persisted. */
  private BigDecimal computedTaxAmount;

  public Invoice() {
    this.id = 0;
    this.customerId = 0;
    this.invoiceDate = "";
    this.dueDate = "";
    this.totalPrice = BigDecimal.ZERO;
    this.taxRate = BigDecimal.valueOf(19);
    this.paidDate = "";
    this.notes = "";
    this.documentNumber = "";
    this.version = 1;
    this.latest = 1;
    this.documentTypeId = 1;
    this.documentStatusId = 1;
    this.versionGroupId = 0;
    this.workStartDate = "";
    this.workEndDate = "";
    this.parentInvoiceId = 0;
    this.periodId = 0;
    this.convertedToInvoiceId = 0;
    this.offerValidUntil = "";
    this.costCalculationBasis = "";
    this.termsAndConditions = "";
    this.archived = 0;
    this.renderedHtml = "";
  }

  public Invoice(
      int id,
      int customerId,
      String invoiceDate,
      String dueDate,
      BigDecimal totalPrice,
      BigDecimal taxRate,
      String paidDate,
      String notes) {
    this(
        id,
        customerId,
        invoiceDate,
        dueDate,
        totalPrice,
        taxRate,
        paidDate,
        notes,
        "",
        1,
        1,
        1,
        1,
        0,
        "",
        "",
        0,
        0,
        0,
        "",
        "",
        "",
        0);
  }

  public Invoice(
      int id,
      int customerId,
      String invoiceDate,
      String dueDate,
      BigDecimal totalPrice,
      BigDecimal taxRate,
      String paidDate,
      String notes,
      String documentNumber,
      int version,
      int isLatest,
      int documentTypeId,
      int documentStatusId,
      int versionGroupId,
      String workStartDate,
      String workEndDate,
      int parentInvoiceId,
      int periodId,
      int convertedToInvoiceId,
      String offerValidUntil,
      String costCalculationBasis,
      String termsAndConditions,
      int archived) {
    this.id = id;
    this.customerId = customerId;
    this.invoiceDate = invoiceDate != null ? invoiceDate : "";
    this.dueDate = dueDate != null ? dueDate : "";
    this.totalPrice = totalPrice;
    this.taxRate = taxRate;
    this.paidDate = paidDate != null ? paidDate : "";
    this.notes = notes != null ? notes : "";
    this.documentNumber = documentNumber != null ? documentNumber : "";
    this.version = version;
    this.latest = isLatest;
    this.documentTypeId = documentTypeId;
    this.versionGroupId = versionGroupId;
    this.documentStatusId = documentStatusId;
    this.workStartDate = workStartDate != null ? workStartDate : "";
    this.workEndDate = workEndDate != null ? workEndDate : "";
    this.parentInvoiceId = parentInvoiceId;
    this.periodId = periodId;
    this.convertedToInvoiceId = convertedToInvoiceId;
    this.offerValidUntil = offerValidUntil != null ? offerValidUntil : "";
    this.costCalculationBasis = costCalculationBasis != null ? costCalculationBasis : "";
    this.termsAndConditions = termsAndConditions != null ? termsAndConditions : "";
    this.archived = archived;
  }

  // Standard Getters and Setters
  public int getId() {
    return id;
  }

  public void setId(int id) {
    this.id = id;
  }

  public int getCustomerId() {
    return customerId;
  }

  public void setCustomerId(int customerId) {
    this.customerId = customerId;
  }

  public String getInvoiceDate() {
    return invoiceDate;
  }

  public void setInvoiceDate(String invoiceDate) {
    this.invoiceDate = invoiceDate != null ? invoiceDate : "";
  }

  public String getDueDate() {
    return dueDate;
  }

  public void setDueDate(String dueDate) {
    this.dueDate = dueDate != null ? dueDate : "";
  }

  public BigDecimal getTotalPrice() {
    return totalPrice;
  }

  public void setTotalPrice(BigDecimal totalPrice) {
    this.totalPrice = totalPrice;
  }

  public BigDecimal getTaxRate() {
    return taxRate;
  }

  public void setTaxRate(BigDecimal taxRate) {
    this.taxRate = taxRate;
  }

  public String getPaidDate() {
    return paidDate;
  }

  public void setPaidDate(String paidDate) {
    this.paidDate = paidDate != null ? paidDate : "";
  }

  public LocalDate getPaidDateAsLocalDate() {
    if (paidDate == null || paidDate.isEmpty()) {
      return null;
    }
    try {
      return LocalDate.parse(paidDate);
    } catch (Exception e) {
      return null;
    }
  }

  public boolean isPaid() {
    return paidDate != null && !paidDate.isEmpty();
  }

  public void setPaid(boolean paid) {
    if (paid && (paidDate == null || paidDate.isEmpty())) {
      this.paidDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
    } else if (!paid) {
      this.paidDate = "";
    }
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes != null ? notes : "";
  }

  // v2 Getters and Setters
  public String getDocumentNumber() {
    return documentNumber;
  }

  public void setDocumentNumber(String documentNumber) {
    this.documentNumber = documentNumber != null ? documentNumber : "";
  }

  public int getVersion() {
    return version;
  }

  public void setVersion(int version) {
    this.version = version;
  }

  public int getIsLatest() {
    return latest;
  }

  public void setIsLatest(int isLatest) {
    this.latest = isLatest;
  }

  public boolean isLatest() {
    return latest == 1;
  }

  public int getDocumentTypeId() {
    return documentTypeId;
  }

  public void setDocumentTypeId(int documentTypeId) {
    this.documentTypeId = documentTypeId;
  }

  public int getDocumentStatusId() {
    return documentStatusId;
  }

  public void setDocumentStatusId(int documentStatusId) {
    this.documentStatusId = documentStatusId;
  }

  public int getVersionGroupId() {
    return versionGroupId;
  }

  public void setVersionGroupId(int versionGroupId) {
    this.versionGroupId = versionGroupId;
  }

  public String getWorkStartDate() {
    return workStartDate;
  }

  public void setWorkStartDate(String workStartDate) {
    this.workStartDate = workStartDate != null ? workStartDate : "";
  }

  public String getWorkEndDate() {
    return workEndDate;
  }

  public void setWorkEndDate(String workEndDate) {
    this.workEndDate = workEndDate != null ? workEndDate : "";
  }

  public int getParentInvoiceId() {
    return parentInvoiceId;
  }

  public void setParentInvoiceId(int parentInvoiceId) {
    this.parentInvoiceId = parentInvoiceId;
  }

  public int getPeriodId() {
    return periodId;
  }

  public void setPeriodId(int periodId) {
    this.periodId = periodId;
  }

  public int getConvertedToInvoiceId() {
    return convertedToInvoiceId;
  }

  public void setConvertedToInvoiceId(int convertedToInvoiceId) {
    this.convertedToInvoiceId = convertedToInvoiceId;
  }

  public String getOfferValidUntil() {
    return offerValidUntil;
  }

  public void setOfferValidUntil(String offerValidUntil) {
    this.offerValidUntil = offerValidUntil != null ? offerValidUntil : "";
  }

  public String getCostCalculationBasis() {
    return costCalculationBasis;
  }

  public void setCostCalculationBasis(String costCalculationBasis) {
    this.costCalculationBasis = costCalculationBasis != null ? costCalculationBasis : "";
  }

  public String getTermsAndConditions() {
    return termsAndConditions;
  }

  public void setTermsAndConditions(String termsAndConditions) {
    this.termsAndConditions = termsAndConditions != null ? termsAndConditions : "";
  }

  public int getArchived() {
    return archived;
  }

  public void setArchived(int archived) {
    this.archived = archived;
  }

  public boolean isArchived() {
    return archived == 1;
  }

  public String getRenderedHtml() {
    return renderedHtml;
  }

  public void setRenderedHtml(String renderedHtml) {
    this.renderedHtml = renderedHtml != null ? renderedHtml : "";
  }

  public Integer getTemplateId() {
    return templateId;
  }

  public void setTemplateId(Integer templateId) {
    this.templateId = templateId;
  }

  /**
   * The invoice this document cancels. Only set on a {@code CREDIT_NOTE} (Stornorechnung).
   *
   * @return the cancelled invoice's id, or 0 if this document cancels nothing
   */
  public int getCancelsInvoiceId() {
    return cancelsInvoiceId;
  }

  public void setCancelsInvoiceId(int cancelsInvoiceId) {
    this.cancelsInvoiceId = cancelsInvoiceId;
  }

  /**
   * How VAT applies to the whole document: {@code STANDARD}, {@code SMALL_BUSINESS}, {@code
   * REVERSE_CHARGE} or {@code TAX_EXEMPT} (see {@code TaxTreatment}).
   *
   * @return the treatment name
   */
  public String getTaxTreatment() {
    return taxTreatment;
  }

  public void setTaxTreatment(String taxTreatment) {
    this.taxTreatment = taxTreatment == null || taxTreatment.isBlank() ? "STANDARD" : taxTreatment;
  }

  /**
   * The legal basis printed for a {@code TAX_EXEMPT} document, e.g. "gemäß § 4 Nr. 14 UStG".
   *
   * @return the reason, empty if none
   */
  public String getTaxExemptionReason() {
    return taxExemptionReason;
  }

  public void setTaxExemptionReason(String taxExemptionReason) {
    this.taxExemptionReason = taxExemptionReason != null ? taxExemptionReason : "";
  }

  /**
   * Seller and buyer data as JSON, frozen when the document was issued ({@code PartySnapshot}).
   * Empty for drafts and for documents issued before the snapshot existed.
   *
   * @return the JSON, or empty
   */
  public String getPartySnapshot() {
    return partySnapshot;
  }

  public void setPartySnapshot(String partySnapshot) {
    this.partySnapshot = partySnapshot != null ? partySnapshot : "";
  }

  // Calculated Properties
  public BigDecimal getNetAmount() {
    return totalPrice;
  }

  /**
   * Tax at the document's single default rate. Only an approximation for documents whose lines all
   * carry that rate; real amounts come from {@code VatCalculator}.
   *
   * @return net × default rate
   */
  public BigDecimal getTaxAmount() {
    if (computedTaxAmount != null) {
      return computedTaxAmount;
    }
    return getNetAmount().multiply(getTaxRate()).movePointLeft(2);
  }

  /**
   * Sets the exact tax (sum of the rate groups) so {@link #getTaxAmount} and {@link
   * #getGrossAmount} report it. Set by the renderer before variables such as {@code {{steuer}}} are
   * substituted; never stored.
   *
   * @param computedTaxAmount the tax from {@code VatCalculator}
   */
  public void setComputedTaxAmount(BigDecimal computedTaxAmount) {
    this.computedTaxAmount = computedTaxAmount;
  }

  public BigDecimal getGrossAmount() {
    return getNetAmount().add(getTaxAmount());
  }

  /**
   * The document number for display, PDF and file names. Drafts of invoice types have no number
   * until they are issued; they show {@value #DRAFT_LABEL} instead of an invented number, so a
   * draft PDF can never be mistaken for an issued invoice.
   *
   * @return the document number, or {@value #DRAFT_LABEL} if none has been assigned yet
   */
  public String getFormattedInvoiceNumber() {
    if (documentNumber != null && !documentNumber.isEmpty()) {
      return documentNumber;
    }
    return DRAFT_LABEL;
  }

  @Override
  public String toString() {
    return String.format(
        "Invoice %s: %.2f€ (gross, %s)",
        getFormattedInvoiceNumber(), getGrossAmount(), isPaid() ? "bezahlt" : "offen");
  }
}
