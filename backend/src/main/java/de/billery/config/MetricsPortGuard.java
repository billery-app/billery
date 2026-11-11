package de.billery.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Refuses to start when the metrics port ({@code BILLERY_METRICS_PORT}) is the application's port.
 * Spring Boot would then serve {@code /prometheus} on the application's web server, without
 * authentication and behind the same reverse proxy as the app, so it would be public.
 */
@Component
public class MetricsPortGuard {

  /**
   * Checks the two ports.
   *
   * @param serverPort the application's port ({@code server.port})
   * @param metricsPort the metrics port ({@code management.server.port}); -1 = off
   * @throws IllegalStateException if both are the same port
   */
  public MetricsPortGuard(
      @Value("${server.port:8080}") int serverPort,
      @Value("${management.server.port:-1}") int metricsPort) {
    check(serverPort, metricsPort);
  }

  /**
   * Fails when the metrics would be served on the application's port.
   *
   * @param serverPort the application's port
   * @param metricsPort the metrics port; -1 = off
   * @throws IllegalStateException if both are the same port
   */
  static void check(int serverPort, int metricsPort) {
    if (metricsPort == serverPort) {
      throw new IllegalStateException(
          "BILLERY_METRICS_PORT must not be the application's port ("
              + serverPort
              + "): the metrics would be public behind the reverse proxy. Use another port,"
              + " for example 9464, or leave it empty.");
    }
  }
}
