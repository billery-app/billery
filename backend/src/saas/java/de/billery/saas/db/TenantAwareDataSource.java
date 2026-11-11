package de.billery.saas.db;

import de.billery.saas.tenant.TenantContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DelegatingDataSource;

/**
 * Hands the active tenant to the database with every connection it lends: the session settings
 * {@code app.tenant_id}, {@code app.user_id} and {@code app.user_email}, which the row-level
 * security policies and the column defaults of the SaaS schema read.
 *
 * <p>The settings are session-level, set when a connection is borrowed and reset when it is
 * returned, so a pooled connection never carries one tenant's id into another's request. Session
 * settings need a connection that stays the same across statements: connect directly or through a
 * session-level pooler, not through a transaction-level one (such as Supabase's port 6543).
 *
 * <p>A thread without a tenant gets a connection with the settings cleared; tenant data is then
 * unreadable (the policies raise an error) while tenant-independent queries still work.
 */
@SuppressWarnings("PMD.UseProperClassLoader") // the JDBC API classes live in the platform loader
public class TenantAwareDataSource extends DelegatingDataSource {

  private static final String APPLY_SETTINGS =
      "SELECT set_config('app.tenant_id', ?, false), set_config('app.user_id', ?, false),"
          + " set_config('app.user_email', ?, false)";

  /**
   * Wraps {@code target}.
   *
   * @param target the pool or data source of the application role
   */
  public TenantAwareDataSource(DataSource target) {
    super(target);
  }

  @Override
  public Connection getConnection() throws SQLException {
    return prepared(super.getConnection());
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    return prepared(super.getConnection(username, password));
  }

  private static Connection prepared(Connection connection) throws SQLException {
    try {
      apply(
          connection, TenantContext.tenantId(), TenantContext.userId(), TenantContext.userEmail());
    } catch (SQLException | RuntimeException e) {
      connection.close();
      throw e;
    }
    InvocationHandler handler =
        (proxy, method, args) -> {
          if ("close".equals(method.getName()) && !connection.isClosed()) {
            release(connection);
            return null;
          }
          try {
            return method.invoke(connection, args);
          } catch (InvocationTargetException e) {
            throw e.getCause();
          }
        };
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, handler);
  }

  /**
   * Clears the tenant from the connection and returns it to the pool. If that is not possible the
   * physical connection is aborted instead, so one that still carries a tenant never gets lent
   * again.
   */
  private static void release(Connection connection) throws SQLException {
    try {
      if (!connection.getAutoCommit()) {
        connection.rollback();
      }
      apply(connection, null, null, null);
    } catch (SQLException | RuntimeException e) {
      connection.abort(Runnable::run);
      return;
    }
    connection.close();
  }

  /** Sets (or, with nulls, clears) the session settings; an empty string means "none". */
  private static void apply(
      Connection connection, Integer tenantId, String userId, String userEmail)
      throws SQLException {
    try (PreparedStatement stmt = connection.prepareStatement(APPLY_SETTINGS)) {
      stmt.setString(1, tenantId == null ? "" : tenantId.toString());
      stmt.setString(2, userId == null ? "" : userId);
      stmt.setString(3, userEmail == null ? "" : userEmail);
      stmt.execute();
    }
  }
}
