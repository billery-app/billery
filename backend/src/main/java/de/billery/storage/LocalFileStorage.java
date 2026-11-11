package de.billery.storage;

import de.billery.config.AppConfig;
import de.billery.pdf.RestrictedUserAgent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * One flat upload directory ({@code FILES_PATH}), served under {@code /uploads/<name>}: the storage
 * of a self-hosted install and the desktop app.
 */
@Component
@Profile("!saas")
public class LocalFileStorage implements FileStorage {

  private final Path directory;

  /** Uses the configured upload directory ({@code FILES_PATH}). */
  public LocalFileStorage() {
    this(AppConfig.FILES_PATH);
  }

  /**
   * Uses the given directory.
   *
   * @param directory the upload directory
   */
  public LocalFileStorage(Path directory) {
    this.directory = directory.toAbsolutePath().normalize();
  }

  @Override
  public String save(byte[] content, String extension) throws IOException {
    Files.createDirectories(directory);
    String name = UUID.randomUUID() + "." + extension;
    Files.write(directory.resolve(name), content);
    return "/uploads/" + name;
  }

  @Override
  public Optional<byte[]> read(String uri) throws IOException {
    Path file = RestrictedUserAgent.resolveUpload(directory, uri);
    return file == null ? Optional.empty() : Optional.of(Files.readAllBytes(file));
  }
}
