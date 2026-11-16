package de.billery.saas.storage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Counts the failures of the object store ({@code billery.files.store.errors}, tag {@code
 * operation} = {@code put} / {@code open}), so a bucket that is unreachable, refuses the key or a
 * full upload disk shows up in the metrics and the alert {@code BilleryFileStoreErrors}, not only
 * as server errors of single uploads and PDFs with a logo. The readiness check does not depend on
 * the store on purpose: without it the application still serves everything but the files.
 *
 * <p>Both counters exist from the start with 0: Prometheus' {@code increase()} does not see the
 * first error of a series that only appears with it. A failure while a caller reads an opened
 * stream is not counted here (the bytes go straight to the response).
 */
final class MeteredObjectStore implements ObjectStore {

  private final ObjectStore store;
  private final Counter putErrors;
  private final Counter openErrors;

  /**
   * Wraps {@code store}.
   *
   * @param store the configured store
   * @param meters where the counters are registered
   */
  MeteredObjectStore(ObjectStore store, MeterRegistry meters) {
    this.store = store;
    this.putErrors = errors(meters, "put");
    this.openErrors = errors(meters, "open");
  }

  private static Counter errors(MeterRegistry meters, String operation) {
    return Counter.builder("billery.files.store.errors")
        .description("Failed operations of the store for uploaded files")
        .tag("operation", operation)
        .register(meters);
  }

  @Override
  public void put(String key, byte[] content) throws IOException {
    try {
      store.put(key, content);
    } catch (IOException | RuntimeException e) {
      putErrors.increment();
      throw e;
    }
  }

  @Override
  public Optional<InputStream> open(String key) throws IOException {
    try {
      return store.open(key);
    } catch (IOException | RuntimeException e) {
      openErrors.increment();
      throw e;
    }
  }
}
