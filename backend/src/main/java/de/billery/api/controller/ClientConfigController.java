package de.billery.api.controller;

import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the single-page app how it is operated, before it asks for anything else. A self-hosted
 * install has no sign-in; the SaaS profile answers the same path with its sign-in settings.
 */
@RestController
@Profile("!saas")
public class ClientConfigController {

  /**
   * Returns the operating mode.
   *
   * @return {@code {"mode": "selfhost"}}
   */
  @GetMapping("/api/config")
  public Map<String, String> config() {
    return Map.of("mode", "selfhost");
  }
}
