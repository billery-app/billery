package de.billery;

import de.billery.config.AppConfig;
import de.billery.data.AppProfiles;
import de.billery.data.Database;
import de.billery.data.DatabaseException;
import de.billery.data.DatabaseInitializer;
import de.billery.data.DatabaseMigration;
import de.billery.data.SchemaMigrator;
import de.billery.desktop.BackupLocations;
import de.billery.desktop.PendingRestore;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point. Prepares the database (schema on first start, optional demo data, migrations) and
 * then starts Spring Boot. Any database preparation failure aborts the start: running the API on a
 * missing or half-migrated schema would only produce harder-to-diagnose errors later.
 */
@SpringBootApplication
public class MainApp {

  private static final Logger LOGGER = Logger.getLogger(MainApp.class.getName());

  /**
   * Starts the application.
   *
   * @param args Spring Boot command-line arguments
   * @throws IOException if the schema resource cannot be read
   */
  public static void main(String[] args) throws IOException {
    AppProfiles.requireProfileForBuild();
    prepareDatabase();
    SpringApplication.run(MainApp.class, args);
  }

  /**
   * Applies a desktop restore chosen before the last exit ({@link PendingRestore}), brings the
   * schema up to date, loads demo data when {@code LOAD_DEMO_DATA=true}, and runs the legacy
   * migrations. PostgreSQL is migrated by Flyway ({@link SchemaMigrator}, which also creates the
   * schema on an empty database); H2 (desktop) gets {@code scheme-h2.sql} on an empty database and
   * the idempotent {@link DatabaseMigration}s on every start.
   *
   * @throws IOException if the schema resource cannot be read
   * @throws DatabaseException if the database is unreachable or a step fails
   */
  static void prepareDatabase() throws IOException {
    Database.validateConfiguration();
    LOGGER.info("Preparing database.");
    if (Database.getUrl().startsWith("jdbc:h2:")) {
      // A restore chosen in the desktop app replaces the database file before anything opens it.
      PendingRestore.applyIfPending(
          BackupLocations.forDatabase(Database.getUrl(), AppConfig.FILES_PATH));
      if (!schemaExists()) {
        try (InputStream in = MainApp.class.getResourceAsStream("/scheme-h2.sql")) {
          if (in == null) {
            throw new IllegalStateException("/scheme-h2.sql not found on the classpath");
          }
          DatabaseInitializer.initialize(in);
        }
        LOGGER.info("Database schema created.");
      }
    } else {
      checkConnection();
      SchemaMigrator.migrate();
    }
    if (DatabaseInitializer.isDemoDataRequested()) {
      DatabaseInitializer.loadDemoData();
    }
    DatabaseMigration.runMigrations();
  }

  /** A failing connection is an error with a clear message, not a Flyway stack trace. */
  private static void checkConnection() {
    try (Connection conn = Database.getConnection()) {
      conn.isValid(5);
    } catch (SQLException e) {
      throw new DatabaseException("Cannot connect to the configured database.", e);
    }
  }

  /**
   * Checks whether the schema exists. Unlike a failing query, a failing connection is an error, not
   * a signal to create the schema.
   */
  private static boolean schemaExists() {
    Connection conn;
    try {
      conn = Database.getConnection();
    } catch (SQLException e) {
      throw new DatabaseException("Cannot connect to the configured database.", e);
    }
    try (conn;
        Statement stmt = conn.createStatement()) {
      stmt.executeQuery("SELECT 1 FROM customer WHERE 1 = 0").close();
      return true;
    } catch (SQLException e) {
      return false;
    }
  }
}
