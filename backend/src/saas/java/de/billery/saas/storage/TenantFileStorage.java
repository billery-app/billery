package de.billery.saas.storage;

import de.billery.saas.tenant.TenantContext;
import de.billery.storage.FileStorage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The tenants' uploads: object key {@code <tenantId>/<name>} in the configured {@link ObjectStore}
 * (a directory or an S3-compatible bucket, {@link ObjectStoreConfig}); the stored URL is {@code
 * /uploads/<tenantId>/<name>?sig=...}.
 *
 * <p>Two access paths, both tenant-bound and independent of the store: {@link #read} (the PDF
 * renderer, inside a request) only returns files of the <b>active</b> tenant, so a template cannot
 * make the server read another tenant's logo into its PDF; {@link #openSigned} (the HTTP endpoint)
 * needs a valid signature for exactly that tenant and file. Only validated names reach the store.
 */
@Component
@Profile("saas")
public class TenantFileStorage implements FileStorage {

  /** {@code /uploads/<tenant>/<file>}: digits, then a single path segment without separators. */
  private static final Pattern UPLOAD_PATH = Pattern.compile("^/uploads/(\\d+)/([A-Za-z0-9._-]+)$");

  /** A file name: one path segment, no separators, not {@code .} or {@code ..}. */
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._-]+");

  private final ObjectStore store;
  private final UploadSigner signer;

  /**
   * Creates the storage.
   *
   * @param secret the secret the URLs are signed with ({@code billery.files.secret})
   * @param store where the bytes are kept
   */
  public TenantFileStorage(@Value("${billery.files.secret:}") String secret, ObjectStore store) {
    this.signer = new UploadSigner(secret);
    this.store = store;
  }

  @Override
  public String save(byte[] content, String extension) throws IOException {
    Integer tenant = TenantContext.tenantId();
    if (tenant == null) {
      throw new IllegalStateException("No tenant is active");
    }
    String name = UUID.randomUUID() + "." + extension;
    store.put(key(tenant, name), content);
    return "/uploads/" + tenant + "/" + name + "?sig=" + signer.sign(tenant, name);
  }

  @Override
  public Optional<byte[]> read(String uri) throws IOException {
    Integer active = TenantContext.tenantId();
    if (active == null) {
      return Optional.empty();
    }
    Matcher matcher = match(uri);
    if (matcher == null || !Integer.toString(active).equals(matcher.group(1))) {
      return Optional.empty();
    }
    Optional<InputStream> file = existing(active, matcher.group(2));
    if (file.isEmpty()) {
      return Optional.empty();
    }
    // The renderer needs the whole logo (at most 2 MB, the upload limit) to size and embed it.
    try (InputStream in = file.get()) {
      return Optional.of(in.readAllBytes());
    }
  }

  /**
   * Opens a file for the HTTP endpoint, which streams it to the browser.
   *
   * @param tenantId the tenant named in the URL
   * @param name the file name named in the URL
   * @param signature the signature from the URL
   * @return the content, to be closed by the caller; empty if the signature is wrong or the file
   *     does not exist
   * @throws IOException if the store cannot be read
   */
  public Optional<InputStream> openSigned(int tenantId, String name, String signature)
      throws IOException {
    if (!signer.verify(tenantId, name, signature)) {
      return Optional.empty();
    }
    return existing(tenantId, name);
  }

  /**
   * Tells whether a signature is valid for a file (checked before the file's existence is
   * revealed).
   *
   * @param tenantId the tenant named in the URL
   * @param name the file name named in the URL
   * @param signature the signature from the URL
   * @return true if valid
   */
  public boolean isSigned(int tenantId, String name, String signature) {
    return signer.verify(tenantId, name, signature);
  }

  private Optional<InputStream> existing(int tenantId, String name) throws IOException {
    if (!NAME.matcher(name).matches() || ".".equals(name) || "..".equals(name)) {
      return Optional.empty();
    }
    return store.open(key(tenantId, name));
  }

  private static String key(int tenantId, String name) {
    return tenantId + "/" + name;
  }

  private static Matcher match(String uri) {
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
    return matcher.matches() ? matcher : null;
  }
}
