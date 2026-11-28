package de.billery.api.controller;

import de.billery.api.error.ErrorBody;
import de.billery.api.error.ErrorCode;
import de.billery.preferences.UserPreferences;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The user's own settings (#15): the language of the user interface. Self-host and desktop keep
 * them per installation, SaaS per signed-in user (it needs no {@code X-Tenant-Id}: the language
 * belongs to the person, not to a company).
 */
@RestController
@RequestMapping("/api/preferences")
public class PreferencesController {

  private final UserPreferences preferences;

  /**
   * @param preferences where they are kept (per installation or per user)
   */
  public PreferencesController(UserPreferences preferences) {
    this.preferences = preferences;
  }

  /**
   * @return {@code {"language": "de"|"en"|null}}; null while none was chosen
   */
  @GetMapping
  public Map<String, Object> get() {
    Map<String, Object> body = new HashMap<>();
    body.put("language", preferences.language().orElse(null));
    return body;
  }

  /**
   * Stores the language.
   *
   * @param body {@code {"language": "de"|"en"}}
   * @return the preferences afterwards, or 400 for a language the app does not offer
   */
  @PutMapping
  public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, String> body) {
    String language = body == null ? null : body.get("language");
    if (language == null || !UserPreferences.LANGUAGES.contains(language)) {
      return ResponseEntity.badRequest().body(ErrorBody.from(ErrorCode.LANGUAGE_UNKNOWN.with()));
    }
    preferences.setLanguage(language);
    return ResponseEntity.ok(get());
  }
}
