package de.billery;

import de.billery.data.Owner;
import java.io.IOException;

/** Lets tests in other packages run the startup sequence of {@link MainApp}. */
public final class MainAppTestAccess {

  private MainAppTestAccess() {}

  /**
   * Runs {@link MainApp#prepareDatabase()} against the database {@code Database} is set to.
   *
   * @throws IOException if the schema resource cannot be read
   */
  public static void prepareDatabase() throws IOException {
    MainApp.prepareDatabase();
  }

  /**
   * An owner with every optional field set.
   *
   * @return a new owner without id
   */
  public static Owner owner() {
    return new Owner(
        0,
        "Erika",
        "Muster",
        "Muster & Söhne GmbH",
        "GmbH",
        "12345",
        "Ort",
        "Weg",
        1,
        "",
        "info@example.de",
        "https://example.de",
        "DE123456789",
        "DE02120300000000202051",
        false);
  }
}
