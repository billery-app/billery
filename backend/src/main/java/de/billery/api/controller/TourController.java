package de.billery.api.controller;

import de.billery.repository.InvoiceRepository;
import de.billery.repository.UserSettingsRepository;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The state of the guided tour of a self-hosted or desktop installation, kept per installation in
 * {@code user_settings} (not only in one browser): whether it was shown, and whether it starts in
 * every new browser session. The SPA starts it by itself once, in an installation without documents
 * (so it never appears in an existing installation after an update), or in every session when that
 * is switched on in Einstellungen → Hilfe. The public demo starts it in every session without
 * asking the server. Not in SaaS: {@code user_settings} belongs to a company there, the tour to a
 * person, and the SPA keeps it in the browser until it is stored per user.
 */
@RestController
@RequestMapping("/api/tour")
@Profile("!saas")
public class TourController {

  /** Set once the tour was finished or skipped. */
  static final String SEEN = "tour_seen";

  /** Set when the tour starts in every new browser session. */
  static final String EVERY_SESSION = "tour_every_session";

  private final UserSettingsRepository settings;
  private final InvoiceRepository invoices;

  /**
   * @param settings where the state is kept
   * @param invoices tells whether the installation has documents yet
   */
  public TourController(UserSettingsRepository settings, InvoiceRepository invoices) {
    this.settings = settings;
    this.invoices = invoices;
  }

  /**
   * @return {@code seen}, {@code everySession} and {@code hasDocuments}
   */
  @GetMapping
  public Map<String, Boolean> state() {
    return Map.of(
        "seen", "true".equals(settings.getSetting(SEEN, "false")),
        "everySession", "true".equals(settings.getSetting(EVERY_SESSION, "false")),
        "hasDocuments", invoices.hasDocuments());
  }

  /**
   * Stores what is given: {@code seen} after the tour was finished or skipped, {@code everySession}
   * from the switch in Einstellungen → Hilfe.
   *
   * @param body the values to change; missing ones stay
   * @return the state afterwards
   */
  @PutMapping
  public Map<String, Boolean> update(@RequestBody Map<String, Boolean> body) {
    if (body != null && body.get("seen") != null) {
      settings.updateSetting(SEEN, Boolean.toString(body.get("seen")));
    }
    if (body != null && body.get("everySession") != null) {
      settings.updateSetting(EVERY_SESSION, Boolean.toString(body.get("everySession")));
    }
    return state();
  }
}
