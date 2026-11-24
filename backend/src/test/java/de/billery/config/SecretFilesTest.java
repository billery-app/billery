package de.billery.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link SecretFiles}: complete, owner-only, never replacing an existing secret. */
class SecretFilesTest {

  @Test
  void theSecretAppearsCompleteAndReadableByItsOwnerOnly(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("a.key");

    SecretFiles.createOwnerOnly(file, "c2VjcmV0");

    assertEquals("c2VjcmV0", Files.readString(file));
    if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
      assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }
    try (var files = Files.list(dir)) {
      assertEquals(List.of(file), files.toList(), "no temporary file left behind");
    }
  }

  @Test
  void anExistingSecretIsNeverReplaced(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("a.key");
    SecretFiles.createOwnerOnly(file, "first");

    assertThrows(
        FileAlreadyExistsException.class, () -> SecretFiles.createOwnerOnly(file, "second"));

    assertEquals("first", Files.readString(file));
    try (var files = Files.list(dir)) {
      assertEquals(1, files.count(), "the second writer's temporary file is gone");
    }
  }
}
