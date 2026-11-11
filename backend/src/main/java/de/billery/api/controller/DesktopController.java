package de.billery.api.controller;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Desktop-only endpoints (Spring profile {@code desktop}). The Tauri shell calls {@code POST
 * /api/desktop/shutdown} when its window closes, so the JVM stops cleanly and H2 flushes and closes
 * the database file. A hard kill could lose the last committed writes.
 */
@RestController
@Profile("desktop")
public class DesktopController {

  /**
   * Per-launch secret from {@code DESKTOP_SHUTDOWN_TOKEN}. Deliberately separate from the
   * non-secret instance id returned by {@code /api/health}.
   */
  private static final String SHUTDOWN_TOKEN =
      System.getenv().getOrDefault("DESKTOP_SHUTDOWN_TOKEN", "");

  private final ApplicationContext context;

  /**
   * Creates the controller.
   *
   * @param context the application context to close on shutdown
   */
  public DesktopController(ApplicationContext context) {
    this.context = context;
  }

  /**
   * Shuts the backend down after responding. Requires the launch secret in {@code X-Shutdown-Token}
   * so that no other local process can stop the app.
   *
   * @param token the caller's shutdown token
   * @return 202 when shutting down, 403 for a wrong or missing token
   */
  @SuppressWarnings("PMD.DoNotTerminateVM") // stopping the JVM is the purpose of this endpoint
  @PostMapping("/api/desktop/shutdown")
  public ResponseEntity<Void> shutdown(
      @RequestHeader(value = "X-Shutdown-Token", required = false) String token) {
    if (SHUTDOWN_TOKEN.isEmpty() || !SHUTDOWN_TOKEN.equals(token)) {
      return ResponseEntity.status(403).build();
    }
    Thread stopper =
        new Thread(() -> System.exit(SpringApplication.exit(context, () -> 0)), "desktop-shutdown");
    stopper.setDaemon(false);
    stopper.start();
    return ResponseEntity.accepted().build();
  }
}
