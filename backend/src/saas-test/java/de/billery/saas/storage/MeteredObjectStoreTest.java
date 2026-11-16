package de.billery.saas.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@link MeteredObjectStore}: failures are counted per operation, successes are not. */
class MeteredObjectStoreTest {

  @TempDir Path dir;

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

  private double errors(String operation) {
    return meters.get("billery.files.store.errors").tag("operation", operation).counter().count();
  }

  @Test
  void bothCountersExistFromTheStartSoTheFirstErrorIsSeen() {
    new MeteredObjectStore(new DirectoryObjectStore(dir), meters);

    assertEquals(0, errors("put"));
    assertEquals(0, errors("open"));
  }

  @Test
  void aWorkingStoreCountsNothingAndPassesTheBytesThrough() throws IOException {
    ObjectStore store = new MeteredObjectStore(new DirectoryObjectStore(dir), meters);

    store.put("1/logo.png", new byte[] {1, 2, 3});
    try (InputStream in = store.open("1/logo.png").orElseThrow()) {
      assertArrayEquals(new byte[] {1, 2, 3}, in.readAllBytes());
    }
    assertEquals(Optional.empty(), store.open("1/missing.png"), "missing is no error");

    assertEquals(0, errors("put"));
    assertEquals(0, errors("open"));
  }

  @Test
  void aFailingStoreIsCountedAndTheFailureStillReachesTheCaller() {
    ObjectStore broken =
        new ObjectStore() {
          @Override
          public void put(String key, byte[] content) throws IOException {
            throw new IOException("bucket unreachable");
          }

          @Override
          public Optional<InputStream> open(String key) {
            throw new IllegalStateException("refused");
          }
        };
    ObjectStore store = new MeteredObjectStore(broken, meters);

    assertThrows(IOException.class, () -> store.put("1/a.png", new byte[] {1}));
    assertThrows(IOException.class, () -> store.put("1/b.png", new byte[] {1}));
    assertThrows(IllegalStateException.class, () -> store.open("1/a.png"));

    assertEquals(2, errors("put"));
    assertEquals(1, errors("open"));
  }
}
