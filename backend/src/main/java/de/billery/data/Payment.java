package de.billery.data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A payment received for an issued invoice (a row of the {@code payment} table).
 *
 * <p>Payments are append-only: they are never updated or deleted. A mistaken entry is corrected by
 * a reversal row with the negated amount whose {@code reversesPaymentId} points at the original, so
 * the record of what was entered stays complete (GoBD).
 *
 * @param id the payment id (0 before it is saved)
 * @param invoiceId the invoice the payment belongs to
 * @param paymentDate the date the money was received (not the date it was entered)
 * @param amount the amount in EUR; negative only on a reversal row
 * @param paymentMethod free text, e.g. "Überweisung"
 * @param reference free text, e.g. the bank statement reference
 * @param notes free text
 * @param reversesPaymentId the payment this row reverses, or {@code null}
 */
public record Payment(
    int id,
    int invoiceId,
    LocalDate paymentDate,
    BigDecimal amount,
    String paymentMethod,
    String reference,
    String notes,
    Integer reversesPaymentId) {}
