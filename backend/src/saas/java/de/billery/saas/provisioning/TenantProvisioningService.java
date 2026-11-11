package de.billery.saas.provisioning;

import de.billery.data.InvoiceTemplate;
import de.billery.repository.InvoiceTemplateRepository;
import de.billery.saas.api.ApiError;
import de.billery.saas.identity.Role;
import de.billery.saas.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates and closes tenants. A new tenant is usable at once: the signed-in user becomes its owner
 * and it gets its own copy of the default templates; number series and prefixes need no rows (the
 * repositories create and default them on first use), the company data are entered by the owner in
 * the app.
 */
@Service
@Profile("saas")
public class TenantProvisioningService {

  /** The tenant that owns the seed data of the core migrations (the default templates). */
  static final int SEED_TENANT_ID = 1;

  /** The quota's name in {@code daily_quota}. */
  static final String SIGNUP_QUOTA = "signup";

  private static final Logger LOG = LoggerFactory.getLogger(TenantProvisioningService.class);

  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final InvoiceTemplateRepository templates;
  private final TenantAuditLog audit;
  private final DailyQuota quota;
  private final SignUpLimits limits;

  /**
   * The limits of sign-up; a value of 0 switches a limit off.
   *
   * @param maxCompaniesPerUser how many companies (not closed) one user may own ({@code
   *     BILLERY_MAX_COMPANIES_PER_USER})
   * @param perUserPerDay how many companies one user may create in 24 hours ({@code
   *     BILLERY_SIGNUPS_PER_USER_PER_DAY})
   * @param perSourcePerDay how many companies may be created from one source IP in 24 hours ({@code
   *     BILLERY_SIGNUPS_PER_IP})
   * @param perDay how many companies the whole system may create in 24 hours ({@code
   *     BILLERY_SIGNUPS_PER_DAY}); the emergency brake, logged as an error
   */
  public record SignUpLimits(
      int maxCompaniesPerUser, int perUserPerDay, int perSourcePerDay, int perDay) {

    /** Refuses negative values; the start fails then. */
    public SignUpLimits {
      if (maxCompaniesPerUser < 0 || perUserPerDay < 0 || perSourcePerDay < 0 || perDay < 0) {
        throw new IllegalStateException(
            "The sign-up limits (BILLERY_SIGNUPS_*) must not be negative");
      }
    }
  }

