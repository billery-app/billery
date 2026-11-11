package de.billery.data;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ServiceTest {

  @Test
  void testConstructorAndGetters() {
    Service service =
        new Service(1, "TestService", 0, "Beschreibung", new BigDecimal("100.0"), "Std", "Notiz");

    assertEquals(1, service.getId());
    assertEquals("TestService", service.getName());
    assertEquals("Beschreibung", service.getDescription());
    assertEquals(new BigDecimal("100.0"), service.getPrice());
    assertEquals("Std", service.getUnit());
    assertEquals("Notiz", service.getNote());
  }

  @Test
  void testSecondConstructor() {
    Service service = new Service("Service2", 0, "Desc2", new BigDecimal("200.0"), "h", "Note2");

    assertEquals(0, service.getId()); // id default 0
    assertEquals("Service2", service.getName());
    assertEquals("Desc2", service.getDescription());
    assertEquals(new BigDecimal("200.0"), service.getPrice());
    assertEquals("h", service.getUnit());
    assertEquals("Note2", service.getNote());
  }

  @Test
  void testSetters() {
    Service service = new Service("Initial", 0, "InitDesc", new BigDecimal("50.0"), "unit", "note");

    service.setId(99);
    service.setName("NeuName");
    service.setDescription("NeuDesc");
    service.setPrice(new BigDecimal("75.0"));
    service.setUnit("neuUnit");
    service.setNote("neuNote");

    assertEquals(99, service.getId());
    assertEquals("NeuName", service.getName());
    assertEquals("NeuDesc", service.getDescription());
    assertEquals(new BigDecimal("75.0"), service.getPrice());
    assertEquals("neuUnit", service.getUnit());
    assertEquals("neuNote", service.getNote());
  }

  @Test
  void testToStringContainsValues() {
    Service service =
        new Service(
            42, "MeinService", 0, "Beschreibung", new BigDecimal("123.45"), "Std", "Hinweis");
    String s = service.toString();

    assertTrue(s.contains("42"));
    assertTrue(s.contains("MeinService"));
    assertTrue(s.contains("Beschreibung"));
    assertTrue(s.contains("123.45"));
    assertTrue(s.contains("Hinweis"));
  }
}
