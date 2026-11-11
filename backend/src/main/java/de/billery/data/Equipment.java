package de.billery.data;

import java.math.BigDecimal;

public class Equipment {

  private int id;
  private String name;
  private BigDecimal purchaseValue;
  private String acquisitionDate;
  private int expectedLifetimeMonths;
  private BigDecimal monthlyOperatingCost;
  private BigDecimal residualValue;
  private String status;
  private String notes;

  public Equipment() {
    this.name = "";
    this.status = "active";
  }

  public Equipment(
      int id,
      String name,
      BigDecimal purchaseValue,
      String acquisitionDate,
      int expectedLifetimeMonths,
      BigDecimal monthlyOperatingCost,
      BigDecimal residualValue,
      String status,
      String notes) {
    this.id = id;
    this.name = name != null ? name : "";
    this.purchaseValue = purchaseValue;
    this.acquisitionDate = acquisitionDate;
    this.expectedLifetimeMonths = expectedLifetimeMonths;
    this.monthlyOperatingCost = monthlyOperatingCost;
    this.residualValue = residualValue;
    this.status = status != null ? status : "active";
    this.notes = notes;
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
    this.name = name != null ? name : "";
  }

  public BigDecimal getPurchaseValue() {
    return purchaseValue;
  }

  public void setPurchaseValue(BigDecimal purchaseValue) {
    this.purchaseValue = purchaseValue;
  }

  public String getAcquisitionDate() {
    return acquisitionDate;
  }

  public void setAcquisitionDate(String acquisitionDate) {
    this.acquisitionDate = acquisitionDate;
  }

  public int getExpectedLifetimeMonths() {
    return expectedLifetimeMonths;
  }

  public void setExpectedLifetimeMonths(int expectedLifetimeMonths) {
    this.expectedLifetimeMonths = expectedLifetimeMonths;
  }

  public BigDecimal getMonthlyOperatingCost() {
    return monthlyOperatingCost;
  }

  public void setMonthlyOperatingCost(BigDecimal monthlyOperatingCost) {
    this.monthlyOperatingCost = monthlyOperatingCost;
  }

  public BigDecimal getResidualValue() {
    return residualValue;
  }

  public void setResidualValue(BigDecimal residualValue) {
    this.residualValue = residualValue;
  }

  public String getStatus() {
    return status;
  }

  public void setStatus(String status) {
    this.status = status != null ? status : "active";
  }

  public String getNotes() {
    return notes;
  }

  public void setNotes(String notes) {
    this.notes = notes;
  }
}
