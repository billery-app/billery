package de.billery.preferences;

import de.billery.repository.UserSettingsRepository;
import java.util.Optional;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Self-host and desktop: the preferences of the installation, in {@code user_settings} (the
 * installation has a single user).
 */
@Component
@Profile("!saas")
public class InstallationPreferences implements UserPreferences {

  /** The {@code user_settings} key of the interface language. */
  static final String LANGUAGE_KEY = "ui_language";

  private final UserSettingsRepository settings;

  /**
   * @param settings where the value is kept
   */
  public InstallationPreferences(UserSettingsRepository settings) {
    this.settings = settings;
  }

  @Override
  public Optional<String> language() {
    String value = settings.getSetting(LANGUAGE_KEY, "");
    return value.isEmpty() ? Optional.empty() : Optional.of(value);
  }

  @Override
  public void setLanguage(String language) {
    settings.updateSetting(LANGUAGE_KEY, language);
  }
}
