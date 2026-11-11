package de.billery.statistics;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Data model for comprehensive invoice statistics over a specific time period. Contains all
 * relevant metrics for financial analysis and reporting.
 *
 * @author Invoice Management System
 * @version 1.0
 */
public class InvoiceStatistics {

  /** Start date of the statistics period */
  private LocalDate periodStart;

  /** End date of the statistics period */
  private LocalDate periodEnd;

  /** Total number of invoices in the period */
  private int totalInvoiceCount;

  /** Number of paid invoices */
  private int paidInvoiceCount;

  /** Number of unpaid invoices */
  private int unpaidInvoiceCount;

  /** Total revenue from all invoices */
  private BigDecimal totalRevenue = BigDecimal.ZERO;

  /** Revenue from paid invoices only */
  private BigDecimal paidRevenue = BigDecimal.ZERO;

  /** Revenue from unpaid invoices only */
  private BigDecimal unpaidRevenue = BigDecimal.ZERO;

  /** Average value per invoice */
  private BigDecimal averageInvoiceValue = BigDecimal.ZERO;

  /** Average value of paid invoices */
  private BigDecimal averagePaidValue = BigDecimal.ZERO;

  /** Average value of unpaid invoices */
  private BigDecimal averageUnpaidValue = BigDecimal.ZERO;

  /** Total tax amount from all invoices */
  private BigDecimal totalTaxAmount = BigDecimal.ZERO;

  /** Tax amount from paid invoices */
  private BigDecimal paidTaxAmount = BigDecimal.ZERO;

  /** Tax amount from unpaid invoices */
  private BigDecimal unpaidTaxAmount = BigDecimal.ZERO;

  private BigDecimal totalGain = BigDecimal.ZERO;

  private BigDecimal totalPurchaseValue = BigDecimal.ZERO;

  private BigDecimal totalSells = BigDecimal.ZERO;

  /**
   * Gross (net + VAT, per {@code VatCalculator}) of all counted documents, credit notes negative.
   */
  private BigDecimal totalGross = BigDecimal.ZERO;

  /** Gross of the issued invoices: {@code paidGross + unpaidGross}, no credit notes netted. */
  private BigDecimal issuedGross = BigDecimal.ZERO;

  /** VAT of the issued invoices: {@code paidTaxAmount + unpaidTaxAmount}. */
  private BigDecimal issuedTaxAmount = BigDecimal.ZERO;

  /** Gross of the paid invoices. */
  private BigDecimal paidGross = BigDecimal.ZERO;

  /** Gross of the open (sent or partly paid) invoices. */
  private BigDecimal unpaidGross = BigDecimal.ZERO;

  /**
   * Gross of all counted documents ({@link #getTotalRevenue()} is net).
   *
   * @return the gross amount
   */
  public BigDecimal getTotalGross() {
    return totalGross;
  }

  /**
   * Sets the gross of all counted documents.
   *
   * @param totalGross the gross amount
   */
  public void setTotalGross(BigDecimal totalGross) {
    this.totalGross = totalGross;
  }

  /**
   * Gross of the paid invoices.
   *
   * @return the gross amount
   */
  public BigDecimal getPaidGross() {
    return paidGross;
  }

  /**
   * Sets the gross of the paid invoices.
   *
   * @param paidGross the gross amount
   */
  public void setPaidGross(BigDecimal paidGross) {
    this.paidGross = paidGross;
  }

  /**
   * Gross of the open (sent or partly paid) invoices: their full amount, not the remainder.
   *
   * @return the gross amount
   */
  public BigDecimal getUnpaidGross() {
    return unpaidGross;
  }

  /**
   * Sets the gross of the open invoices.
   *
   * @param unpaidGross the gross amount
   */
  public void setUnpaidGross(BigDecimal unpaidGross) {
    this.unpaidGross = unpaidGross;
  }

  /**
   * Gross of the issued invoices (paid + open), what the dashboard shows as revenue.
   *
   * @return the gross amount
   */
  public BigDecimal getIssuedGross() {
    return issuedGross;
  }

  /**
   * Sets the gross of the issued invoices.
   *
   * @param issuedGross the gross amount
   */
  public void setIssuedGross(BigDecimal issuedGross) {
    this.issuedGross = issuedGross;
  }

