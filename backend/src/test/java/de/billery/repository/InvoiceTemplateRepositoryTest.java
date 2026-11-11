package de.billery.repository;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.InvoiceTemplate;
import de.billery.data.TestDatabaseConfig;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies the per-document clone and the document-specific visibility filter. */
class InvoiceTemplateRepositoryTest {

  private static InvoiceTemplateRepository repository;

  private static final String BLOCKS =
      "[{\"id\":\"t\",\"type\":\"header\",\"order\":0,"
          + "\"data\":{\"companyName\":\"X\"},\"config\":{\"layout\":\"logo-left\"}}]";

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    repository = TestWiring.templateRepository();
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
  void findAllExcludesDocumentSpecificTemplates() {
    InvoiceTemplate global = new InvoiceTemplate();
    global.setName("Global");
    global.setDocumentTypeId(1);
    global.setBlocks(BLOCKS);
    global.setDefault(true);
    global.setDocumentSpecific(false);
    repository.save(global);

    InvoiceTemplate perDoc = new InvoiceTemplate();
    perDoc.setName("[doc #1] Per-doc");
    perDoc.setDocumentTypeId(1);
    perDoc.setBlocks(BLOCKS);
    perDoc.setDefault(false);
    perDoc.setDocumentSpecific(true);
    repository.save(perDoc);

    List<InvoiceTemplate> all = repository.findAll();
    assertEquals(1, all.size(), "findAll() must hide per-document clones");
    assertFalse(all.get(0).isDocumentSpecific());
  }

  @Test
  void findAllIncludingDocumentSpecificReturnsEverything() {
    InvoiceTemplate global = new InvoiceTemplate();
    global.setName("Global");
    global.setDocumentTypeId(1);
    global.setBlocks(BLOCKS);
    global.setDocumentSpecific(false);
    repository.save(global);

    InvoiceTemplate perDoc = new InvoiceTemplate();
    perDoc.setName("[doc #1] Per-doc");
    perDoc.setDocumentTypeId(1);
    perDoc.setBlocks(BLOCKS);
    perDoc.setDocumentSpecific(true);
    repository.save(perDoc);

    List<InvoiceTemplate> all = repository.findAllIncludingDocumentSpecific();
    assertEquals(2, all.size(), "the inclusive variant returns everything");
  }

  @Test
  void findByDocumentTypeIdExcludesPerDocumentClonesByDefault() {
    InvoiceTemplate global = new InvoiceTemplate();
    global.setName("Global");
    global.setDocumentTypeId(1);
    global.setBlocks(BLOCKS);
    global.setDocumentSpecific(false);
    repository.save(global);

    InvoiceTemplate perDoc = new InvoiceTemplate();
    perDoc.setName("[doc #1] Per-doc");
    perDoc.setDocumentTypeId(1);
    perDoc.setBlocks(BLOCKS);
    perDoc.setDocumentSpecific(true);
    repository.save(perDoc);

    List<InvoiceTemplate> visible = repository.findByDocumentTypeId(1);
    assertEquals(1, visible.size());
    assertEquals("Global", visible.get(0).getName());

    List<InvoiceTemplate> inclusive = repository.findByDocumentTypeId(1, true);
    assertEquals(2, inclusive.size());
  }
}
