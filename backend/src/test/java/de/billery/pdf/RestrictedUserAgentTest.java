package de.billery.pdf;

import static org.junit.jupiter.api.Assertions.*;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xhtmlrenderer.pdf.ITextOutputDevice;
import org.xhtmlrenderer.pdf.ITextRenderer;

/** The PDF renderer may only read uploaded files, never the network or other local files. */
class RestrictedUserAgentTest {

  @TempDir Path uploads;

  private RestrictedUserAgent agent() {
    return new RestrictedUserAgent(
        new ITextOutputDevice(ITextRenderer.DEFAULT_DOTS_PER_POINT),
        ITextRenderer.DEFAULT_DOTS_PER_PIXEL,
        uploads);
  }

  @Test
  void servesUploadsRegardlessOfHostAndPort() throws Exception {
    Files.writeString(uploads.resolve("logo.png"), "png");

    try (InputStream in = agent().openStream("http://127.0.0.1:8080/uploads/logo.png")) {
      assertEquals("png", new String(in.readAllBytes()));
    }
    assertTrue(agent().readUpload("https://other-host.example/uploads/logo.png").isPresent());
  }

  @Test
  void refusesNetworkFilesAndTraversal() throws Exception {
    Files.writeString(uploads.resolve("logo.png"), "png");
    RestrictedUserAgent agent = agent();

    assertTrue(agent.readUpload("http://169.254.169.254/latest/meta-data/").isEmpty());
    assertTrue(agent.readUpload("file:///etc/passwd").isEmpty());
    assertTrue(agent.readUpload("http://127.0.0.1:8080/uploads/../secret.txt").isEmpty());
    assertTrue(agent.readUpload("http://127.0.0.1:8080/uploads/sub/logo.png").isEmpty());
    assertTrue(agent.readUpload("http://127.0.0.1:8080/uploads/missing.png").isEmpty());
    assertThrows(FileNotFoundException.class, () -> agent.openStream("http://internal/admin"));
  }

  /** Without Flying Saucer's default stylesheet, {@code <style>} content is printed as text. */
  @Test
  void allowsTheRendererDefaultStylesheetButNoOtherJarOrFileUri() throws Exception {
    String stylesheet =
        org.xhtmlrenderer.simple.extend.XhtmlNamespaceHandler.class
            .getResource("/resources/css/XhtmlNamespaceHandler.css")
            .toExternalForm();
    try (InputStream in = agent().openStream(stylesheet)) {
      assertTrue(new String(in.readAllBytes()).contains("display"));
    }
    assertNull(RestrictedUserAgent.resolveClasspathResource("jar:file:/etc/passwd.zip!/x.css"));
    assertNull(RestrictedUserAgent.resolveClasspathResource("file:///etc/resources/passwd"));
    assertThrows(FileNotFoundException.class, () -> agent().openStream("file:///etc/passwd"));
  }

  @Test
  void cssCannotCloseTheStyleElement() {
    String css = "h1 { color: red } </style><img src=\"http://internal/\"/> & more";

    String safe = TemplateBasedPdfGenerator.cssForStyleElement(css);

    assertFalse(safe.contains("<"));
    assertFalse(safe.contains("&"));
    assertEquals("", TemplateBasedPdfGenerator.cssForStyleElement(null));
  }
}
