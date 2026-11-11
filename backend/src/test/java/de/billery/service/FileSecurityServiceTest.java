package de.billery.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.web.multipart.MultipartFile;

class FileSecurityServiceTest {

  private final FileSecurityService service = new FileSecurityService();

  @Test
  void rejectsUploadsLargerThanTheConfiguredLimit() {
    byte[] oversized = new byte[(int) FileSecurityService.MAX_FILE_SIZE_BYTES + 1];

    assertFalse(service.validateFile(new FakeMultipartFile("document.docx", oversized)).isValid());
  }

  @Test
  void rejectsMalformedZipArchives() {
    byte[] malformed = "not a zip archive".getBytes(StandardCharsets.UTF_8);

    assertFalse(service.validateArchive(malformed, "docx").isValid());
  }

  @Test
  void rejectsArchivesThatDoNotMatchTheDocumentExtension() throws IOException {
    byte[] odtEntries = zipWithEntry("content.xml", "<text/>".getBytes(StandardCharsets.UTF_8));

    assertFalse(service.validateArchive(odtEntries, "docx").isValid());
  }

  @Test
  void rejectsAnExcessiveCompressionRatioBelowTheExpandedSizeLimit() throws IOException {
    byte[] archive = zipWithRepeatedPayload("word/document.xml", 10L * 1024 * 1024);

    assertTrue(archive.length < 10L * 1024 * 1024 / FileSecurityService.MAX_COMPRESSION_RATIO);
    assertFalse(service.validateArchive(archive, "docx").isValid());
  }

  @Test
  void rejectsHighExpansionArchivesBeforeTheParserReadsThem() throws IOException {
    byte[] archive = zipWithRepeatedPayload("word/document.xml", 200L * 1024 * 1024);

    assertTrue(archive.length < FileSecurityService.MAX_FILE_SIZE_BYTES);
    assertFalse(service.validateArchive(archive, "docx").isValid());
  }

  @Test
  void rejectsManySmallEntriesThatEachExceedTheRatioLimit() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] zeros = new byte[40 * 1024];
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      writeEntry(zip, "[Content_Types].xml", "<Types/>");
      writeEntry(zip, "word/document.xml", "<document/>");
      for (int i = 0; i < 400; i++) {
        zip.putNextEntry(new ZipEntry("word/bomb" + i + ".bin"));
        zip.write(zeros);
        zip.closeEntry();
      }
    }
    byte[] archive = bytes.toByteArray();

    assertTrue(400L * zeros.length < FileSecurityService.MAX_UNCOMPRESSED_BYTES);
    assertTrue(archive.length * FileSecurityService.MAX_COMPRESSION_RATIO < 400L * zeros.length);
    assertFalse(service.validateArchive(archive, "docx").isValid());
  }

  @Test
  void acceptsAMinimalValidDocx() throws IOException {
    assertTrue(service.validateArchive(docxWithExtraEntries(0, null), "docx").isValid());
  }

  @Test
  void acceptsEntryNamesContainingDoubleDotsInsideASegment() throws IOException {
    byte[] archive = docxWithExtraEntries(0, "word/media/image..1.png");

    assertTrue(service.validateArchive(archive, "docx").isValid());
  }

  @Test
  void rejectsZipSlipEntryNamesInOtherwiseValidDocx() throws IOException {
    byte[] archive = docxWithExtraEntries(0, "word/../../etc/passwd");

    assertFalse(service.validateArchive(archive, "docx").isValid());
  }

  @Test
  void rejectsAbsolutePathEntryNamesInOtherwiseValidDocx() throws IOException {
    byte[] archive = docxWithExtraEntries(0, "/etc/passwd");

    assertFalse(service.validateArchive(archive, "docx").isValid());
  }

  @Test
  void acceptsExactlyTheMaximumNumberOfEntries() throws IOException {
    byte[] archive = docxWithExtraEntries(FileSecurityService.MAX_ARCHIVE_ENTRIES - 2, null);

    assertTrue(service.validateArchive(archive, "docx").isValid());
  }

  @Test
  void rejectsOneEntryMoreThanTheConfiguredLimit() throws IOException {
    byte[] archive = docxWithExtraEntries(FileSecurityService.MAX_ARCHIVE_ENTRIES - 1, null);

    assertFalse(service.validateArchive(archive, "docx").isValid());
  }

  private byte[] docxWithExtraEntries(int fillerEntries, String namedEntry) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      writeEntry(zip, "[Content_Types].xml", "<Types/>");
      writeEntry(zip, "word/document.xml", "<document/>");
      for (int i = 0; i < fillerEntries; i++) {
        writeEntry(zip, "word/filler" + i + ".xml", "x");
      }
      if (namedEntry != null) {
        writeEntry(zip, namedEntry, "x");
      }
    }
    return bytes.toByteArray();
  }

  private void writeEntry(ZipOutputStream zip, String name, String contents) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(contents.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private byte[] zipWithEntry(String name, byte[] contents) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry(name));
      zip.write(contents);
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  private byte[] zipWithRepeatedPayload(String name, long uncompressedBytes) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    byte[] buffer = new byte[8 * 1024];
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      writeEntry(zip, "[Content_Types].xml", "<Types/>");
      zip.putNextEntry(new ZipEntry(name));
      long remaining = uncompressedBytes;
      while (remaining > 0) {
        int bytesToWrite = (int) Math.min(buffer.length, remaining);
        zip.write(buffer, 0, bytesToWrite);
        remaining -= bytesToWrite;
      }
      zip.closeEntry();
    }
    return bytes.toByteArray();
  }

  // spring-boot-starter-test (and MockMultipartFile) is only on the saas profile's test
  // classpath; this module's own test source set stays dependency-free.
  private static final class FakeMultipartFile implements MultipartFile {
    private final String filename;
    private final byte[] content;

    private FakeMultipartFile(String filename, byte[] content) {
      this.filename = filename;
      this.content = content;
    }

    @Override
    public String getName() {
      return "file";
    }

    @Override
    public String getOriginalFilename() {
      return filename;
    }

    @Override
    public String getContentType() {
      return null;
    }

    @Override
    public boolean isEmpty() {
      return content.length == 0;
    }

    @Override
    public long getSize() {
      return content.length;
    }

    @Override
    public byte[] getBytes() {
      return content;
    }

    @Override
    public InputStream getInputStream() {
      return new ByteArrayInputStream(content);
    }

    @Override
    public void transferTo(File dest) {
      throw new UnsupportedOperationException();
    }
  }
}
