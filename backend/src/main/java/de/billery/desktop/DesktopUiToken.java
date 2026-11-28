package de.billery.desktop;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The per-launch secret of the app window ({@code DESKTOP_UI_TOKEN}): the Tauri shell generates it,
 * passes it to the backend and hands it to its own window only (in the URL fragment, which the SPA
 * keeps in {@code sessionStorage} and sends as {@code X-Desktop-Token}). Other programs on the
 * computer reach 127.0.0.1 too; the desktop-only endpoints (backups, export into a folder) need the
 * secret. Without a configured token (the JAR started without the shell) every request is refused.
 */
final class DesktopUiToken {

  /** The header that carries the window's per-launch secret. */
  static final String HEADER = "X-Desktop-Token";

  private final String token;

  /**
   * @param token the secret; empty or null refuses every request
   */
  DesktopUiToken(String token) {
    this.token = token == null ? "" : token;
  }

  /**
   * @return the secret the shell started the backend with ({@code DESKTOP_UI_TOKEN})
   */
  static DesktopUiToken fromEnvironment() {
    return new DesktopUiToken(System.getenv().getOrDefault("DESKTOP_UI_TOKEN", ""));
  }

  /**
   * Whether a request comes from the app's own window (constant-time comparison).
   *
   * @param presented the value of {@link #HEADER}
   * @return true for the window's secret
   */
  boolean matches(String presented) {
    return !token.isEmpty()
        && presented != null
        && MessageDigest.isEqual(
            token.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
  }
}
