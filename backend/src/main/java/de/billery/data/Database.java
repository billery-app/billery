package de.billery.data;

import de.billery.config.AppConfig;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Connection settings and the DataSource of the invoice management system.
 *
 * <p>The URL comes from {@code DB_URL} when set (e.g. the H2 file URL of the desktop build),
 * otherwise it is assembled from {@code DB_HOST}/{@code DB_PORT}/{@code DB_NAME}. PostgreSQL
 * credentials are supplied separately through {@code DB_USER}/{@code DB_PASSWORD}, so the password
 * does not appear in a JDBC URL or application log.
 *
 * <p>All connections come from one {@link DataSource}: the pooled bean registered by {@code
 * DbConfig} via {@link #useDataSource(DataSource)} once Spring is up, otherwise (pre-Spring
 * bootstrap in {@code MainApp.prepareDatabase()}, tests without a Spring context) a {@link
 * DriverManagerDataSource} on the configured URL. Repositories use a {@code JdbcClient} on the same
 * DataSource; services open transactions through Spring's {@code TransactionTemplate}.
 *
 * <p>Inside such a transaction {@link #getConnection()} returns the connection Spring bound to the
 * thread, wrapped so that {@code close()}, {@code commit()}, {@code rollback()} and {@code
 * setAutoCommit()} are no-ops: plain-JDBC code cannot end the transaction early.
 */
@SuppressWarnings({
  "PMD.NullAssignment", // null credentials mean "use the URL as-is"
  "PMD.UseProperClassLoader" // the JDBC API classes live in the platform loader
})
public class Database {

  private static final String DEV_PROFILE = "dev";
  private static final String DEFAULT_DEV_USER = "billery";
  // Known placeholder values rejected by rejectKnownDefault(), not live credentials.
  private static final String DEFAULT_DEV_PASSWORD = "billery123"; // gitleaks:allow
  // The development password of the project's former name, still in older .env files.
  private static final String LEGACY_DEV_PASSWORD = "rechnung123"; // gitleaks:allow

  private static String dbUrl;
  private static Properties credentials;

  /** Credentials of the migration role (the schema owner) when it differs from the app role. */
  private static Properties migrationCredentials;

  /** The DataSource all connections come from; rebuilt lazily when the URL changes. */
  private static volatile DataSource current;

  /**
   * True when a test or desktop caller supplied the URL directly instead of using the environment.
   */
  private static volatile boolean customDatabasePath;

  static {
    reloadFromEnv();
  }

  private Database() {}

  /**
   * Returns a connection to the configured database. Inside a Spring-managed transaction this is
   * the thread's transaction connection, whose lifecycle methods are no-ops.
   *
   * @return a Connection object to the database
   * @throws SQLException if a database access error occurs
   */
  public static Connection getConnection() throws SQLException {
    DataSource source = dataSource();
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
      return nonClosing(DataSourceUtils.getConnection(source));
    }
    return source.getConnection();
  }

  /**
   * Returns the DataSource every connection comes from. {@code JdbcClient}s and transaction
   * managers built on it share the thread's transaction connection.
   *
   * @return the current DataSource
   */
  public static DataSource dataSource() {
    DataSource source = current;
    if (source == null) {
      synchronized (Database.class) {
        if (current == null) {
          current = driverManagerDataSource();
        }
        source = current;
      }
    }
    return source;
  }

  /**
   * Returns the DataSource schema migrations run with. By default the application's own; when
   * {@code DB_MIGRATION_USER} is set (the SaaS deployment, where the application connects as a role
   * that is neither owner nor superuser so that row-level security applies) a plain, unpooled
   * DataSource for that user, which is only used while the application starts.
   *
   * @return the DataSource for Flyway and the migration lock
   */
  public static DataSource migrationDataSource() {
    if (migrationCredentials == null) {
      return dataSource();
    }
    DriverManagerDataSource source = new DriverManagerDataSource(dbUrl);
    source.setConnectionProperties(migrationCredentials);
    return source;
  }

  /**
   * Makes {@code source} the DataSource for all further connections. Called by {@code DbConfig}
   * with the pooled bean, so bootstrap code and repositories share the pool once Spring is up.
   *
   * @param source the DataSource to use
   */
  public static synchronized void useDataSource(DataSource source) {
    current = source;
  }

  private static DataSource driverManagerDataSource() {
    DriverManagerDataSource source = new DriverManagerDataSource(dbUrl);
    if (credentials != null) {
      source.setConnectionProperties(credentials);
    }
    return source;
  }

  /** Wraps the transaction connection so plain-JDBC code cannot end the transaction early. */
  private static Connection nonClosing(Connection target) {
    return (Connection)
        Proxy.newProxyInstance(
            Connection.class.getClassLoader(),
            new Class<?>[] {Connection.class},
            (proxy, method, args) ->
                switch (method.getName()) {
                  case "close", "commit", "rollback", "setAutoCommit" -> null;
                  case "isClosed" -> false;
                  default -> {
                    try {
                      yield method.invoke(target, args);
                    } catch (InvocationTargetException e) {
                      throw e.getCause();
                    }
                  }
                });
  }

  /**
   * Returns the current database URL. Never contains the password unless it was put into {@code
   * DB_URL} explicitly.
   *
   * @return the JDBC URL
   */
  public static String getUrl() {
    return dbUrl;
  }

  /**
   * Returns the configured user name, or {@code null} when no separate credentials are configured
   * (e.g. the desktop H2 file URL).
   *
   * @return the JDBC user name, or null
   */
  public static String getUser() {
    return credentials == null ? null : credentials.getProperty("user");
  }

  /**
   * Returns the configured password, or {@code null} when no separate credentials are configured.
   *
   * @return the JDBC password, or null
   */
  public static String getPassword() {
    return credentials == null ? null : credentials.getProperty("password");
  }

  /**
   * Sets a custom database URL (no separate credentials) for tests or desktop mode. The DataSource
   * is rebuilt on next use.
   *
   * @param url the JDBC URL
   */
  public static void setDbPath(String url) {
    dbUrl = url;
    customDatabasePath = true;
    credentials = null;
    migrationCredentials = null;
    loadDriver(url);
    current = null;
  }

  /**
   * Re-reads the connection settings from the runtime environment or local {@code .env} file. The
   * DataSource is rebuilt on next use.
   */
  public static void reloadFromEnv() {
    customDatabasePath = false;
    Map<String, String> environment = runtimeEnvironment();
    boolean devProfile = AppProfiles.isActive(DEV_PROFILE);
    String url = environment.get("DB_URL");
    if (url != null && !url.isBlank()) {
      dbUrl = url;
      credentials =
          url.startsWith("jdbc:h2:") ? null : connectionCredentials(environment, devProfile);
    } else {
      String host = environment.getOrDefault("DB_HOST", "localhost");
      String port = environment.getOrDefault("DB_PORT", "5432");
      String database = environment.getOrDefault("DB_NAME", "billery");
      dbUrl = String.format("jdbc:postgresql://%s:%s/%s", host, port, database);
      credentials = connectionCredentials(environment, devProfile);
    }
    String migrationUser = environment.get("DB_MIGRATION_USER");
    if (migrationUser != null && !migrationUser.isBlank()) {
      migrationCredentials = new Properties();
      migrationCredentials.setProperty("user", migrationUser);
      String migrationPassword = environment.get("DB_MIGRATION_PASSWORD");
      if (migrationPassword != null) {
        migrationCredentials.setProperty("password", migrationPassword);
      }
    } else {
      migrationCredentials = null;
    }
    loadDriver(dbUrl);
    current = null;
  }

  /**
   * Fails before database access when a non-development deployment has no explicit credentials. The
   * local credentials are available only with the explicit {@code dev} profile; the desktop profile
   * is allowed to use its H2 file database without PostgreSQL credentials.
   */
  public static void validateConfiguration() {
    if (customDatabasePath) {
      return;
    }
    validateConfiguration(
        runtimeEnvironment(), AppProfiles.isActive(DEV_PROFILE), AppProfiles.isActive("desktop"));
  }

  private static Map<String, String> runtimeEnvironment() {
    Map<String, String> environment = new HashMap<>(System.getenv());
    String[] databaseSettings = {
      "DB_URL",
      "DB_HOST",
      "DB_PORT",
      "DB_NAME",
      "DB_USER",
      "DB_PASSWORD",
      "DB_MIGRATION_USER",
      "DB_MIGRATION_PASSWORD"
    };
    for (String setting : databaseSettings) {
      if (!environment.containsKey(setting)) {
        String value = AppConfig.get(setting);
        if (value != null) {
          environment.put(setting, value);
        }
      }
    }
    return environment;
  }

  static void validateConfiguration(
      Map<String, String> environment, boolean devProfile, boolean desktopProfile) {
    String url = environment.get("DB_URL");
    if (url != null && url.startsWith("jdbc:h2:")) {
      if (desktopProfile || devProfile) {
        return;
      }
      throw new IllegalStateException(
          "An H2 database requires the explicit desktop or dev profile.");
    }
    if (devProfile) {
      return;
    }

    if (!hasValue(environment.get("DB_USER")) || !hasValue(environment.get("DB_PASSWORD"))) {
      throw new IllegalStateException(
          "DB_USER and DB_PASSWORD are required; supply database credentials through the runtime"
              + " environment.");
    }
    rejectKnownDefault(environment, "DB_PASSWORD");
    rejectKnownDefault(environment, "DB_MIGRATION_PASSWORD");

    String migrationUser = environment.get("DB_MIGRATION_USER");
    String migrationPassword = environment.get("DB_MIGRATION_PASSWORD");
    if (hasValue(migrationUser) && !hasValue(migrationPassword)) {
      throw new IllegalStateException(
          "DB_MIGRATION_PASSWORD is required when DB_MIGRATION_USER is configured.");
    }
    if (hasValue(migrationPassword) && !hasValue(migrationUser)) {
      throw new IllegalStateException(
          "DB_MIGRATION_USER is required when DB_MIGRATION_PASSWORD is configured.");
    }
  }

  // null means "no credentials configured"; getUser()/getPassword() rely on that distinction.
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  static Properties connectionCredentials(Map<String, String> environment, boolean devProfile) {
    String user = environment.get("DB_USER");
    String password = environment.get("DB_PASSWORD");
    if (devProfile) {
      user = hasValue(user) ? user : DEFAULT_DEV_USER;
      password = hasValue(password) ? password : DEFAULT_DEV_PASSWORD;
    }
    if (!hasValue(user) && !hasValue(password)) {
      return null;
    }
    Properties result = new Properties();
    if (hasValue(user)) {
      result.setProperty("user", user);
    }
    if (hasValue(password)) {
      result.setProperty("password", password);
    }
    return result;
  }

  private static void rejectKnownDefault(Map<String, String> environment, String name) {
    String value = environment.get(name);
    if (DEFAULT_DEV_PASSWORD.equals(value) || LEGACY_DEV_PASSWORD.equals(value)) {
      throw new IllegalStateException(
          "Database credentials must not use a known development default. Configure a new secret.");
    }
  }

  private static boolean hasValue(String value) {
    return value != null && !value.isBlank();
  }

  private static void loadDriver(String url) {
    String driverClass = url.startsWith("jdbc:h2:") ? "org.h2.Driver" : "org.postgresql.Driver";
    try {
      Class.forName(driverClass);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("JDBC driver not found: " + driverClass, e);
    }
  }
}
