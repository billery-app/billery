package de.billery.data;

/**
 * Represents an invoice template entity for document generation. Templates contain HTML/CSS content
 * for rendering different document types (invoices, offers, cost calculations, etc.).
 */
public class InvoiceTemplate {

  private int id;
  private String name;
  private int documentTypeId;
  private String htmlContent;
  private String cssContent;
  private String headerHtml;
  private String footerHtml;
  private int defaultFlag;
  private int marginTopMm;
  private int marginBottomMm;
  private int marginLeftMm;
  private int marginRightMm;
  private String createdAt;
  private String updatedAt;
  private String blocks;
  private boolean documentSpecific;

  public InvoiceTemplate() {
    this.id = 0;
    this.name = "";
    this.documentTypeId = 1;
    this.htmlContent = "";
    this.cssContent = "";
    this.headerHtml = "";
    this.footerHtml = "";
    this.defaultFlag = 0;
    this.marginTopMm = 10;
    this.marginBottomMm = 10;
    this.marginLeftMm = 10;
    this.marginRightMm = 10;
    this.createdAt = "";
    this.updatedAt = "";
    this.documentSpecific = false;
  }

  public InvoiceTemplate(
      int id,
      String name,
      int documentTypeId,
      String htmlContent,
      String cssContent,
      String headerHtml,
      String footerHtml,
      int isDefault,
      int marginTopMm,
      int marginBottomMm,
      int marginLeftMm,
      int marginRightMm,
      String createdAt,
      String updatedAt,
      String blocks) {
    this.id = id;
    this.name = name;
    this.documentTypeId = documentTypeId;
    this.htmlContent = htmlContent != null ? htmlContent : "";
    this.cssContent = cssContent != null ? cssContent : "";
    this.headerHtml = headerHtml != null ? headerHtml : "";
    this.footerHtml = footerHtml != null ? footerHtml : "";
    this.defaultFlag = isDefault;
    this.marginTopMm = marginTopMm;
    this.marginBottomMm = marginBottomMm;
    this.marginLeftMm = marginLeftMm;
    this.marginRightMm = marginRightMm;
    this.createdAt = createdAt != null ? createdAt : "";
    this.updatedAt = updatedAt != null ? updatedAt : "";
    this.blocks = blocks != null ? blocks : "";
  }

  // Getters and Setters
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
    this.name = name != null ? name : "";
  }

  public int getDocumentTypeId() {
    return documentTypeId;
  }

  public void setDocumentTypeId(int documentTypeId) {
    this.documentTypeId = documentTypeId;
  }

  public String getHtmlContent() {
    return htmlContent;
  }

  public void setHtmlContent(String htmlContent) {
    this.htmlContent = htmlContent != null ? htmlContent : "";
  }

  public String getCssContent() {
    return cssContent;
  }

  public void setCssContent(String cssContent) {
    this.cssContent = cssContent != null ? cssContent : "";
  }

  public String getHeaderHtml() {
    return headerHtml;
  }

  public void setHeaderHtml(String headerHtml) {
    this.headerHtml = headerHtml != null ? headerHtml : "";
  }

  public String getFooterHtml() {
    return footerHtml;
  }

  public void setFooterHtml(String footerHtml) {
    this.footerHtml = footerHtml != null ? footerHtml : "";
  }

  public int getIsDefault() {
    return defaultFlag;
  }

  public void setIsDefault(int isDefault) {
    this.defaultFlag = isDefault;
  }

  public int getMarginTopMm() {
    return marginTopMm;
  }

  public void setMarginTopMm(int marginTopMm) {
    this.marginTopMm = marginTopMm;
  }

  public int getMarginBottomMm() {
    return marginBottomMm;
  }

  public void setMarginBottomMm(int marginBottomMm) {
    this.marginBottomMm = marginBottomMm;
  }

  public int getMarginLeftMm() {
    return marginLeftMm;
  }

  public void setMarginLeftMm(int marginLeftMm) {
    this.marginLeftMm = marginLeftMm;
  }

  public int getMarginRightMm() {
    return marginRightMm;
  }

  public void setMarginRightMm(int marginRightMm) {
    this.marginRightMm = marginRightMm;
  }

  public String getCreatedAt() {
    return createdAt;
  }

  public void setCreatedAt(String createdAt) {
    this.createdAt = createdAt != null ? createdAt : "";
  }

  public String getUpdatedAt() {
    return updatedAt;
  }

  public void setUpdatedAt(String updatedAt) {
    this.updatedAt = updatedAt != null ? updatedAt : "";
  }

  public boolean isDefault() {
    return defaultFlag == 1;
  }

  public void setDefault(boolean isDefault) {
    this.defaultFlag = isDefault ? 1 : 0;
  }

  public String getBlocks() {
    return blocks;
  }

  public void setBlocks(String blocks) {
    this.blocks = blocks;
  }

  public boolean isDocumentSpecific() {
    return documentSpecific;
  }

  public void setDocumentSpecific(boolean documentSpecific) {
    this.documentSpecific = documentSpecific;
  }
}
