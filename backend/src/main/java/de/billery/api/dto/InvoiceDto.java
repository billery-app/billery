package de.billery.api.dto;

import de.billery.tax.VatBreakdown;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A document as exchanged with the SPA. Amounts are computed by the server; on requests only the
 * editable draft fields are read.
 *
 * @param paidAmount sum of recorded payments (response only; 0 for drafts and non-invoices)
 * @param cancelsInvoiceId on a credit note: the invoice it cancels (response only)
 * @param cancelledByInvoiceId on a cancelled invoice: its credit note (response only)
 * @param taxTreatment STANDARD, SMALL_BUSINESS, REVERSE_CHARGE or TAX_EXEMPT ({@code TaxTreatment})
 * @param taxExemptionReason legal basis printed for TAX_EXEMPT
 * @param vatGroups net and tax per VAT rate (response only; {@code taxAmount} is their sum)
 */
public record InvoiceDto(
    Integer id,
    Integer customerId,
    String customerName,
    String invoiceNumber,
    Integer documentStatusId,
    LocalDate invoiceDate,
    LocalDate dueDate,
    BigDecimal netAmount,
    BigDecimal taxRate,
    BigDecimal taxAmount,
    BigDecimal grossAmount,
    LocalDate paidDate,
    String notes,
    Integer documentTypeId,
    List<InvoiceItemDto> items,
    Boolean archived,
    LocalDate workStartDate,
    LocalDate workEndDate,
    Integer templateId,
    BigDecimal paidAmount,
    Integer cancelsInvoiceId,
    Integer cancelledByInvoiceId,
    String taxTreatment,
    String taxExemptionReason,
    List<VatBreakdown.Group> vatGroups) {}
