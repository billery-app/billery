package de.billery.data;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ArticleTest {

  @Test
  void testConstructorAndGetters() {
    Article article =
        new Article(
            1, "TestName", new BigDecimal("10.5"), new BigDecimal("15.75"), "kg", "TestNote", null);

    assertEquals(1, article.getId());
    assertEquals("TestName", article.getName());
    assertEquals(new BigDecimal("10.5"), article.getPurchaseValue());
    assertEquals(new BigDecimal("15.75"), article.getSalesValue());
    assertEquals("kg", article.getUnit());
    assertEquals("TestNote", article.getNote());
  }

  @Test
  void testSecondConstructor() {
    Article article =
        new Article(
            "TestName2", new BigDecimal("20.0"), new BigDecimal("30.0"), "pcs", "Note2", null);

    assertEquals(0, article.getId()); // id default 0
    assertEquals("TestName2", article.getName());
    assertEquals(new BigDecimal("20.0"), article.getPurchaseValue());
    assertEquals(new BigDecimal("30.0"), article.getSalesValue());
    assertEquals("pcs", article.getUnit());
    assertEquals("Note2", article.getNote());
  }

  @Test
  void testSetters() {
    Article article =
        new Article(
            "Initial",
            new BigDecimal("1.0"),
            new BigDecimal("2.0"),
            "unit",
            "note",
            "test description");

    article.setId(99);
    article.setName("NewName");
    article.setPurchaseValue(new BigDecimal("9.9"));
    article.setSalesValue(new BigDecimal("19.9"));
    article.setUnit("newUnit");
    article.setNote("newNote");

    assertEquals(99, article.getId());
    assertEquals("NewName", article.getName());
    assertEquals(new BigDecimal("9.9"), article.getPurchaseValue());
    assertEquals(new BigDecimal("19.9"), article.getSalesValue());
    assertEquals("newUnit", article.getUnit());
    assertEquals("newNote", article.getNote());
  }

  @Test
  void testToStringContainsValues() {
    Article article =
        new Article(
            42,
            "MyArticle",
            new BigDecimal("5.0"),
            new BigDecimal("10.0"),
            "ltr",
            "noteText",
            "descriptionText");
    String s = article.toString();

    assertTrue(s.contains("42"));
    assertTrue(s.contains("MyArticle"));
    assertTrue(s.contains("5.0"));
    assertTrue(s.contains("10.0"));
    assertTrue(s.contains("ltr"));
    assertTrue(s.contains("noteText"));
  }
}
