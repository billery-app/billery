package de.billery.saas.preferences;

import de.billery.preferences.UserPreferences;
import de.billery.saas.tenant.TenantContext;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * SaaS: the preferences of the signed-in user, in {@code app_user} ({@code
 * V11__user_language.sql}). Only ever the row of the user of the request ({@link
 * TenantContext#userId()}); {@code app_user} has no row-level security, the {@code WHERE id} is
 * the boundary.
 */
@Component
@Profile("saas")
public class AccountPreferences implements UserPreferences {

  private final JdbcClient jdbc;

  /**
   * @param jdbc the application's database access
   */
  public AccountPreferences(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<String> language() {
    return jdbc.sql("SELECT ui_language FROM app_user WHERE id = :user")
        .param("user", currentUser())
        .query(String.class)
        .optional();
  }

  @Override
  public void setLanguage(String language) {
    jdbc.sql("UPDATE app_user SET ui_language = :language WHERE id = :user")
        .param("language", language)
        .param("user", currentUser())
        .update();
  }

  private static UUID currentUser() {
    String user = TenantContext.userId();
    if (user == null) {
      throw new IllegalStateException("No signed-in user in this request");
    }
    return UUID.fromString(user);
  }
}
