package de.billery.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** {@link TemplateBasedPdfGenerator#fixSelfClosingTags}: XHTML void elements for Flying Saucer. */
class SelfClosingTagsTest {

  @Test
  void closesBreaksRulesAndImagesOnce() {
    assertEquals("a<br/>b<hr/>", TemplateBasedPdfGenerator.fixSelfClosingTags("a<br>b<hr>"));
    assertEquals(
        "<img src=\"logo.png\" alt=\"Logo\"/>",
        TemplateBasedPdfGenerator.fixSelfClosingTags("<img src=\"logo.png\" alt=\"Logo\">"));
    assertEquals(
        "<img src=\"a\"/><img src=\"b\" />",
        TemplateBasedPdfGenerator.fixSelfClosingTags("<img src=\"a\"/><img src=\"b\" />"),
        "an image that is already closed stays as it is");
    assertEquals("<br/>", TemplateBasedPdfGenerator.fixSelfClosingTags("<br/>"));
    assertNull(TemplateBasedPdfGenerator.fixSelfClosingTags(null));
  }

  @Test
  void manyUnclosedImageTagsDoNotBacktrack() {
    String hostile = "<img=".repeat(20_000) + "/";
    assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> TemplateBasedPdfGenerator.fixSelfClosingTags(hostile));
  }
}
