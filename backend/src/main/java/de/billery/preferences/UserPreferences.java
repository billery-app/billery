package de.billery.preferences;

import java.util.Optional;
import java.util.Set;

/**
 * The personal settings of whoever uses the app: today the language of the user interface (#15).
 * Self-host and desktop keep them per installation ({@link InstallationPreferences}, there is one
 * user); SaaS per signed-in user ({@code de.billery.saas.preferences.AccountPreferences}).
 */
public interface UserPreferences {

  /** The languages the user interface offers; German is the default. */
  Set<String> LANGUAGES = Set.of("de", "en");

  /**
   * @return the chosen language, empty while none was chosen (the app then shows German)
   */
  Optional<String> language();

  /**
   * Stores the language.
   *
   * @param language one of {@link #LANGUAGES}
   */
  void setLanguage(String language);
}
