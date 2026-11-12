package de.billery.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * An applied Flyway migration must stay byte for byte as it was: Flyway compares its checksum on
 * every start (comments included) and refuses to start when it changed. That happened once, when
 * only a comment of {@code V6__tenant_audit.sql} was edited. {@code applied-migrations.sha256}
 * lists every released migration with its SHA-256; this test compares the files with it.
 */
class AppliedMigrationsTest {

  private static final Path MANIFEST = Path.of("src/test/resources/applied-migrations.sha256");

  @Test
  void appliedMigrationsAreUnchanged() throws IOException, NoSuchAlgorithmException {
    List<String> entries =
        Files.readAllLines(MANIFEST).stream()
            .filter(line -> !line.isBlank() && !line.startsWith("#"))
            .toList();
    assertFalse(entries.isEmpty(), "the manifest lists no migration");
    for (String entry : entries) {
      String[] parts = entry.split("\\s+", 2);
      Path file = Path.of(parts[1].trim());
      assertTrue(Files.exists(file), file + " is listed as applied but missing");
      assertEquals(
          parts[0],
          sha256(Files.readAllBytes(file)),
          file
              + " was applied to databases and must not change (Flyway checks its checksum);"
              + " put the change into a new migration");
    }
  }

  private static String sha256(byte[] content) throws NoSuchAlgorithmException {
    return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
  }
}
