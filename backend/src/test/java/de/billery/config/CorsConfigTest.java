package de.billery.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

/** CORS is opt-in: no origins configured means the API is same-origin only. */
class CorsConfigTest {

  /** Exposes the registry's protected result. */
  private static final class InspectableRegistry extends CorsRegistry {
    Map<String, CorsConfiguration> configurations() {
      return getCorsConfigurations();
    }
  }

  private static Map<String, CorsConfiguration> configure(String origins) {
    CorsConfig config = new CorsConfig();
    config.setAllowedOrigins(origins);
    InspectableRegistry registry = new InspectableRegistry();
    config.addCorsMappings(registry);
    return registry.configurations();
  }

  @Test
  void parsesOriginsTrimmedAndWithoutTrailingSlash() {
    assertArrayEquals(
        new String[] {"https://a.example", "http://localhost:5173"},
        CorsConfig.parseOrigins(" https://a.example/ , http://localhost:5173,, "));
    assertEquals(0, CorsConfig.parseOrigins("").length);
    assertEquals(0, CorsConfig.parseOrigins(null).length);
  }

  @Test
  void noOriginsMeansNoCorsMapping() {
    assertTrue(configure("").isEmpty());
    assertTrue(configure("  ").isEmpty());
  }

  @Test
  void configuredOriginsGetTheMethodsTheSpaUses() {
    CorsConfiguration cors = configure("https://demo.example.com").get("/api/**");

    assertNotNull(cors);
    assertEquals(java.util.List.of("https://demo.example.com"), cors.getAllowedOrigins());
    // PATCH: the invoice status endpoint (issue, pay, ...) is a PATCH.
    assertTrue(cors.getAllowedMethods().contains("PATCH"));
    assertTrue(cors.getExposedHeaders().contains("Content-Disposition"));
    assertNotEquals(Boolean.TRUE, cors.getAllowCredentials());
  }
}
