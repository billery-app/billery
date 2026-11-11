package de.billery.repository;

import de.billery.data.DatabaseException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.support.KeyHolder;

/**
 * Shared error handling for the JDBC repositories. Repositories must never swallow a database
 * error: they log it and rethrow it as an unchecked {@link DatabaseException}, either via {@code
 * throw databaseError("operation", e)} (plain JDBC) or by running the {@code JdbcClient} call in
 * {@link #run(String, Supplier)}, which does the same for Spring's {@link DataAccessException}.
 */
public class BaseRepository {

  private static final Logger LOGGER = Logger.getLogger(BaseRepository.class.getName());

  protected BaseRepository() {}

  /**
   * Logs a failed database operation and returns the exception to throw.
   *
   * @param operation short description of what failed, used in the log and exception message
   * @param exception the underlying error
   * @return the exception the caller must throw
   */
  protected static DatabaseException databaseError(String operation, Exception exception) {
    LOGGER.log(Level.SEVERE, "Database error during " + operation, exception);
    return new DatabaseException("Database error during " + operation, exception);
  }

  /**
   * Runs a {@code JdbcClient} call and translates a {@link DataAccessException} into the logged
   * {@link DatabaseException} the rest of the application expects.
   *
   * @param operation short description of the operation, used in the log and exception message
   * @param work the database call
   * @param <T> the result type
   * @return the result of {@code work}
   */
  protected static <T> T run(String operation, Supplier<T> work) {
    try {
      return work.get();
    } catch (DataAccessException e) {
      throw databaseError(operation, e);
    }
  }

  /**
   * Reads the generated primary key after an insert. PostgreSQL and H2 may return further generated
   * columns, so the {@code id} column is preferred and the first column is the fallback (what
   * {@code getGeneratedKeys().getInt(1)} used to read).
   *
   * @param keys the key holder filled by {@code JdbcClient...update(keys)}
   * @return the generated id
   * @throws DatabaseException if the driver returned no key
   */
  protected static int generatedId(KeyHolder keys) {
    Map<String, Object> row = keys.getKeys();
    if (row == null || row.isEmpty()) {
      throw new DatabaseException("Insert returned no generated key", null);
    }
    Object id =
        row.entrySet().stream()
            .filter(entry -> "id".equalsIgnoreCase(entry.getKey()))
            .map(Map.Entry::getValue)
            .findFirst()
            .orElseGet(() -> row.values().iterator().next());
    return ((Number) id).intValue();
  }

  /**
   * A case-insensitive "contains" pattern for {@code LOWER(column) LIKE :pattern ESCAPE '!'}:
   * lower-cased, wrapped in {@code %}, with {@code %}, {@code _} and {@code !} matching literally.
   *
   * @param text what the user typed
   * @return the LIKE pattern
   */
  static String containsPattern(String text) {
    String escaped =
        text.toLowerCase(java.util.Locale.ROOT)
            .replace("!", "!!")
            .replace("%", "!%")
            .replace("_", "!_");
    return "%" + escaped + "%";
  }

  /**
   * Converts an ISO date string ({@code yyyy-MM-dd}) of an entity to the typed value a {@code DATE}
   * column is bound with. Empty or missing means no date (SQL {@code NULL}).
   *
   * @param iso the ISO date, or null / empty
   * @return the date, or null
   * @throws java.time.format.DateTimeParseException if the text is not an ISO date
   */
  protected static LocalDate dateOrNull(String iso) {
    return iso == null || iso.isEmpty() ? null : LocalDate.parse(iso);
  }

  /**
   * Reads a {@code DECIMAL} column, mapping SQL {@code NULL} to {@link BigDecimal#ZERO} (what
   * {@code ResultSet.getDouble} used to return for it).
   *
   * @param rs the current row
   * @param column the column label
   * @return the value, never null
   * @throws SQLException if the column cannot be read
   */
  protected static BigDecimal decimalOrZero(ResultSet rs, String column) throws SQLException {
    BigDecimal value = rs.getBigDecimal(column);
    return value != null ? value : BigDecimal.ZERO;
  }
}
