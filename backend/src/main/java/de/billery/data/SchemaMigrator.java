package de.billery.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the versioned PostgreSQL migrations ({@code classpath:db/migration}, Flyway naming {@code
 * V<version>__<description>.sql}) to the configured database.
 *
 * <p>An empty database gets the whole history, starting with {@code V1__baseline.sql}. A database
 * that already has tables but no Flyway history (created by the earlier {@code scheme.sql}) is
 * baselined at version 1 and only receives later migrations.
 *
 * <p>Several backend instances may start at once. Flyway locks only the migration itself; creating
 * and baselining the history table happens before that and races ("Unable to baseline schema
 * history table ... already exists, and is empty"). The whole run is therefore wrapped in a
 * PostgreSQL advisory lock of its own: the other instances wait and then find nothing left to do.
 *
 * <p>Applied migrations are immutable (Flyway verifies their checksums on every start): a schema
 * change is always a new file with the next version number, never an edit of an old one.
 *
 * <p>With the Spring profile {@code saas} active, the SaaS migrations ({@code
 * classpath:db/migration-saas}, present only in a build made with {@code -Psaas}) run after the
 * core ones. They keep their own history table, {@code flyway_schema_history_saas}, so their
 * version numbers are independent of the core's. If the profile is active but the build lacks the
 * SaaS migrations, the start fails with a clear message instead of running without tenancy.
 *
 * <p>H2 (desktop) does not use this class; it keeps {@code scheme-h2.sql} and {@link
 * DatabaseMigration}.
 */
public final class SchemaMigrator {

  private static final Logger LOGGER = Logger.getLogger(SchemaMigrator.class.getName());

  /** Version a core database without Flyway history is baselined at: the content of V1. */
  private static final String CORE_BASELINE_VERSION = "1";

  /**
   * The SaaS history starts on a schema that already holds the core tables, which Flyway only
   * accepts after a baseline; version 0 lets the first SaaS migration run.
   */
  private static final String SAAS_BASELINE_VERSION = "0";

  /** Location and history table of the SaaS migrations (profile {@code saas}). */
  static final String SAAS_LOCATION = "db/migration-saas";

  static final String SAAS_HISTORY_TABLE = "flyway_schema_history_saas";

  /** Key of the advisory lock that serialises concurrent migrations (arbitrary, app-specific). */
  private static final long MIGRATION_LOCK_KEY = 0x62696C6C65727931L;

  private SchemaMigrator() {}

  /**
   * Migrates the database {@link Database} points at to the latest version.
   *
   * @return the number of migrations applied (0 if the schema was already current)
   * @throws DatabaseException if a migration fails; the application must not start on a
   *     half-migrated schema
   */
  public static int migrate() {
    try (Connection lockConnection = Database.migrationDataSource().getConnection()) {
      setAdvisoryLock(lockConnection, true);
      try {
        return runFlyway();
      } finally {
        setAdvisoryLock(lockConnection, false);
      }
    } catch (SQLException | RuntimeException e) {
      throw new DatabaseException("Schema migration failed", e);
    }
  }

  private static int runFlyway() {
    int applied = run("db/migration", "flyway_schema_history", CORE_BASELINE_VERSION);
    if (AppProfiles.isSaas()) {
      if (SchemaMigrator.class.getResource("/" + SAAS_LOCATION) == null) {
        throw new IllegalStateException(
            "The Spring profile 'saas' is active, but this build does not contain the SaaS code"
                + " (classpath:"
                + SAAS_LOCATION
                + "); build with -Psaas");
      }
      applied += run(SAAS_LOCATION, SAAS_HISTORY_TABLE, SAAS_BASELINE_VERSION);
    }
    return applied;
  }

  private static int run(String location, String historyTable, String baselineVersion) {
    FluentConfiguration configuration =
        Flyway.configure()
            .dataSource(Database.migrationDataSource())
            .locations("classpath:" + location)
            .table(historyTable);
    Flyway flyway = configuration.baselineOnMigrate(true).baselineVersion(baselineVersion).load();
    MigrateResult result = flyway.migrate();
    if (LOGGER.isLoggable(Level.INFO)) {
      MigrationInfo current = flyway.info().current();
      LOGGER.info(
          location
              + " is at version "
              + (current == null ? "none" : current.getVersion())
              + " ("
              + result.migrationsExecuted
              + " migration(s) applied)");
    }
    return result.migrationsExecuted;
  }

  /** Takes (blocking) or releases the session-level advisory lock on {@code connection}. */
  private static void setAdvisoryLock(Connection connection, boolean acquire) throws SQLException {
    String sql = acquire ? "SELECT pg_advisory_lock(?)" : "SELECT pg_advisory_unlock(?)";
    try (PreparedStatement stmt = connection.prepareStatement(sql)) {
      stmt.setLong(1, MIGRATION_LOCK_KEY);
      stmt.execute();
    }
  }
}
