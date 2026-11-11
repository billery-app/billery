package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.data.Database;
import de.billery.data.SchemaMigrator;
import de.billery.data.TestDatabaseConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * The SaaS migrations on a real PostgreSQL: they run after the core ones with their own history
 * table. Compiled and run only with {@code -Psaas}, which also activates the Spring profile.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class SaasSchemaTest {

  /** Number of {@code V*.sql} files in {@code db/migration-saas}; grows with every migration. */
  private static final int SAAS_FILES = saasFiles();

  private static int saasFiles() {
    try {
      return new PathMatchingResourcePatternResolver()
          .getResources("classpath:db/migration-saas/V*.sql")
          .length;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @BeforeAll
  static void connect() {
    Database.setDbPath(System.getenv("POSTGRES_TEST_URL"));
  }

  @AfterAll
  static void restore() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void emptyDatabase() throws SQLException {
    execute("DROP SCHEMA public CASCADE", "CREATE SCHEMA public");
  }

  private static void execute(String... statements) throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement()) {
      for (String sql : statements) {
        stmt.execute(sql);
      }
    }
  }

  private static int count(String sql) throws SQLException {
    try (Connection conn = Database.getConnection();
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  @Test
  void saasMigrationsRunAfterTheCoreOnesWithTheirOwnHistory() throws SQLException {
    SchemaMigrator.migrate();

    assertEquals(1, count("SELECT COUNT(*) FROM tenant"), "the seed-data tenant");
    assertEquals(1, count("SELECT id FROM tenant"));
    assertEquals(
        SAAS_FILES, count("SELECT COUNT(*) FROM flyway_schema_history_saas WHERE type = 'SQL'"));
    assertEquals(
        0,
        count("SELECT COUNT(*) FROM flyway_schema_history WHERE description LIKE '%tenant table%'"),
        "the SaaS versions do not appear in the core history");
  }

  @Test
  void aSecondRunHasNothingToDoAndNewTenantsGetFreshIds() throws SQLException {
    SchemaMigrator.migrate();
    assertEquals(0, SchemaMigrator.migrate());

    execute("INSERT INTO tenant (name) VALUES ('Second')");
    assertEquals(2, count("SELECT MAX(id) FROM tenant"), "the identity continues after tenant 1");
  }
}
