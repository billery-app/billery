package de.billery.mail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Counts the mails the application sends: {@code billery_mails_total{kind, outcome, reason}}.
 * {@code kind} is {@code system} (the operator's account: invitations) or {@code company} (a
 * company's own account: documents to customers, test mails), {@code outcome} is {@code sent} or
 * {@code failed}, {@code reason} the {@link MailException.Reason} of a failure ({@code none} for a
 * sent mail). No addresses, no company: the alert rules in {@code deploy/saas/monitoring/} watch
 * the failure rate per kind, so failed deliveries are noticed without reading logs.
 */
@Component
public class MailMetrics {

  /** The operator's account. */
  public static final String SYSTEM = "system";

  /** A company's own account. */
  public static final String COMPANY = "company";

  private final MeterRegistry meters;

  /**
   * Registers every counter with 0, so the monitoring sees the first failure as an increase.
   *
   * @param meters the registry
   */
  public MailMetrics(MeterRegistry meters) {
    this.meters = meters;
    for (String kind : new String[] {SYSTEM, COMPANY}) {
      counter(kind, "sent", "none");
      for (MailException.Reason reason : MailException.Reason.values()) {
        counter(kind, "failed", reason.label());
      }
    }
    unrecorded();
  }

  /**
   * A mail to a customer went out, but its entry in the document's history could not be written
   * ({@code billery_mail_dispatch_unrecorded_total}, alert {@code BilleryMailDispatchUnrecorded}).
   */
  public void dispatchUnrecorded() {
    unrecorded().increment();
  }

  private Counter unrecorded() {
    return Counter.builder("billery.mail.dispatch.unrecorded")
        .description("Mails to customers that went out but are missing in the document history")
        .register(meters);
  }

  /**
   * @param kind {@link #SYSTEM} or {@link #COMPANY}
   */
  public void sent(String kind) {
    counter(kind, "sent", "none").increment();
  }

  /**
   * @param kind {@link #SYSTEM} or {@link #COMPANY}
   * @param reason why it failed
   */
  public void failed(String kind, MailException.Reason reason) {
    counter(kind, "failed", reason.label()).increment();
  }

  private Counter counter(String kind, String outcome, String reason) {
    return Counter.builder("billery.mails")
        .description("Mails handed to an SMTP server, by account kind, outcome and failure reason")
        .tag("kind", kind)
        .tag("outcome", outcome)
        .tag("reason", reason)
        .register(meters);
  }
}
