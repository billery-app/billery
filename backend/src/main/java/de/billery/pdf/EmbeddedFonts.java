package de.billery.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.openpdf.text.pdf.BaseFont;
import org.xhtmlrenderer.pdf.ITextFontResolver;

/**
 * The fonts every PDF is rendered with: Liberation Sans, Serif and Mono (SIL Open Font License,
 * {@code src/main/resources/fonts/liberation/LICENSE}), embedded into the document.
 *
 * <p>PDF/A forbids fonts that are not embedded, and the 14 standard PDF fonts Flying Saucer uses by
 * default (Helvetica, Times, Courier) never are, so a viewer substituted whatever it had.
 * Liberation has the same glyph widths as Helvetica / Arial, Times and Courier, so line breaks and
 * the layout stay as they were.
 *
 * <p>The fonts are registered under every family name Flying Saucer maps a CSS family to ({@code
 * sans-serif} → {@code SansSerif}, {@code Helvetica} ...), after the built-in families are removed.
 * The HTML and CSS therefore stay unchanged, and the template editor's preview (which renders the
 * same markup in the browser, see {@code TemplateParityTest}) needs no change either. A family that
 * is not registered falls back to the default family of Flying Saucer, which is one of these names
 * too, so no text can end up in a non-embedded font.
 */
final class EmbeddedFonts {

  private static final String DIRECTORY = "/fonts/liberation/";
  private static final String[] STYLES = {"Regular", "Bold", "Italic", "BoldItalic"};

  /** Flying Saucer family name → Liberation family (file prefix). */
  private static final Map<String, String> FAMILIES =
      Map.of(
          "SansSerif", "LiberationSans",
          "Helvetica", "LiberationSans",
          "Dialog", "LiberationSans",
          "Serif", "LiberationSerif",
          "TimesRoman", "LiberationSerif",
          "Monospaced", "LiberationMono",
          "Courier", "LiberationMono",
          "DialogInput", "LiberationMono");

  /**
   * File name → parsed font, per Liberation family. Parsed once; OpenPDF subsets a font per
   * document, so one instance serves every render.
   */
  private static final Map<String, Map<String, BaseFont>> FONTS = load();

  private EmbeddedFonts() {}

  /**
   * Replaces the built-in (never embedded) font families of a renderer with the embedded fonts.
   *
   * @param resolver the font resolver of the renderer, before the document is laid out
   */
  static void install(ITextFontResolver resolver) {
    Map<String, ?> families = resolver.getFonts();
    // getFonts() reloads the built-in families whenever the map is empty, so one entry is kept
    // until the replacements are registered.
    String keep = "Symbol";
    families.keySet().removeIf(name -> !keep.equals(name));
    FAMILIES.forEach(
        (family, file) -> {
          FONTS.get(file).forEach((name, font) -> resolver.addFont(font, DIRECTORY + name, family));
        });
    families.keySet().removeIf(name -> !FAMILIES.containsKey(name));
  }

  private static Map<String, Map<String, BaseFont>> load() {
    Map<String, Map<String, BaseFont>> fonts = new HashMap<>();
    for (String file : new TreeSet<>(FAMILIES.values())) {
      Map<String, BaseFont> styles = new LinkedHashMap<>();
      for (String style : STYLES) {
        String name = file + "-" + style + ".ttf";
        styles.put(name, font(name));
      }
      fonts.put(file, Collections.unmodifiableMap(styles));
    }
    return Map.copyOf(fonts);
  }

  private static BaseFont font(String name) {
    try (InputStream in = EmbeddedFonts.class.getResourceAsStream(DIRECTORY + name)) {
      if (in == null) {
        throw new IllegalStateException("Font missing from the classpath: " + DIRECTORY + name);
      }
      BaseFont font =
          BaseFont.createFont(
              name, BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true, in.readAllBytes(), null);
      // PDF/A-2 and -3 do not need a CIDSet, and OpenPDF writes one that lists only the subset's
      // glyphs, which veraPDF rejects (ISO 19005-3, 6.2.11.4.2).
      font.setIncludeCidSet(false);
      return font;
    } catch (IOException e) {
      throw new UncheckedIOException("Font cannot be read: " + name, e);
    }
  }
}
