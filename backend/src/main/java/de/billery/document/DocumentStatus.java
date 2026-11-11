package de.billery.document;

/**
 * Ids of the {@code document_status} lookup table. Id 7 is unused: it was the removed {@code
 * OVERDUE} status (overdue is derived from the due date).
 */
public final class DocumentStatus {

  /** Being prepared; editable and deletable. Invoice types have no number yet. */
  public static final int DRAFT = 1;

  /**
   * Invoice types: issued ("gesendet"), number assigned, content frozen. Offers and cost
   * calculations: sent to the customer and waiting for the answer (set by hand, there is no mail
   * dispatch yet).
   */
  public static final int SENT = 2;

  /** Offer / cost calculation accepted by the customer. */
  public static final int ACCEPTED = 3;

  /** Offer / cost calculation rejected by the customer. */
  public static final int REJECTED = 4;

  /** Offer / cost calculation converted into the next document. */
  public static final int CONVERTED = 5;

  /** Fully paid; derived from the recorded payments. */
  public static final int PAID = 6;

  /** Cancelled; for invoice types only by a credit note (Stornorechnung). */
  public static final int CANCELLED = 8;

  /** Partly paid; derived from the recorded payments. */
  public static final int PARTIALLY_PAID = 9;

  private DocumentStatus() {}

  /**
   * German name of a status as the SPA shows it, for messages to the user.
   *
   * @param statusId a {@code document_status.id}
   * @return the name, or the id as text for an unknown status
   */
  public static String germanName(int statusId) {
    return switch (statusId) {
      case DRAFT -> "Entwurf";
      case SENT -> "Gesendet";
      case ACCEPTED -> "Angenommen";
      case REJECTED -> "Abgelehnt";
      case CONVERTED -> "Umgewandelt";
      case PAID -> "Bezahlt";
      case CANCELLED -> "Storniert";
      case PARTIALLY_PAID -> "Teilweise bezahlt";
      default -> String.valueOf(statusId);
    };
  }

  /**
   * The history note of a status change, readable without looking anything up.
   *
   * @param fromStatusId the status before
   * @param toStatusId the status after
   * @return for example {@code Entwurf → Gesendet}
   */
  public static String transitionNote(int fromStatusId, int toStatusId) {
    return germanName(fromStatusId) + " → " + germanName(toStatusId);
  }
}
