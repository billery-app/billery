package de.billery.config;

import io.github.cdimascio.dotenv.Dotenv;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * File-system settings read from the process environment or an optional {@code .env} file in the
 * working directory (environment wins).
 *
 * <p>Only application-specific names are read. An earlier version read {@code PATH}, which always
 * resolved to the shell's executable search path instead of an application directory.
 */
public final class AppConfig {

  private static final Dotenv DOTENV = Dotenv.configure().ignoreIfMissing().load();

  /**
   * Directory for uploaded files (logos), served under {@code /uploads/**}. {@code FILES_PATH};
   * defaults to {@code ./uploads}. The desktop shell points it into the per-user app-data folder;
   * Docker deployments should mount a volume there.
   */
  public static final Path FILES_PATH = Paths.get(DOTENV.get("FILES_PATH", "./uploads"));

  private AppConfig() {}

  /**
   * Reads a setting from the environment or, if unset there, from {@code .env}.
   *
   * @param key the variable name
   * @return the value, or {@code null} if set in neither
   */
  public static String get(String key) {
    return DOTENV.get(key);
  }
}
