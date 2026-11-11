package de.billery.saas.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Objects as files below one directory ({@code <dir>/<tenantId>/<name>}). Works for one backend
 * instance or several that share the directory (a shared volume); instances with a disk of their
 * own need {@link S3ObjectStore}.
 */
public class DirectoryObjectStore implements ObjectStore {

  private final Path root;

  /**
   * Creates the store.
   *
   * @param root the directory that holds one subdirectory per tenant
   */
  public DirectoryObjectStore(Path root) {
    this.root = root.toAbsolutePath().normalize();
  }

  @Override
  public void put(String key, byte[] content) throws IOException {
    Path file = file(key);
    Files.createDirectories(file.getParent());
    Files.write(file, content);
  }

  @Override
  public Optional<InputStream> open(String key) throws IOException {
    Path file = file(key);
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    try {
      return Optional.of(Files.newInputStream(file));
    } catch (NoSuchFileException e) {
      return Optional.empty();
    }
  }

  /**
   * Maps a key to its file and refuses one that would leave the directory (a second guard; the
   * caller already validates the name).
   */
  private Path file(String key) {
    Path file = root.resolve(key).normalize();
    if (!file.startsWith(root) || file.equals(root)) {
      throw new IllegalArgumentException("Invalid object key");
    }
    return file;
  }
}
