package de.billery.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record SearchResult(
    List<SearchCustomer> customers,
    List<SearchArticle> articles,
    List<SearchService> services,
    List<SearchInvoice> invoices,
    List<SearchInvoice> offers,
    List<SearchInvoice> calculations) {

  public record SearchCustomer(
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
      String note) {}

  public record SearchArticle(
      int id,
      String name,
      BigDecimal purchaseValue,
      BigDecimal salesValue,
      String unit,
      String description) {}

  public record SearchService(
      int id, String name, String description, BigDecimal price, String unit, int articleId) {}

  public record SearchInvoice(
      int id,
      int customerId,
      String customerName,
      String documentNumber,
      String invoiceDate,
      String dueDate,
      BigDecimal totalPrice,
      BigDecimal taxRate,
      String paidDate,
      String notes,
      int documentTypeId,
      String documentTypeName,
      int documentStatusId,
      String documentStatusName) {}
}
