package de.billery.api.controller;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Health endpoints. {@code /api/health} is the liveness check: the process answers (polled by the
 * desktop shell while the backend starts). {@code /api/health/ready} is the readiness check: the
 * database answers too (Docker health checks, monitoring). Both are open and say nothing more than
 * up or down.
 */
@RestController
public class HealthController {

  private static final Logger LOG = Logger.getLogger(HealthController.class.getName());

  /**
   * Seconds a readiness check may take. Below the Docker health check's timeout: with the database
   * gone, the pool would otherwise wait for a connection for its full timeout (30 s).
   */
  private static final int CHECK_SECONDS = 3;

  /**
   * Opaque per-launch id from {@code DESKTOP_INSTANCE_ID} (set by the desktop shell, empty
   * otherwise). The shell compares it to detect a foreign server already listening on its port.
   */
  private static final String INSTANCE_ID = System.getenv().getOrDefault("DESKTOP_INSTANCE_ID", "");

  private final DataSource dataSource;

  /** Runs the database check, so that a hanging one cannot hold the request. */
  private final ExecutorService checker =
      Executors.newSingleThreadExecutor(
          task -> {
            Thread thread = new Thread(task, "readiness-check");
            thread.setDaemon(true);
            return thread;
          });

  /** The check in progress; a still hanging one is reused instead of piling up new ones. */
  private Future<Boolean> running;

  /**
   * @param dataSource the application's connection pool
   */
  public HealthController(DataSource dataSource) {
    this.dataSource = dataSource;
  }

  /**
   * Reports that the backend process is up. Does not touch the database, so a database outage never
   * makes a container look dead and restart.
   *
   * @return {@code {"status": "UP", "instance": "<id>"}}
   */
  @GetMapping("/api/health")
  public ResponseEntity<Map<String, String>> health() {
    return ResponseEntity.ok(Map.of("status", "UP", "instance", INSTANCE_ID));
  }

  /**
   * Reports whether the backend can serve requests: a pooled database connection is valid.
   *
   * @return 200 {@code {"status": "UP"}}, or 503 {@code {"status": "DOWN"}} without details
   */
  @GetMapping("/api/health/ready")
  public ResponseEntity<Map<String, String>> ready() {
    boolean up;
    try {
      up = currentCheck().get(CHECK_SECONDS, TimeUnit.SECONDS);
    } catch (TimeoutException e) {
      LOG.warning("Readiness: the database did not answer within " + CHECK_SECONDS + " s");
      up = false;
    } catch (ExecutionException e) {
      up = false;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      up = false;
    }
    return up
        ? ResponseEntity.ok(Map.of("status", "UP"))
        : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
  }

  private synchronized Future<Boolean> currentCheck() {
    if (running == null || running.isDone()) {
      running = checker.submit(this::databaseAnswers);
    }
    return running;
  }

  private boolean databaseAnswers() {
    try (Connection connection = dataSource.getConnection()) {
      if (connection.isValid(CHECK_SECONDS)) {
        return true;
      }
      LOG.warning("Readiness: the database connection is not valid");
    } catch (SQLException e) {
      // The message only, without credentials or a stack trace; this runs every few seconds.
      LOG.log(Level.WARNING, "Readiness: the database is not reachable: {0}", e.getMessage());
    }
    return false;
  }
}
