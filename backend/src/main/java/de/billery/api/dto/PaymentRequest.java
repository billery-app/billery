package de.billery.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body of {@code POST /api/invoices/{id}/payments}.
 *
 * @param paymentDate the date the money was received (required)
 * @param amount the amount in EUR, more than 0 and at most the open amount (required)
 * @param paymentMethod free text, e.g. "Überweisung"
 * @param reference free text, e.g. the bank statement reference
 * @param notes free text
 */
public record PaymentRequest(
    LocalDate paymentDate,
    BigDecimal amount,
    String paymentMethod,
    String reference,
    String notes) {}
