package de.billery.service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** Service for validating uploaded document files. Simplified for local Docker app usage. */
@Service
public class FileSecurityService {

  static final long MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024;
  static final int MAX_ARCHIVE_ENTRIES = 2_000;
  static final long MAX_UNCOMPRESSED_BYTES = 20L * 1024 * 1024;
  static final long MAX_COMPRESSION_RATIO = 100;
  static final long MAX_ARCHIVE_SCAN_NANOS = 30_000_000_000L;
  private static final int BUFFER_SIZE = 8 * 1024;
  private static final int ODT_MIMETYPE_MAX_BYTES = 128;
  private static final String INVALID_ARCHIVE_MESSAGE = "Invalid document archive";
  private static final Set<String> ALLOWED_EXTENSIONS = Set.of("docx", "odt");

  /** Basic file validation for local app */
  public ValidationResult validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      return ValidationResult.error("File is empty or null");
    }

    // 1. Check file size
    if (file.getSize() > MAX_FILE_SIZE_BYTES) {
      return ValidationResult.error("File too large. Maximum size: 2MB");
    }

    // 2. Check file extension
    String filename = file.getOriginalFilename();
    if (filename == null || !hasValidExtension(filename)) {
      return ValidationResult.error("Invalid file extension. Allowed: .docx, .odt");
    }

    return ValidationResult.success();
  }

  /** Checks ZIP structure and decompression limits before a document parser opens the archive. */
  public ValidationResult validateArchive(byte[] archive, String extension) {
    if (archive == null || archive.length == 0 || archive.length > MAX_FILE_SIZE_BYTES) {
      return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
    }
    if (!hasEndOfCentralDirectory(archive)) {
      return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
    }

    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
      return inspectArchive(zip, extension);
    } catch (Exception e) {
      return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
    }
  }

  private ValidationResult inspectArchive(ZipInputStream zip, String extension) throws Exception {
    long startedAt = System.nanoTime();
    long totalUncompressed = 0;
    int entryCount = 0;
    Set<String> entryNames = new HashSet<>();
    boolean hasContentTypes = false;
    boolean hasDocument = false;
    boolean hasContentXml = false;
    String odtMimeType = null;
    byte[] buffer = new byte[BUFFER_SIZE];

    ZipEntry entry = zip.getNextEntry();
    while (entry != null) {
      checkScanTime(startedAt);
      entryCount++;
      if (entryCount > MAX_ARCHIVE_ENTRIES
          || !entryNames.add(entry.getName())
          || hasUnsafeEntryName(entry.getName())) {
        return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
      }
      if ("odt".equals(extension) && "mimetype".equals(entry.getName()) && entryCount != 1) {
        return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
      }
      if ("[Content_Types].xml".equals(entry.getName())) {
        hasContentTypes = true;
      }
      if ("word/document.xml".equals(entry.getName())) {
        hasDocument = true;
      }
      if ("content.xml".equals(entry.getName())) {
        hasContentXml = true;
      }

      EntryScan scan = scanEntry(zip, entry, startedAt, totalUncompressed, buffer);
      totalUncompressed = scan.totalUncompressed;
      if ("mimetype".equals(entry.getName())) {
        odtMimeType = scan.capturedContent;
      }
      zip.closeEntry();
      checkEntryRatio(entry);
      entry = zip.getNextEntry();
    }

    if (entryCount == 0) {
      return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
    }
    if ("docx".equals(extension) && (!hasContentTypes || !hasDocument)) {
      return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
    }
    if ("odt".equals(extension)
        && (!hasContentXml || !"application/vnd.oasis.opendocument.text".equals(odtMimeType))) {
      return ValidationResult.error(INVALID_ARCHIVE_MESSAGE);
    }
    return ValidationResult.success();
  }

  private EntryScan scanEntry(
      ZipInputStream zip, ZipEntry entry, long startedAt, long totalUncompressed, byte[] buffer)
      throws Exception {
    long entryUncompressed = 0;
    long runningTotal = totalUncompressed;
    StringBuilder mimeType = new StringBuilder();
    int bytesRead = zip.read(buffer);
    while (bytesRead != -1) {
      checkScanTime(startedAt);
      entryUncompressed += bytesRead;
      runningTotal += bytesRead;
      if (runningTotal > MAX_UNCOMPRESSED_BYTES) {
        throw new IllegalArgumentException(INVALID_ARCHIVE_MESSAGE);
      }
      if ("mimetype".equals(entry.getName()) && entryUncompressed <= ODT_MIMETYPE_MAX_BYTES) {
        mimeType.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
      }
      bytesRead = zip.read(buffer);
    }
    return new EntryScan(runningTotal, mimeType.toString());
  }

  // ZipInputStream only knows an entry's sizes once it has been read completely, and its input
  // read-ahead makes any compressed-byte counter taken while inflating unreliable. The running
  // 20 MB total in scanEntry is what bounds the work for a bomb; this is the exact per-entry check.
  private void checkEntryRatio(ZipEntry entry) {
    long uncompressedSize = entry.getSize();
    long compressedSize = entry.getCompressedSize();
    if (uncompressedSize < 0 || compressedSize < 0) {
      throw new IllegalArgumentException(INVALID_ARCHIVE_MESSAGE);
    }
    if (uncompressedSize > 0
        && (compressedSize == 0 || uncompressedSize > compressedSize * MAX_COMPRESSION_RATIO)) {
      throw new IllegalArgumentException(INVALID_ARCHIVE_MESSAGE);
    }
  }

  /** Rejects zip-slip style entry names (path traversal or absolute paths). */
  private boolean hasUnsafeEntryName(String name) {
    if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\")) {
      return true;
    }
    for (String segment : name.split("[/\\\\]")) {
      if ("..".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  private void checkScanTime(long startedAt) {
    if (System.nanoTime() - startedAt > MAX_ARCHIVE_SCAN_NANOS) {
      throw new IllegalArgumentException(INVALID_ARCHIVE_MESSAGE);
    }
  }

  private boolean hasEndOfCentralDirectory(byte[] archive) {
    int earliestPosition = Math.max(0, archive.length - 22 - 65_535);
    for (int i = archive.length - 22; i >= earliestPosition; i--) {
      if (archive[i] == 'P'
          && archive[i + 1] == 'K'
          && archive[i + 2] == 5
          && archive[i + 3] == 6) {
        int commentLength = unsignedShort(archive, i + 20);
        if (i + 22 + commentLength == archive.length) {
          return true;
        }
      }
    }
    return false;
  }

  private int unsignedShort(byte[] bytes, int offset) {
    return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
  }

  private static class EntryScan {
    private final long totalUncompressed;
    private final String capturedContent;

    private EntryScan(long totalUncompressed, String capturedContent) {
      this.totalUncompressed = totalUncompressed;
      this.capturedContent = capturedContent;
    }
  }

  /** Validates file extension against allowed list */
  private boolean hasValidExtension(String filename) {
    String extension = FilenameUtils.getExtension(filename).toLowerCase(Locale.ROOT);
    return ALLOWED_EXTENSIONS.contains(extension);
  }

  /** Result class for validation operations */
  public static class ValidationResult {
    private final boolean valid;
    private final String message;

    private ValidationResult(boolean valid, String message) {
      this.valid = valid;
      this.message = message;
    }

    public static ValidationResult success() {
      return new ValidationResult(true, null);
    }

    public static ValidationResult error(String message) {
      return new ValidationResult(false, message);
    }

    public boolean isValid() {
      return valid;
    }

    public String getMessage() {
      return message;
    }
  }
}
