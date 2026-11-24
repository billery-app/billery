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

  @Test
  void ofManyConcurrentWritersExactlyOneWins(@TempDir Path dir) throws Exception {
    Path file = dir.resolve("race.key");
    int writers = 16;
    var start = new java.util.concurrent.CountDownLatch(1);
    var winners = new java.util.concurrent.atomic.AtomicInteger();
    try (var pool = java.util.concurrent.Executors.newFixedThreadPool(writers)) {
      for (int i = 0; i < writers; i++) {
        String content = "writer" + i;
        pool.submit(
            () -> {
              start.await();
              try {
                SecretFiles.createOwnerOnly(file, content);
                winners.incrementAndGet();
              } catch (FileAlreadyExistsException e) {
                // lost the race: reads the winner's key
              }
              return null;
            });
      }
      start.countDown();
    }
    assertEquals(1, winners.get(), "exactly one writer created the file");
    try (var files = Files.list(dir)) {
      assertEquals(List.of(file), files.toList(), "no temporary file left behind");
    }
  }
}
