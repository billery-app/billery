package de.billery.data;

/**
 * Utility class for database-related string operations. Provides methods for consistent handling of
 * null values and empty strings.
 */
public class DatabaseUtils {

  /**
   * Converts empty strings to null for database storage.
   *
   * @param value the string value to convert
   * @return null if the string is null or empty, otherwise the original string
   */
  public static String emptyToNull(String value) {
    return (value == null || value.trim().isEmpty()) ? null : value;
  }

  /**
   * Converts null values to empty strings for display.
   *
   * @param value the string value to convert
   * @return empty string if the value is null, otherwise the original string
   */
  public static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
