package de.billery.data;

import de.billery.config.AppConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Creates the database schema and, only on explicit request, loads demo data.
 *
 * <p>Demo data ({@code /dummies.sql}: fake customers, documents and number-series counters) is
 * never loaded implicitly. Production and desktop installs start empty; developers opt in with
 * {@code LOAD_DEMO_DATA=true} (see {@link #isDemoDataRequested()}).
 *
 * <p>Scripts are split on {@code ;} outside comments, string literals and dollar-quoted blocks (see
 * {@link #splitStatements}), so those may contain semicolons.
 */
public final class DatabaseInitializer {

  private static final Logger LOGGER = Logger.getLogger(DatabaseInitializer.class.getName());

  private DatabaseInitializer() {}

  /**
   * Executes a schema script statement by statement.
   *
   * @param schema the SQL script
   * @throws DatabaseException if the script cannot be read or a statement fails
   */
  public static void initialize(InputStream schema) {
    try (Connection conn = Database.getConnection()) {
      executeScript(conn, new String(schema.readAllBytes(), StandardCharsets.UTF_8));
    } catch (SQLException | IOException e) {
      throw new DatabaseException("Database schema initialization failed", e);
    }
  }

  /**
   * Loads {@code /dummies.sql} from the classpath, unless the database already has customers.
   *
   * @return true if the demo data was loaded
   * @throws DatabaseException if a demo-data statement fails
   */
  public static boolean loadDemoData() {
    try (InputStream in = DatabaseInitializer.class.getResourceAsStream("/dummies.sql")) {
      if (in == null) {
        LOGGER.warning("LOAD_DEMO_DATA is set but /dummies.sql is not on the classpath");
        return false;
      }
      return loadDemoData(in);
    } catch (IOException e) {
      throw new DatabaseException("Loading demo data failed", e);
    }
  }

  /**
   * Runs a demo-data script, unless the database already has customers.
   *
   * @param script the SQL script (read completely, not closed)
   * @return true if the demo data was loaded
   * @throws DatabaseException if the script cannot be read or a statement fails
   */
  public static boolean loadDemoData(InputStream script) {
    try (Connection conn = Database.getConnection()) {
      if (!isDatabaseEmpty(conn)) {
        LOGGER.info("Database already has data, skipping demo data.");
        return false;
      }
      executeScript(conn, new String(script.readAllBytes(), StandardCharsets.UTF_8));
      LOGGER.info("Demo data loaded.");
      return true;
    } catch (SQLException | IOException e) {
      throw new DatabaseException("Loading demo data failed", e);
    }
  }

  /**
   * Whether the operator asked for demo data via {@code LOAD_DEMO_DATA=true}: system property,
   * environment variable or {@code .env} entry (in that order).
   *
   * @return true if demo data should be loaded on startup
   */
  public static boolean isDemoDataRequested() {
    return Boolean.parseBoolean(
        System.getProperty("LOAD_DEMO_DATA", AppConfig.get("LOAD_DEMO_DATA")));
  }

  private static void executeScript(Connection conn, String sql) throws SQLException {
    try (Statement stmt = conn.createStatement()) {
      for (String statement : splitStatements(sql)) {
        String trimmed = statement.trim();
        if (trimmed.isEmpty()) {
          continue;
        }
        try {
          stmt.execute(trimmed);
        } catch (SQLException e) {
          LOGGER.log(
              Level.SEVERE,
              "Failed SQL: " + trimmed.substring(0, Math.min(200, trimmed.length())),
              e);
          throw e;
        }
      }
    }
  }

  /**
   * Splits a script into statements at every {@code ;} that is not inside a {@code --} line
   * comment, a {@code /* ... *}{@code /} block comment, a single-quoted string literal (with {@code
   * ''} as escaped quote) or a dollar-quoted block ({@code $$ ... $$}, used for function bodies),
   * so such text may contain semicolons.
   *
   * @param sql the script
   * @return the statements, without the terminating semicolons (may contain blank entries)
   */
  static List<String> splitStatements(String sql) {
    List<String> statements = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    int i = 0;
    while (i < sql.length()) {
      int end = quotedOrCommentEnd(sql, i);
      if (end > i) {
        current.append(sql, i, end);
        i = end;
      } else if (sql.charAt(i) == ';') {
        statements.add(current.toString());
        current.setLength(0);
        i++;
      } else {
        current.append(sql.charAt(i));
        i++;
      }
    }
    statements.add(current.toString());
    return statements;
  }

  /**
   * If a comment, string literal or dollar-quoted block starts at {@code start}, returns the index
   * just after it (or the end of the script if it is not closed); otherwise {@code start}.
   */
  private static int quotedOrCommentEnd(String sql, int start) {
    if (sql.startsWith("--", start)) {
      int newline = sql.indexOf('\n', start);
      return newline < 0 ? sql.length() : newline;
    }
    if (sql.startsWith("/*", start)) {
      int close = sql.indexOf("*/", start + 2);
      return close < 0 ? sql.length() : close + 2;
    }
    if (sql.startsWith("$$", start)) {
      int close = sql.indexOf("$$", start + 2);
      return close < 0 ? sql.length() : close + 2;
    }
    if (sql.charAt(start) == '\'') {
      int i = start + 1;
      while (i < sql.length()) {
        if (sql.charAt(i) == '\'') {
          if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
            i += 2;
            continue;
          }
          return i + 1;
        }
        i++;
      }
      return sql.length();
    }
    return start;
  }

  private static boolean isDatabaseEmpty(Connection conn) throws SQLException {
    try (Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM customer")) {
      return !rs.next() || rs.getInt(1) == 0;
    }
  }
}
