package de.billery.data;

/**
 * One entry of a document's audit trail (a row of the {@code document_history} table).
 *
 * @param id the entry id
 * @param invoiceId the document the entry belongs to
 * @param action what happened, one of the {@code ACTION_*} constants of {@code
 *     DocumentHistoryRepository}
 * @param actionDate when it happened (database timestamp, as text)
 * @param notes human-readable details, e.g. the assigned number or a payment amount
 * @param performedBy the stored actor id (SaaS: the user's id; self-host: null)
 * @param performedByName the actor's display name or address; null when unknown or self-host
 */
public record DocumentHistoryEntry(
    int id,
    int invoiceId,
    String action,
    String actionDate,
    String notes,
    String performedBy,
    String performedByName) {

  /**
   * This entry with the actor's name filled in.
   *
   * @param name the display name or address
   * @return a copy
   */
  public DocumentHistoryEntry withPerformedByName(String name) {
    return new DocumentHistoryEntry(id, invoiceId, action, actionDate, notes, performedBy, name);
  }
}
