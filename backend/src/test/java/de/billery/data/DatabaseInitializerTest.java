package de.billery.data;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

/** Initializes the H2 desktop schema, so it does not run when the suite targets PostgreSQL. */
@DisabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
class DatabaseInitializerTest {

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @Test
  void testInitializeCreatesTables() throws Exception {
    // Use the H2-compatible scheme-h2.sql to initialize database
    try (InputStream schemaStream =
        DatabaseInitializerTest.class.getResourceAsStream("/scheme-h2.sql")) {
      assertNotNull(schemaStream, "scheme-h2.sql should exist in test resources");

      // Schema initialisieren (re-initializing should work fine with H2)
      DatabaseInitializer.initialize(schemaStream);
    }

    // Prüfen, ob wichtige Tabellen existieren
    try (Connection conn = Database.getConnection()) {
      DatabaseMetaData meta = conn.getMetaData();

      // Check customer table
      try (ResultSet rs = meta.getTables(null, null, "CUSTOMER", null)) {
        assertTrue(rs.next(), "Tabelle 'customer' sollte existieren");
      }

      // Check invoice table
      try (ResultSet rs = meta.getTables(null, null, "INVOICE", null)) {
        assertTrue(rs.next(), "Tabelle 'invoice' sollte existieren");
      }

      // Check document_type table
      try (ResultSet rs = meta.getTables(null, null, "DOCUMENT_TYPE", null)) {
        assertTrue(rs.next(), "Tabelle 'document_type' sollte existieren");
      }
    }
  }

  @Test
  void splitStatementsKeepsDollarQuotedFunctionBodiesTogether() {
    String script =
        "CREATE TABLE a (id INT);\n"
            + "CREATE FUNCTION f() RETURNS trigger AS $$\nBEGIN\n  RETURN NEW;\nEND;\n$$ LANGUAGE plpgsql;\n"
            + "INSERT INTO a VALUES (1);";

    java.util.List<String> statements =
        DatabaseInitializer.splitStatements(script).stream()
            .map(String::trim)
            .filter(statement -> !statement.isEmpty())
            .toList();

    assertEquals(3, statements.size());
    assertTrue(statements.get(1).contains("RETURN NEW;"));
    assertTrue(statements.get(1).endsWith("LANGUAGE plpgsql"));
  }

  @Test
  void splitStatementsIgnoresSemicolonsInCommentsAndStringLiterals() {
    String script =
        "-- a comment; with a semicolon\n"
            + "INSERT INTO t VALUES ('a;b', 'it''s; fine'); /* block; comment */\n"
            + "SELECT 1; -- trailing; note\n"
            + "SELECT 2";

    java.util.List<String> statements =
        DatabaseInitializer.splitStatements(script).stream()
            .map(String::trim)
            .filter(statement -> !statement.isEmpty())
            .toList();

    assertEquals(3, statements.size());
    assertTrue(statements.get(0).contains("'a;b'"));
    assertTrue(statements.get(0).contains("'it''s; fine'"));
    assertTrue(statements.get(1).startsWith("/* block; comment */"));
    assertTrue(statements.get(2).startsWith("-- trailing; note"));
    assertTrue(statements.get(2).endsWith("SELECT 2"));
  }
}