  /**
   * VAT of the issued invoices (paid + open), what the dashboard shows as tax.
   *
   * @return the tax amount
   */
  public BigDecimal getIssuedTaxAmount() {
    return issuedTaxAmount;
  }

  /**
   * Sets the VAT of the issued invoices.
   *
   * @param issuedTaxAmount the tax amount
   */
  public void setIssuedTaxAmount(BigDecimal issuedTaxAmount) {
    this.issuedTaxAmount = issuedTaxAmount;
  }

  // Getters and Setters
  public LocalDate getPeriodStart() {
    return periodStart;
  }

  public void setPeriodStart(LocalDate periodStart) {
    this.periodStart = periodStart;
  }

  public LocalDate getPeriodEnd() {
    return periodEnd;
  }

  public void setPeriodEnd(LocalDate periodEnd) {
    this.periodEnd = periodEnd;
  }

  public int getTotalInvoiceCount() {
    return totalInvoiceCount;
  }

  public void setTotalInvoiceCount(int totalInvoiceCount) {
    this.totalInvoiceCount = totalInvoiceCount;
  }

  public int getPaidInvoiceCount() {
    return paidInvoiceCount;
  }

  public void setPaidInvoiceCount(int paidInvoiceCount) {
    this.paidInvoiceCount = paidInvoiceCount;
  }

  public int getUnpaidInvoiceCount() {
    return unpaidInvoiceCount;
  }

  public void setUnpaidInvoiceCount(int unpaidInvoiceCount) {
    this.unpaidInvoiceCount = unpaidInvoiceCount;
  }

  public BigDecimal getTotalRevenue() {
    return totalRevenue;
  }

  public void setTotalRevenue(BigDecimal totalRevenue) {
    this.totalRevenue = totalRevenue;
  }

  public BigDecimal getPaidRevenue() {
    return paidRevenue;
  }

  public void setPaidRevenue(BigDecimal paidRevenue) {
    this.paidRevenue = paidRevenue;
  }

  public BigDecimal getUnpaidRevenue() {
    return unpaidRevenue;
  }

  public void setUnpaidRevenue(BigDecimal unpaidRevenue) {
    this.unpaidRevenue = unpaidRevenue;
  }

  public BigDecimal getAverageInvoiceValue() {
    return averageInvoiceValue;
  }

  public void setAverageInvoiceValue(BigDecimal averageInvoiceValue) {
    this.averageInvoiceValue = averageInvoiceValue;
  }

  public BigDecimal getAveragePaidValue() {
    return averagePaidValue;
  }

  public void setAveragePaidValue(BigDecimal averagePaidValue) {
    this.averagePaidValue = averagePaidValue;
  }

  public BigDecimal getAverageUnpaidValue() {
    return averageUnpaidValue;
  }

  public void setAverageUnpaidValue(BigDecimal averageUnpaidValue) {
    this.averageUnpaidValue = averageUnpaidValue;
  }

  public BigDecimal getTotalTaxAmount() {
    return totalTaxAmount;
  }

  public void setTotalTaxAmount(BigDecimal totalTaxAmount) {
    this.totalTaxAmount = totalTaxAmount;
  }

  public BigDecimal getPaidTaxAmount() {
    return paidTaxAmount;
  }

  public void setPaidTaxAmount(BigDecimal paidTaxAmount) {
    this.paidTaxAmount = paidTaxAmount;
  }

  public BigDecimal getUnpaidTaxAmount() {
    return unpaidTaxAmount;
  }

  public void setUnpaidTaxAmount(BigDecimal unpaidTaxAmount) {
    this.unpaidTaxAmount = unpaidTaxAmount;
  }

  public void setTotalGain(BigDecimal totalGain) {
    this.totalGain = totalGain;
  }

  public BigDecimal getTotalGain() {
    return totalGain;
  }

  public void setTotalPurchaseValue(BigDecimal totalPurchaseValue) {
    this.totalPurchaseValue = totalPurchaseValue;
  }

  public BigDecimal getTotalPurchaseValue() {
    return totalPurchaseValue;
  }

  public void setTotalSells(BigDecimal totalSells) {
    this.totalSells = totalSells;
  }

  public BigDecimal getTotalSells() {
    return totalSells;
  }
}
