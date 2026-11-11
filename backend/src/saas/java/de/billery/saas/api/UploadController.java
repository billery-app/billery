package de.billery.saas.api;

import de.billery.saas.storage.TenantFileStorage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Serves a tenant's uploaded file to anyone holding its signed URL (the browser loads logos in an
 * {@code <img>} tag and cannot authenticate). The signature is checked first, so a wrong URL cannot
 * be used to find out which files exist. The bytes come from the configured object store (a
 * directory or an S3-compatible bucket), so any backend instance can serve any upload.
 */
@RestController
@Profile("saas")
public class UploadController {

  private static final Logger LOGGER = Logger.getLogger(UploadController.class.getName());

  private final TenantFileStorage storage;

  public UploadController(TenantFileStorage storage) {
    this.storage = storage;
  }

  /**
   * Returns the file.
   *
   * @param tenant the owning tenant, as in the URL
   * @param name the file name
   * @param sig the signature from the URL
   * @return the image; 403 for a missing or wrong signature, 404 if the file is gone, 503 if the
   *     object store cannot be read (logged; the browser shows no logo and may retry)
   */
  @GetMapping("/uploads/{tenant}/{name:.+}")
  public ResponseEntity<Resource> serve(
      @PathVariable int tenant,
      @PathVariable String name,
      @RequestParam(required = false) String sig) {
    if (!storage.isSigned(tenant, name, sig)) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    Optional<InputStream> file;
    try {
      file = storage.openSigned(tenant, name, sig);
    } catch (IOException e) {
      LOGGER.log(Level.WARNING, "Upload of tenant " + tenant + " could not be read", e);
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    if (file.isEmpty()) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.ok()
        .contentType(mediaType(name))
        .cacheControl(CacheControl.noCache().cachePrivate())
        // Streamed and closed by Spring after writing: no whole file on the heap per download.
        .body(new InputStreamResource(file.get()));
  }

  private static MediaType mediaType(String name) {
    String lower = name.toLowerCase(Locale.ROOT);
    if (lower.endsWith(".png")) {
      return MediaType.IMAGE_PNG;
    }
    if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
      return MediaType.IMAGE_JPEG;
    }
    if (lower.endsWith(".gif")) {
      return MediaType.IMAGE_GIF;
    }
    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
