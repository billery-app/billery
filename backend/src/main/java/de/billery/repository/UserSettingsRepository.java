package de.billery.repository;

import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Key/value application settings ({@code user_settings}), e.g. the document number prefixes per
 * document type. All statements run through the shared {@link JdbcClient}; database errors surface
 * as {@code DatabaseException} (see {@link BaseRepository}).
 */
@Repository
public class UserSettingsRepository extends BaseRepository {

  private static final String PREFIX_INVOICE = "prefix_invoice";

  private final JdbcClient jdbc;

  /**
   * Creates the repository.
   *
   * @param jdbc the shared JdbcClient
   */
  public UserSettingsRepository(JdbcClient jdbc) {
    super();
    this.jdbc = jdbc;
  }

  /**
   * Returns a setting's value.
   *
   * @param key the setting key
   * @param defaultValue returned when the key does not exist
   * @return the stored value (may be null if stored as NULL), or {@code defaultValue}
   */
  public String getSetting(String key, String defaultValue) {
    return run(
        "Failed to get setting " + key,
        () ->
            jdbc
                .sql("SELECT setting_value FROM user_settings WHERE setting_key = :key")
                .param("key", key)
                .query((rs, rowNum) -> rs.getString("setting_value"))
                .list()
                .stream()
                .findFirst()
                .orElse(defaultValue));
  }

  public String getPrefixForDocumentType(int documentTypeId) {
    String key =
        switch (documentTypeId) {
          case 1 -> PREFIX_INVOICE;
          case 2 -> "prefix_offer";
          case 3 -> "prefix_cost_calculation";
          case 4 -> "prefix_parted_invoice";
          case 5 -> "prefix_final_invoice";
          case 6 -> "prefix_credit_note";
          default -> PREFIX_INVOICE;
        };
    String defaultPrefix =
        switch (documentTypeId) {
          case 1 -> "RE";
          case 2 -> "AN";
          case 3 -> "KV";
          case 4 -> "TR";
          case 5 -> "SR";
          case 6 -> "ST";
          default -> "RE";
        };
    return getSetting(key, defaultPrefix);
  }

  /**
   * Inserts or overwrites a setting ({@code updated_at} is maintained by the column default and a
   * trigger). Uses the standard {@code MERGE}, which PostgreSQL and H2 both run.
   *
   * @param key the setting key
   * @param value the new value
   */
  public void updateSetting(String key, String value) {
    run(
        "Failed to update setting " + key,
        () ->
            jdbc.sql(
                    "MERGE INTO user_settings AS t"
                        + " USING (VALUES (CAST(:key AS VARCHAR(100)), CAST(:value AS TEXT)))"
                        + " AS s (setting_key, setting_value)"
                        + " ON t.setting_key = s.setting_key"
                        + " WHEN MATCHED THEN UPDATE SET setting_value = s.setting_value"
                        + " WHEN NOT MATCHED THEN INSERT (setting_key, setting_value)"
                        + " VALUES (s.setting_key, s.setting_value)")
                .param("key", key)
                .param("value", value)
                .update());
  }

  public void updateDocumentPrefixes(Map<Integer, String> prefixes) {
    for (Map.Entry<Integer, String> entry : prefixes.entrySet()) {
      String key =
          switch (entry.getKey()) {
            case 1 -> PREFIX_INVOICE;
            case 2 -> "prefix_offer";
            case 3 -> "prefix_cost_calculation";
            case 4 -> "prefix_parted_invoice";
            case 5 -> "prefix_final_invoice";
            case 6 -> "prefix_credit_note";
            default -> null;
          };
      if (key != null) {
        updateSetting(key, entry.getValue());
      }
    }
  }

  /**
   * Returns all settings.
   *
   * @return every key with its value
   */
  public Map<String, String> getAllSettings() {
    return run(
        "Failed to get all settings",
        () -> {
          Map<String, String> settings = new HashMap<>();
          jdbc.sql("SELECT setting_key, setting_value FROM user_settings")
              .query(
                  rs -> {
                    settings.put(rs.getString("setting_key"), rs.getString("setting_value"));
                  });
          return settings;
        });
  }
}