  /**
   * @param jdbc the tenant-aware database access
   * @param transactions runs the sign-up as one
   * @param templates copies the default templates into the new tenant
   * @param audit the administration log
   * @param quota counts the sign-ups per user, source and day
   * @param maxCompaniesPerUser see {@link SignUpLimits}
   * @param perUserPerDay see {@link SignUpLimits}
   * @param perSourcePerDay see {@link SignUpLimits}
   * @param perDay see {@link SignUpLimits}
   */
  public TenantProvisioningService(
      JdbcClient jdbc,
      TransactionTemplate transactions,
      InvoiceTemplateRepository templates,
      TenantAuditLog audit,
      DailyQuota quota,
      @Value("${billery.max-companies-per-user:5}") int maxCompaniesPerUser,
      @Value("${billery.signups-per-user-per-day:3}") int perUserPerDay,
      @Value("${billery.signups-per-ip:10}") int perSourcePerDay,
      @Value("${billery.signups-per-day:100}") int perDay) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.templates = templates;
    this.audit = audit;
    this.quota = quota;
    this.limits = new SignUpLimits(maxCompaniesPerUser, perUserPerDay, perSourcePerDay, perDay);
    quota.declare(SIGNUP_QUOTA, "user", "ip", DailyQuota.SYSTEM);
  }

  /**
   * Creates a tenant for the acting user (who must be signed in; no tenant needs to be active).
   *
   * <p>Sign-up is open to anyone who signs in, so it is limited ({@link SignUpLimits}): companies
   * owned per user, and companies created per user, per source IP and for the whole system in 24
   * hours. The limits are checked and counted in the transaction that creates the tenant: a refused
   * or failed sign-up counts nothing.
   *
   * @param name the company's name
   * @param sourceIp the address the request came from
   * @return the id of the new tenant
   * @throws ApiError 400 if the name is empty or longer than 255 characters, 409 if the user owns
   *     the most companies allowed, 429 if a sign-up limit of the day is reached
   */
  public int createTenant(String name, String sourceIp) {
    String clean = name == null ? "" : name.trim();
    if (clean.isEmpty() || clean.length() > 255) {
      throw new ApiError(HttpStatus.BAD_REQUEST, "The name must have 1 to 255 characters");
    }
    UUID user = Permissions.user();
    List<InvoiceTemplate> defaults = seedTemplates();
    return transactions.execute(
        status -> {
          requireSignUpAllowed(user, sourceIp);
          // Take the id first and act as the new tenant: rows are only visible and writable
          // for the active tenant, and the tenant row has to be visible to be returned.
          long id =
              jdbc.sql("SELECT nextval(pg_get_serial_sequence('tenant', 'id'))")
                  .query(Long.class)
                  .single();
          actAs(id);
          jdbc.sql("INSERT INTO tenant (id, name) VALUES (:id, :name)")
              .param("id", id)
              .param("name", clean)
              .update();
          jdbc.sql(
                  "INSERT INTO tenant_member (tenant_id, user_id, role)"
                      + " VALUES (:tenant, :user, :role)")
              .param("tenant", id)
              .param("user", user)
              .param("role", Role.OWNER.name())
              .update();
          for (InvoiceTemplate seed : defaults) {
            seed.setId(0);
            templates.save(seed);
          }
          audit.record(TenantAuditLog.TENANT_CREATED, clean);
          return (int) id;
        });
  }

  /**
   * Refuses a sign-up beyond the limits, otherwise counts it (in the caller's transaction).
   *
   * @throws ApiError 409 or 429 with a message for the user
   */
  private void requireSignUpAllowed(UUID user, String sourceIp) {
    if (limits.maxCompaniesPerUser() > 0) {
      // Runs as the user: tenant_member shows a user their own memberships, tenant their tenants.
      int owned =
          jdbc.sql(
                  "SELECT COUNT(*) FROM tenant_member m JOIN tenant t ON t.id = m.tenant_id"
                      + " WHERE m.user_id = :user AND m.role = :role AND t.status <> 'CLOSED'")
              .param("user", user)
              .param("role", Role.OWNER.name())
              .query(Integer.class)
              .single();
      if (owned >= limits.maxCompaniesPerUser()) {
        throw new ApiError(
            HttpStatus.CONFLICT,
            "Sie besitzen bereits "
                + owned
                + " Firmen, mehr sind nicht möglich. Schließen Sie eine Firma oder wenden Sie sich"
                + " an den Betreiber.");
      }
    }
    quota
        .claim(
            SIGNUP_QUOTA,
            List.of(
                new DailyQuota.Limit("user", user.toString(), limits.perUserPerDay()),
                new DailyQuota.Limit("ip", DailyQuota.hash(sourceIp), limits.perSourcePerDay()),
                new DailyQuota.Limit(DailyQuota.SYSTEM, DailyQuota.SYSTEM_KEY, limits.perDay())))
        .ifPresent(
            scope -> {
              throw new ApiError(HttpStatus.TOO_MANY_REQUESTS, signUpRefusal(scope));
            });
  }

  private static String signUpRefusal(String scope) {
    return switch (scope) {
      case "user" ->
          "Sie haben heute schon mehrere Firmen angelegt. Bitte versuchen Sie es morgen wieder.";
      case "ip" ->
          "Von diesem Anschluss wurden heute schon zu viele Firmen angelegt. Bitte versuchen Sie es"
              + " später wieder.";
      default -> {
        LOG.error(
            "The system limit of sign-ups is reached: no new companies for up to 24 hours. Check"
                + " for abuse, or raise BILLERY_SIGNUPS_PER_DAY.");
        yield "Heute können keine neuen Firmen mehr angelegt werden. Bitte versuchen Sie es morgen"
            + " wieder.";
      }
    };
  }

  /**
   * Closes the active tenant: it keeps all its data but becomes read-only, so it can be exported.
   * Only an owner may do this; reopening is an operator action.
   *
   * @throws ApiError 403 for anyone but an owner, 409 if the tenant is not active
   */
  public void closeTenant() {
    Permissions.require(Role.OWNER);
    transactions.executeWithoutResult(
        status -> {
          int updated =
              jdbc.sql(
                      "UPDATE tenant SET status = 'CLOSED', closed_at = CURRENT_TIMESTAMP"
                          + " WHERE id = :tenant AND status = 'ACTIVE'")
                  .param("tenant", Permissions.tenant())
                  .update();
          if (updated == 0) {
            throw new ApiError(HttpStatus.CONFLICT, "The tenant is not active");
          }
          audit.record(TenantAuditLog.TENANT_CLOSED, null);
        });
  }

  /** Makes {@code tenantId} the active tenant of the current transaction only. */
  private void actAs(long tenantId) {
    jdbc.sql("SELECT set_config('app.tenant_id', :id, true)")
        .param("id", Long.toString(tenantId))
        .query(String.class)
        .single();
  }

  /**
   * The default templates, read as the seed tenant (before the transaction: another connection).
   */
  private List<InvoiceTemplate> seedTemplates() {
    try {
      return TenantContext.runAs(SEED_TENANT_ID, TenantContext.userId(), templates::findAll);
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("Cannot read the default templates", e);
    }
  }
}
