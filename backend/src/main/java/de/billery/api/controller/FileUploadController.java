package de.billery.api.controller;

import de.billery.storage.FileStorage;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

@RestController
@RequestMapping("/api/upload")
public class FileUploadController {

  private static final Logger LOGGER = Logger.getLogger(FileUploadController.class.getName());
  private static final long MAX_FILE_SIZE = 2 * 1024 * 1024;
  private static final Set<String> ALLOWED_EXTENSIONS = Set.of("png", "jpg", "jpeg", "gif");

  private final FileStorage storage;

  public FileUploadController(FileStorage storage) {
    this.storage = storage;
  }

  @PostMapping
  public ResponseEntity<Map<String, String>> uploadFile(@RequestParam("file") MultipartFile file) {
    if (file.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("message", "Datei ist leer"));
    }

    if (file.getSize() > MAX_FILE_SIZE) {
      return ResponseEntity.badRequest().body(Map.of("message", "Datei zu groß (max. 2 MB)"));
    }

    String originalFilename = file.getOriginalFilename();
    if (originalFilename == null || originalFilename.isEmpty()) {
      return ResponseEntity.badRequest().body(Map.of("message", "Ungültiger Dateiname"));
    }

    String extension = "";
    int dotIndex = originalFilename.lastIndexOf('.');
    if (dotIndex > 0) {
      extension = originalFilename.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    if (!ALLOWED_EXTENSIONS.contains(extension)) {
      return ResponseEntity.badRequest()
          .body(Map.of("message", "Nur PNG, JPG, JPEG und GIF sind erlaubt"));
    }

    try {
      byte[] content = file.getBytes();
      String detected = detectImageExtension(content);
      if (detected == null || !detected.equals(canonicalExtension(extension))) {
        return ResponseEntity.badRequest()
            .body(Map.of("message", "Dateiinhalt passt nicht zur Dateiendung oder ist kein Bild"));
      }

      // The stored extension comes from the detected content, never from the client's filename.
      String relativeUrl = storage.save(content, detected);
      String fileUrl =
          ServletUriComponentsBuilder.fromCurrentContextPath().toUriString() + relativeUrl;
      return ResponseEntity.ok(Map.of("url", fileUrl));
    } catch (IOException e) {
      LOGGER.log(Level.SEVERE, "File upload failed", e);
      return ResponseEntity.internalServerError()
          .body(Map.of("message", "Upload fehlgeschlagen: " + e.getMessage()));
    }
  }

  /**
   * Maps a filename extension to the canonical extension of its image format ({@code jpeg} becomes
   * {@code jpg}).
   *
   * @param extension lower-case extension without the dot
   * @return the canonical extension
   */
  private static String canonicalExtension(String extension) {
    return "jpeg".equals(extension) ? "jpg" : extension;
  }

  /**
   * Identifies a raster image by its magic bytes. The upload trusts the content, not the filename
   * or the client-sent content type, so an HTML or SVG document renamed to {@code .png} is
   * rejected.
   *
   * @param content the uploaded bytes
   * @return {@code png}, {@code jpg} or {@code gif}; {@code null} if the bytes are none of these
   */
  static String detectImageExtension(byte[] content) {
    if (startsWith(content, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return "png";
    }
    if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
      return "jpg";
    }
    if (startsWith(content, 'G', 'I', 'F', '8', '7', 'a')
        || startsWith(content, 'G', 'I', 'F', '8', '9', 'a')) {
      return "gif";
    }
    return null;
  }

  private static boolean startsWith(byte[] content, int... prefix) {
    if (content.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((content[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }
}
