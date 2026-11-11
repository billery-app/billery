package de.billery.data;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class DatabaseConfigTest {

  private static final Pattern SECRET_IMAGE_DIRECTIVE =
      Pattern.compile("\\b(?:DB_PASSWORD|DB_MIGRATION_PASSWORD)(?:=|\\b)");

  @Test
  void productionRequiresDatabaseCredentials() {
    assertThrows(
        IllegalStateException.class, () -> Database.validateConfiguration(Map.of(), false, false));
    assertThrows(
        IllegalStateException.class,
        () -> Database.validateConfiguration(Map.of("DB_USER", "billery"), false, false));
  }

  @Test
  void productionRejectsKnownDevelopmentPasswords() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Database.validateConfiguration(
                Map.of("DB_USER", "billery", "DB_PASSWORD", "billery123"), false, false));
    assertThrows(
        IllegalStateException.class,
        () ->
            Database.validateConfiguration(
                Map.of("DB_USER", "billery", "DB_PASSWORD", "rechnung123"), false, false));
    assertThrows(
        IllegalStateException.class,
        () ->
            Database.validateConfiguration(
                Map.of(
                    "DB_USER", "billery",
                    "DB_PASSWORD", "secure-app-password",
                    "DB_MIGRATION_PASSWORD", "billery123"),
                false,
                false));
  }

  @Test
  void localDefaultsAreAppliedOnlyWithTheExplicitDevelopmentProfile() {
    assertDoesNotThrow(() -> Database.validateConfiguration(Map.of(), true, false));
    Properties devCredentials = Database.connectionCredentials(Map.of(), true);
    assertEquals("billery", devCredentials.getProperty("user"));
    assertEquals("billery123", devCredentials.getProperty("password"));
    assertNull(Database.connectionCredentials(Map.of(), false));
  }

  @Test
  void desktopH2DoesNotRequirePostgresCredentials() {
    assertDoesNotThrow(
        () ->
            Database.validateConfiguration(
                Map.of("DB_URL", "jdbc:h2:file:./database"), false, true));
  }

  @Test
  void migrationCredentialsMustBeSuppliedAsAPair() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Database.validateConfiguration(
                Map.of(
                    "DB_USER", "app",
                    "DB_PASSWORD", "secure-app-password",
                    "DB_MIGRATION_USER", "migration"),
                false,
                false));
  }

  @Test
  void dockerfileDoesNotStoreDatabaseSecretsInImageMetadata() throws Exception {
    StringBuilder instruction = new StringBuilder();
    for (String line : Files.readAllLines(Path.of("Dockerfile"))) {
      String trimmed = line.strip();
      if (trimmed.startsWith("#")) {
        continue;
      }
      if (instruction.length() == 0) {
        if (trimmed.startsWith("ENV ")
            || trimmed.startsWith("ARG ")
            || trimmed.startsWith("LABEL ")) {
          instruction.append(trimmed);
        }
      } else {
        instruction.append(' ').append(trimmed);
      }
      if (instruction.length() > 0 && !trimmed.endsWith("\\")) {
        assertFalse(SECRET_IMAGE_DIRECTIVE.matcher(instruction).find());
        instruction.setLength(0);
      }
    }
  }
}
