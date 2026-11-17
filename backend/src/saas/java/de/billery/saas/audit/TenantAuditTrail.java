package de.billery.saas.audit;

import de.billery.audit.AuditTrail;
import de.billery.saas.provisioning.TenantAuditLog;
import java.time.LocalDate;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * SaaS: a sent GoBD export goes into the administration log of the active company ({@code
 * tenant_audit}, action {@link TenantAuditLog#GOBD_EXPORTED}), with the acting user from the
 * connection. Its owners and administrators see it next to the team actions; the operator uses it
 * to tell which data an account took out ({@code docs/operations/saas-incident-response.md}).
 */
@Component
@Profile("saas")
public class TenantAuditTrail implements AuditTrail {

  private final TenantAuditLog log;

  /**
   * Creates the trail.
   *
   * @param log the administration log of the active company
   */
  public TenantAuditTrail(TenantAuditLog log) {
    this.log = log;
  }

  @Override
  public void gobdExport(
      LocalDate from, LocalDate to, boolean withFiles, int documents, String problem) {
    log.record(
        TenantAuditLog.GOBD_EXPORTED,
        from
            + " bis "
            + to
            + ", "
            + documents
            + " Belege"
            + (withFiles ? " mit PDF und XRechnung" : " ohne Belegdateien")
            + (problem == null ? ", vollständig" : ", abgebrochen: " + problem));
  }
}
