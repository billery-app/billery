package de.billery.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Filters of the paged document list ({@code GET /api/invoices/page}). They mirror what the SPA
 * used to apply in the browser ({@code InvoicesView.filteredInvoices} and {@code
 * useDocumentType.applyStatusFilter}), so moving them into SQL does not change which documents a
 * user sees.
 *
 * @param documentTypeIds document types to show; empty means all
 * @param archived {@code true} for the archive, {@code false} for the active documents, {@code
 *     null} for both (the dashboard: archived invoices still count as revenue)
 * @param year calendar year of the document date, or {@code null} for all years
 * @param from first document date (inclusive), or {@code null}
 * @param to last document date (inclusive), or {@code null}
 * @param search case-insensitive text matched against the document number and "first last" of the
 *     customer, or {@code null}/blank for none; {@code %} and {@code _} match literally
 * @param status one of {@link #STATUS_FILTERS}, or {@code null}/blank for all
 * @param today the reference date of the derived filters {@code overdue} and {@code expired}
 */
public record InvoiceListQuery(
    List<Integer> documentTypeIds,
    Boolean archived,
    Integer year,
    LocalDate from,
    LocalDate to,
    String search,
    String status,
    LocalDate today) {

  /**
   * The status filter keys: the ones the document list offers ({@code
   * useDocumentType.statusFilterOptions}), plus {@code open} (sent or partly paid) and {@code
   * issued} (sent, partly paid or paid) for the dashboard, matching the open / paid / counted split
   * of {@code InvoiceStatisticsService}.
   */
  public static final Set<String> STATUS_FILTERS =
      Set.of(
          "open",
          "issued",
          "draft",
          "sent",
          "partiallyPaid",
          "paid",
          "cancelled",
          "accepted",
          "rejected",
          "overdue",
          "expired");

  /** Validates the status key and the year (the year range must fit {@link LocalDate}). */
  public InvoiceListQuery {
    documentTypeIds = documentTypeIds == null ? List.of() : List.copyOf(documentTypeIds);
    if (status != null && !status.isBlank() && !STATUS_FILTERS.contains(status)) {
      throw new IllegalArgumentException("Unknown status filter: " + status);
    }
    if (year != null && (year < 1 || year > 9999)) {
      throw new IllegalArgumentException("Year out of range: " + year);
    }
    if (from != null && to != null && from.isAfter(to)) {
      throw new IllegalArgumentException("from is after to");
    }
  }

  /** {@code WHERE} clause over {@code invoice i LEFT JOIN customer c} plus its parameters. */
  record Where(String sql, Map<String, Object> params) {}

  Where toWhere() {
    List<String> conditions = new ArrayList<>();
    Map<String, Object> params = new HashMap<>();
    if (archived != null) {
      conditions.add(archived ? "COALESCE(i.archived, 0) = 1" : "COALESCE(i.archived, 0) = 0");
    }
    if (!documentTypeIds.isEmpty()) {
      conditions.add("i.document_type_id IN (:types)");
      params.put("types", documentTypeIds);
    }
    if (year != null) {
      conditions.add("i.invoice_date >= :yearStart AND i.invoice_date < :yearEnd");
      params.put("yearStart", LocalDate.of(year, 1, 1));
      params.put("yearEnd", LocalDate.of(year + 1, 1, 1));
    }
    if (from != null) {
      conditions.add("i.invoice_date >= :from");
      params.put("from", from);
    }
    if (to != null) {
      conditions.add("i.invoice_date <= :to");
      params.put("to", to);
    }
    if (search != null && !search.isBlank()) {
      conditions.add(
          "(LOWER(COALESCE(i.document_number, '')) LIKE :search ESCAPE '!'"
              + " OR LOWER(CONCAT(COALESCE(c.first_name, ''), ' ', COALESCE(c.last_name, '')))"
              + " LIKE :search ESCAPE '!')");
      params.put("search", BaseRepository.containsPattern(search));
    }
    String statusCondition = statusCondition();
    if (statusCondition != null) {
      conditions.add(statusCondition);
      if (statusCondition.contains(":today")) {
        params.put("today", today);
      }
    }
    return new Where(
        conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
  }

  /**
   * The status filter as SQL. {@code overdue} and {@code expired} are derived exactly like {@code
   * isInvoiceOverdue} / {@code isOfferExpired} in {@code frontend/src/utils/documentStatus.js}.
   */
  private String statusCondition() {
    if (status == null || status.isBlank()) {
      return null;
    }
    return switch (status) {
      case "open" -> "i.document_status_id IN (2, 9)";
      case "issued" -> "i.document_status_id IN (2, 6, 9)";
      case "draft" -> "i.document_status_id = 1";
      case "sent" -> "i.document_status_id = 2";
      case "accepted" -> "i.document_status_id = 3";
      case "rejected" -> "i.document_status_id = 4";
      case "paid" -> "i.document_status_id = 6";
      case "cancelled" -> "i.document_status_id = 8";
      case "partiallyPaid" -> "i.document_status_id = 9";
      case "overdue" ->
          "i.paid_date IS NULL AND i.due_date IS NOT NULL AND i.due_date < :today"
              + " AND i.document_status_id NOT IN (1, 5, 6, 8)";
      case "expired" ->
          "i.offer_valid_until IS NOT NULL AND i.offer_valid_until < :today"
              + " AND i.document_status_id NOT IN (3, 4, 5, 8)";
      default -> throw new IllegalStateException(status);
    };
  }
}
