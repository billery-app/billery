package de.billery.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.data.InvoiceTemplate;
import de.billery.data.TestDatabaseConfig;
import de.billery.repository.InvoiceTemplateRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A template without blocks (the DOCX/ODT import stores only HTML) gets the default layout at
 * startup; a template with blocks is left exactly as it is.
 */
class TemplateBlocksBackfillTest {

  private static final String OWN_BLOCKS =
      "[{\"id\":\"h\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"Eigene\"},\"config\":{}}]";

  private static InvoiceTemplateRepository templates;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    templates = TestWiring.templateRepository();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clearData() throws Exception {
    TestDatabaseConfig.clearData();
  }

  @Test
  void fillsOnlyTemplatesWithoutBlocks() {
    int imported = save("Importiert", 2, "[]");
    int own = save("Eigene Vorlage", 1, OWN_BLOCKS);

    new TemplateBlocksBackfill(templates).backfillMissingBlocks();

    String filled = templates.findById(imported).getBlocks();
    for (String type : new String[] {"header", "invoice-meta", "line-items", "totals", "footer"}) {
      assertTrue(filled.contains("\"type\":\"" + type + "\""), type + " missing: " + filled);
    }
    assertTrue(filled.contains("\"invoiceTitle\":\"Angebot\""), "an offer is titled as one");
    assertEquals(OWN_BLOCKS, templates.findById(own).getBlocks());
  }

  private static int save(String name, int documentTypeId, String blocks) {
    InvoiceTemplate template = new InvoiceTemplate();
    template.setName(name);
    template.setDocumentTypeId(documentTypeId);
    template.setHtmlContent("<p>x</p>");
    template.setBlocks(blocks);
    return templates.save(template).getId();
  }
}
