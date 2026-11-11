package de.billery.service;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.data.TestDatabaseConfig;
import org.junit.jupiter.api.*;

class DocumentNumberServiceTest {

  private static DocumentNumberService service;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    service = TestWiring.documentNumberService();
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
  void generateDocumentNumber_startsAt001() {
    String result = service.generateDocumentNumber(1);
    assertTrue(result.startsWith("RE-"), "Invoice should start with RE- prefix");
    assertTrue(result.endsWith("-001"), "First invoice should end with -001, got: " + result);
  }

  @Test
  void generateDocumentNumber_generatesCorrectFormat() {
    String result = service.generateDocumentNumber(1);
    int currentYear = java.time.LocalDate.now().getYear();
    assertEquals("RE-" + currentYear + "-001", result);
  }

  @Test
  void generateDocumentNumber_incrementsSequentially() {
    String first = service.generateDocumentNumber(1);
    String second = service.generateDocumentNumber(1);
    String third = service.generateDocumentNumber(1);

    assertTrue(first.endsWith("-001"));
    assertTrue(second.endsWith("-002"));
    assertTrue(third.endsWith("-003"));
  }

  @Test
  void generateDocumentNumber_usesCorrectPrefixPerType() {
    String invoice = service.generateDocumentNumber(1);
    String offer = service.generateDocumentNumber(2);
    String costCalc = service.generateDocumentNumber(3);
    String parted = service.generateDocumentNumber(4);
    String finalInv = service.generateDocumentNumber(5);

    assertTrue(invoice.startsWith("RE-"));
    assertTrue(offer.startsWith("AN-"));
    assertTrue(costCalc.startsWith("KV-"));
    assertTrue(parted.startsWith("TR-"));
    assertTrue(finalInv.startsWith("SR-"));
  }

  @Test
  void extractVersion_returns1ForNonVersioned() {
    assertEquals(1, service.extractVersion("RE-2026-001"));
    assertEquals(1, service.extractVersion(null));
  }

  @Test
  void extractVersion_parsesVersionSuffix() {
    assertEquals(2, service.extractVersion("RE-2026-001/2"));
    assertEquals(3, service.extractVersion("RE-2026-001/3"));
  }

  @Test
  void generateVersionedNumber_addsVersionSuffix() {
    assertEquals("RE-2026-001/2", service.generateVersionedNumber("RE-2026-001", 2));
    assertEquals("RE-2026-001/3", service.generateVersionedNumber("RE-2026-001", 3));
  }

  @Test
  void generateVersionedNumber_returnsBaseForVersion1() {
    assertEquals("RE-2026-001", service.generateVersionedNumber("RE-2026-001", 1));
  }

  @Test
  void extractBaseNumber_removesVersion() {
    assertEquals("RE-2026-001", service.extractBaseNumber("RE-2026-001/2"));
    assertEquals("RE-2026-001", service.extractBaseNumber("RE-2026-001"));
  }

  @Test
  void formatNumber_padsWithZeros() {
    assertEquals("RE-2026-001", service.formatNumber("RE", 2026, 1));
    assertEquals("RE-2026-012", service.formatNumber("RE", 2026, 12));
    assertEquals("RE-2026-123", service.formatNumber("RE", 2026, 123));
  }
}
