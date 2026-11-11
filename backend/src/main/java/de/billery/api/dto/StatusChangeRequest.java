package de.billery.api.dto;

import java.time.LocalDate;

/**
 * Body of {@code PATCH /api/invoices/{id}/status}.
 *
 * @param documentStatusId the requested status
 * @param paymentDate required when an invoice is set to PAID: the date the money was received
 * @param convertedToInvoiceId optional when an offer or cost calculation is set to CONVERTED: the
 *     document it was converted into
 */
public record StatusChangeRequest(
    Integer documentStatusId, LocalDate paymentDate, Integer convertedToInvoiceId) {}
