package de.billery.storage;

import java.io.IOException;
import java.util.Optional;

/**
 * Where uploaded files (company logos) live and how they are addressed.
 *
 * <p>A stored file is referenced by an application-relative URL such as {@code /uploads/<name>}
 * that ends up inside templates and in the rendered HTML snapshots. Self-host keeps one flat
 * directory ({@link LocalFileStorage}); the SaaS profile keeps the files per tenant (in a directory
 * or an S3-compatible object store) and signs the URLs (see {@code
 * docs/architecture/multi-tenancy.md}). The PDF renderer reads files only through {@link #read}, so
 * it can never load a file of another tenant.
 */
public interface FileStorage {

  /**
   * Stores a new file.
   *
   * @param content the bytes (already validated by the caller)
   * @param extension the file extension without the dot ({@code png}, {@code jpg}, {@code gif})
   * @return the application-relative URL to store in templates, e.g. {@code /uploads/<name>}
   * @throws IOException if the file cannot be written
   */
  String save(byte[] content, String extension) throws IOException;

  /**
   * Reads a stored file by its URL (absolute or relative; host, port and query are ignored).
   * Returns bytes rather than a path so that the files can live in an object store as well as on a
   * disk.
   *
   * @param uri a URL taken from a template
   * @return the content; empty if the URL is not a stored file of the active tenant or the file is
   *     missing
   * @throws IOException if the storage cannot be read
   */
  Optional<byte[]> read(String uri) throws IOException;
}
