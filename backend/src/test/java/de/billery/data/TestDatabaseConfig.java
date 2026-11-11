package de.billery.data;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Test utilities for configuring the database. By default this is an H2 in-memory database in
 * {@code MODE=MariaDB}, so tests run without an external database container (useful in
 * Docker-in-Docker CI environments). With the environment variable {@code POSTGRES_TEST_URL} the
 * same entry points drive a real PostgreSQL instead (see {@link #setupH2Database()}).
 *
 * <p>Usage: Call {@link #setupH2Database()} in your test setup method or @BeforeAll.
 */
public class TestDatabaseConfig {

  private static final String H2_URL_TEMPLATE =
      "jdbc:h2:mem:testdb;MODE=MariaDB;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE;";

  /**
   * Environment variable that points the whole suite at a real PostgreSQL (empty database; the
   * {@code public} schema is dropped and recreated). Without it the suite runs on H2.
   */
  private static final String POSTGRES_URL_ENV = "POSTGRES_TEST_URL";

  private static boolean isInitialized = false;

  private static boolean usesPostgres() {
    String url = System.getenv(POSTGRES_URL_ENV);
    return url != null && !url.isEmpty();
  }

  /**
   * Configures the Database class to use H2 in-memory database with MariaDB compatibility mode and
   * initializes the schema from the H2-compatible scheme-h2.sql file. When {@code
   * POSTGRES_TEST_URL} is set it instead resets the {@code public} schema of that PostgreSQL and
   * migrates it with Flyway and applies {@code dummies-postgres.sql}.
   *
   * <p>This method is safe to call multiple times - the database is only initialized once.
   *
   * @throws SQLException if database initialization fails
   */
  public static synchronized void setupH2Database() throws SQLException {
    if (isInitialized) {
      return;
    }

    if (usesPostgres()) {
      setupPostgresDatabase();
      isInitialized = true;
      return;
    }

    // Configure Database to use H2
    Database.setDbPath(H2_URL_TEMPLATE);

    // Initialize schema from H2-compatible SQL file
    try (InputStream in = TestDatabaseConfig.class.getResourceAsStream("/scheme-h2.sql")) {
      if (in == null) {
        throw new RuntimeException("scheme-h2.sql not found in test resources");
      }
      DatabaseInitializer.initialize(in);
      // Test fixtures (number series, prefixes) from src/test/resources/dummies.sql.
      DatabaseInitializer.loadDemoData();
    } catch (Exception e) {
      throw new SQLException("Failed to initialize H2 database schema", e);
    }

    isInitialized = true;
  }

  private static void setupPostgresDatabase() throws SQLException {
    Database.setDbPath(System.getenv(POSTGRES_URL_ENV));
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      stmt.execute("DROP SCHEMA public CASCADE");
      stmt.execute("CREATE SCHEMA public");
    }
    SchemaMigrator.migrate();
    try (InputStream in = TestDatabaseConfig.class.getResourceAsStream("/dummies-postgres.sql")) {
      if (in == null) {
        throw new SQLException("/dummies-postgres.sql not found on the classpath");
      }
      DatabaseInitializer.initialize(in);
    } catch (java.io.IOException e) {
      throw new SQLException("Failed to read /dummies-postgres.sql", e);
    }
    useAppRoleIfSaas();
  }

  /**
   * In the {@code -Psaas} build the suite runs as the application role under row-level security,
   * with tenant 1 for every thread that has none (self-host code must work unchanged under it).
   * Call this once the schema exists; setup, cleanup and fixtures keep using the admin account of
   * {@code POSTGRES_TEST_URL}. Does nothing in the default build and on H2.
   *
   * @throws SQLException if the role cannot be created
   */
  public static void useAppRoleIfSaas() throws SQLException {
    if (!usesPostgres() || !AppProfiles.isSaas()) {
      return;
    }
    try {
      Class.forName("de.billery.saas.SaasTestSupport")
          .getMethod("installAppRole", String.class)
          .invoke(null, System.getenv(POSTGRES_URL_ENV));
    } catch (ReflectiveOperationException e) {
      throw new SQLException("Cannot install the application role for the SaaS suite", e);
    }
  }

  /** The admin account of the test database; unaffected by row-level security. */
  private static Connection adminConnection() throws SQLException {
    return java.sql.DriverManager.getConnection(System.getenv(POSTGRES_URL_ENV));
  }

  /**
   * Clears all data from the database tables while keeping the schema intact. Useful for test
   * isolation between test methods.
   *
   * @throws SQLException if clearing data fails
   */
  public static void clearData() throws SQLException {
    try (Connection conn = usesPostgres() ? adminConnection() : Database.getConnection();
        Statement stmt = conn.createStatement()) {

      if (usesPostgres()) {
        stmt.execute(
            "TRUNCATE TABLE payment, document_history, invoice_item, invoice_period, invoice,"
                + " invoice_template, document_number_series, user_settings, service, article,"
                + " customer, owner, equipment, document_type, document_status"
                + " RESTART IDENTITY CASCADE");
      } else {
        truncateH2(stmt);
      }
      reseedLookupTables(stmt);
    }
  }

  private static void truncateH2(Statement stmt) throws SQLException {
    // Disable foreign key checks temporarily
    stmt.execute("SET REFERENTIAL_INTEGRITY FALSE");

    // Truncate all tables in correct order (children first). Literal statements, so no SQL is
    // assembled from strings at runtime.
    String[] truncates = {
      "TRUNCATE TABLE payment",
      "TRUNCATE TABLE document_history",
      "TRUNCATE TABLE invoice_item",
      "TRUNCATE TABLE invoice_period",
      "TRUNCATE TABLE invoice",
      "TRUNCATE TABLE invoice_template",
      "TRUNCATE TABLE document_number_series",
      "TRUNCATE TABLE user_settings",
      "TRUNCATE TABLE service",
      "TRUNCATE TABLE article",
      "TRUNCATE TABLE customer",
      "TRUNCATE TABLE owner",
      // equipment was missing here (the PostgreSQL branch has it), so equipment rows leaked
      // from one test class into the next (CatalogOrderTest broke
      // MasterDataControllersTest).
      "TRUNCATE TABLE equipment",
      "TRUNCATE TABLE document_type",
      "TRUNCATE TABLE document_status"
    };

    for (String truncate : truncates) {
      try {
        stmt.execute(truncate);
      } catch (SQLException e) {
        // Table might not exist, ignore
      }
    }

    // Re-enable foreign key checks
    stmt.execute("SET REFERENTIAL_INTEGRITY TRUE");
  }

  private static void reseedLookupTables(Statement stmt) throws SQLException {
    stmt.execute("INSERT INTO document_type (id, name) VALUES (1, 'INVOICE')");
    stmt.execute("INSERT INTO document_type (id, name) VALUES (2, 'OFFER')");
    stmt.execute("INSERT INTO document_type (id, name) VALUES (3, 'COST_CALCULATION')");
    stmt.execute("INSERT INTO document_type (id, name) VALUES (4, 'PARTED_INVOICE')");
    stmt.execute("INSERT INTO document_type (id, name) VALUES (5, 'FINAL_INVOICE')");
    stmt.execute("INSERT INTO document_type (id, name) VALUES (6, 'CREDIT_NOTE')");

    stmt.execute("INSERT INTO document_status (id, name) VALUES (1, 'DRAFT')");
    stmt.execute("INSERT INTO document_status (id, name) VALUES (2, 'SENT')");
    stmt.execute("INSERT INTO document_status (id, name) VALUES (3, 'ACCEPTED')");
    stmt.execute("INSERT INTO document_status (id, name) VALUES (4, 'REJECTED')");
    stmt.execute("INSERT INTO document_status (id, name) VALUES (5, 'CONVERTED')");
    stmt.execute("INSERT INTO document_status (id, name) VALUES (6, 'PAID')");
    stmt.execute("INSERT INTO document_status (id, name) VALUES (8, 'CANCELLED')");
    stmt.execute("INSERT INTO document_status (id, name) VALUES (9, 'PARTIALLY_PAID')");
  }

  /**
   * Resets the Database configuration to reload from environment variables. Call this in @AfterAll
   * to clean up after tests.
   */
  public static void resetDatabaseConfig() {
    isInitialized = false;
    Database.reloadFromEnv();
  }
}
