package de.billery.saas;

import de.billery.data.Database;
import de.billery.saas.db.TenantAwareDataSource;
import de.billery.saas.tenant.TenantContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Test helper of the {@code -Psaas} suite: a login role that is a member of {@code billery_app},
 * i.e. exactly what the production backend connects as, and the DataSources built on it. The admin
 * account of {@code POSTGRES_TEST_URL} (a superuser, which bypasses row-level security) is only
 * used to prepare and clean the database.
 */
public final class SaasTestSupport {

  /** Login role used by the suite; created on demand, a member of {@code billery_app}. */
  public static final String APP_LOGIN = "billery_rls_test";

  private static final String APP_PASSWORD = "rls-test";

  private SaasTestSupport() {}

  /**
   * Creates the application login role if needed and makes {@link Database} use it, tenant-aware,
   * with tenant 1 for every thread that has none. Called by {@code TestDatabaseConfig} after the
   * schema exists.
   *
   * @param adminUrl the JDBC URL with the admin credentials ({@code POSTGRES_TEST_URL})
   * @throws SQLException if the role cannot be created
   */
  public static void installAppRole(String adminUrl) throws SQLException {
    ensureLoginRole(adminUrl);
    Database.useDataSource(new FixedTenantDataSource(appRoleDataSource(adminUrl), 1));
  }

  /**
   * Creates the login role (idempotent) and grants it the application role.
   *
   * @param adminUrl the JDBC URL with the admin credentials
   * @throws SQLException if the statements fail
   */
  public static void ensureLoginRole(String adminUrl) throws SQLException {
    try (Connection admin = DriverManager.getConnection(adminUrl);
        Statement stmt = admin.createStatement()) {
      stmt.execute(
          "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '"
              + APP_LOGIN
              + "') THEN CREATE ROLE "
              + APP_LOGIN
              + " LOGIN PASSWORD '"
              + APP_PASSWORD
              + "' IN ROLE billery_app; END IF; END $$");
    }
  }

  /**
   * A plain DataSource connecting as the application login role to the database of {@code
   * adminUrl}.
   *
   * @param adminUrl the JDBC URL with the admin credentials; the credentials are replaced
   * @return the DataSource (not tenant-aware)
   */
  public static DataSource appRoleDataSource(String adminUrl) {
    String withoutCredentials =
        adminUrl.replaceAll("[?&](user|password)=[^&]*", "").replaceFirst("&", "?");
    DriverManagerDataSource source = new DriverManagerDataSource(withoutCredentials);
    source.setUsername(APP_LOGIN);
    source.setPassword(APP_PASSWORD);
    return source;
  }

  /** Tenant-aware connections; a thread without a tenant is lent tenant {@code fallback}. */
  private static final class FixedTenantDataSource extends DelegatingDataSource {

    private final DataSource tenantAware;
    private final int fallback;

    FixedTenantDataSource(DataSource target, int fallback) {
      super(target);
      this.tenantAware = new TenantAwareDataSource(target);
      this.fallback = fallback;
    }

    @Override
    public Connection getConnection() throws SQLException {
      if (TenantContext.tenantId() != null) {
        return tenantAware.getConnection();
      }
      TenantContext.set(fallback, null);
      try {
        return tenantAware.getConnection();
      } finally {
        TenantContext.clear();
      }
    }
  }
}
