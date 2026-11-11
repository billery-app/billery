package de.billery.pdf;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextUserAgent;

/**
 * Resource loader for Flying Saucer that never touches the network or arbitrary files.
 *
 * <p>Template content (logo URL, custom CSS with {@code url(...)} / {@code @import}) is user input.
 * The default user agent would fetch any {@code http(s)://} or {@code file:} URI it contains, which
 * allows server-side request forgery against internal hosts and reading local files. This agent
 * only serves:
 *
 * <ul>
 *   <li>embedded {@code data:} images (handled by the parent class before {@link #openStream}),
 *   <li>resources on the application's own classpath, e.g. Flying Saucer's default XHTML stylesheet
 *       (without it {@code <style>} content would be printed as text): only a URI that is exactly
 *       the URL the class loader returns for that resource name,
 *   <li>uploaded files, read through the configured {@code FileStorage} (self-host: any URI whose
 *       path is {@code /uploads/<file name>}, read from the upload directory; SaaS: only files of
 *       the active tenant, from a directory or an object store). The host and port of the stored
 *       URL are ignored, so a logo keeps working when the server's address changes.
 * </ul>
 *
 * Everything else is refused; Flying Saucer then renders the document without that resource.
 */
public class RestrictedUserAgent extends ITextUserAgent {

  /** A single path segment of an uploaded file: no separators, no {@code ..}. */
  private static final Pattern UPLOAD_PATH = Pattern.compile("^/uploads/([A-Za-z0-9._-]+)$");

  private final UploadReader uploads;

  /** Reads an uploaded file by its URI; the PDF generator passes {@code FileStorage::read}. */
  @FunctionalInterface
  public interface UploadReader {

    /**
     * Reads the bytes of an uploaded file.
     *
     * @param uri the URI from the document
     * @return the bytes; empty when the URI is not an upload the renderer may read
     * @throws IOException when the storage cannot be read
     */
    Optional<byte[]> read(String uri) throws IOException;
  }

  /**
   * @param outputDevice the renderer's output device
   * @param dotsPerPixel the renderer's dots per pixel
   * @param uploadDirectory directory that holds the uploaded files ({@code FILES_PATH})
   */
  public RestrictedUserAgent(
      ITextOutputDevice outputDevice, int dotsPerPixel, Path uploadDirectory) {
    this(
        outputDevice,
        dotsPerPixel,
        uri -> {
          Path file = resolveUpload(uploadDirectory.toAbsolutePath().normalize(), uri);
          return file == null ? Optional.empty() : Optional.of(Files.readAllBytes(file));
        });
  }

  /**
   * @param outputDevice the renderer's output device
   * @param dotsPerPixel the renderer's dots per pixel
   * @param uploads reads an uploaded file the renderer may use, or returns {@code null} (the
   *     configured {@code FileStorage}, which knows about tenants)
   */
  public RestrictedUserAgent(
      ITextOutputDevice outputDevice, int dotsPerPixel, UploadReader uploads) {
    super(outputDevice, dotsPerPixel);
    this.uploads = uploads;
  }

  /**
   * Opens a classpath resource or an uploaded file, or refuses the URI.
   *
   * @param uri the resolved URI Flying Saucer wants to load
   * @return a stream of the resource or uploaded file
   * @throws FileNotFoundException if the URI is not an upload or the file does not exist
   */
  @Override
  protected InputStream openStream(String uri) throws IOException {
    URL classpathResource = resolveClasspathResource(uri);
    if (classpathResource != null) {
      return classpathResource.openStream();
    }
    Optional<byte[]> upload = readUpload(uri);
    if (upload.isEmpty()) {
      throw new FileNotFoundException("Resource blocked (only uploads are allowed): " + uri);
    }
    return new ByteArrayInputStream(upload.get());
  }

  /**
   * Maps a URI to a resource of the application's classpath. The resource name is the part after
   * the last {@code !/} of a {@code jar:} URL (or after {@code /resources/} of an exploded {@code
   * file:} classpath); the URI is only accepted when the class loader returns exactly this URL for
   * that name, so arbitrary {@code jar:} or {@code file:} URIs are still refused.
   *
   * @param uri any URI
   * @return the classpath resource, or {@code null}
   */
  static URL resolveClasspathResource(String uri) {
    if (uri == null || !(uri.startsWith("jar:") || uri.startsWith("file:"))) {
      return null;
    }
    String name = null;
    int bang = uri.lastIndexOf("!/");
    if (bang >= 0) {
      name = uri.substring(bang + 2);
    } else {
      int resources = uri.indexOf("/resources/");
      if (resources >= 0) {
        name = uri.substring(resources + 1);
      }
    }
    if (name == null || name.isEmpty() || name.contains("..")) {
      return null;
    }
    ClassLoader loader = Thread.currentThread().getContextClassLoader();
    URL resource = loader == null ? null : loader.getResource(name);
    return resource != null && resource.toExternalForm().equals(uri) ? resource : null;
  }

  /**
   * Reads an uploaded file.
   *
   * @param uri any URI
   * @return the bytes; empty when the URI is not an upload or the file is missing
   * @throws IOException when the storage cannot be read
   */
  Optional<byte[]> readUpload(String uri) throws IOException {
    return uploads.read(uri);
  }

  /**
   * Maps a URI to a file inside a flat upload directory (the self-host layout).
   *
   * @param directory the normalized absolute upload directory
   * @param uri any URI
   * @return the existing regular file, or {@code null}
   */
  public static Path resolveUpload(Path directory, String uri) {
    Path root = directory.toAbsolutePath().normalize();
    if (uri == null) {
      return null;
    }
    String path;
    try {
      path = new URI(uri).getPath();
    } catch (URISyntaxException e) {
      return null;
    }
    if (path == null) {
      return null;
    }
    Matcher matcher = UPLOAD_PATH.matcher(path);
    if (!matcher.matches() || "..".equals(matcher.group(1)) || ".".equals(matcher.group(1))) {
      return null;
    }
    Path file = root.resolve(matcher.group(1)).normalize();
    if (!file.startsWith(root) || !Files.isRegularFile(file)) {
      return null;
    }
    return file;
  }
}
