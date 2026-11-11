package de.billery.saas.storage;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Where the bytes of the tenants' uploads are kept: a directory ({@link DirectoryObjectStore}, the
 * default) or an S3-compatible object store ({@link S3ObjectStore}). Selected by {@code
 * BILLERY_FILES_STORE} ({@link ObjectStoreConfig}).
 *
 * <p>The store knows nothing about tenants or signatures: {@link TenantFileStorage} decides which
 * key may be read and only passes keys of the form {@code <tenantId>/<name>} with a validated name.
 */
public interface ObjectStore {

  /**
   * Stores an object, replacing one with the same key.
   *
   * @param key {@code <tenantId>/<name>}
   * @param content the bytes
   * @throws IOException if the store cannot be written
   */
  void put(String key, byte[] content) throws IOException;

  /**
   * Opens an object for reading. The stream is not buffered in memory: the upload endpoint copies
   * it straight to the response, so many parallel downloads do not hold whole files on the heap.
   *
   * @param key {@code <tenantId>/<name>}
   * @return the content, to be closed by the caller; empty if there is no such object
   * @throws IOException if the store cannot be read (unreachable, refused)
   */
  Optional<InputStream> open(String key) throws IOException;
}
