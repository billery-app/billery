package de.billery.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.billery.TestWiring;
import de.billery.data.Article;
import de.billery.data.Equipment;
import de.billery.data.Service;
import de.billery.data.TestDatabaseConfig;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The catalog lists (articles, services, equipment) are ordered by id and keep that order when an
 * item is renamed. On PostgreSQL an updated row is a new tuple, so without {@code ORDER BY} the
 * edited item moved to the end of the list; the test runs on PostgreSQL in the {@code postgres} CI
 * job.
 */
class CatalogOrderTest {

  private static ArticleRepository articles;
  private static ServiceRepository services;
  private static EquipmentRepository equipment;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
    articles = TestWiring.articleRepository();
    services = TestWiring.serviceRepository();
    equipment = TestWiring.equipmentRepository();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void clear() throws Exception {
    TestDatabaseConfig.clearData();
  }

  @Test
  void articlesStayInIdOrderAfterARename() {
    List<Article> created =
        List.of("Charlie", "Alpha", "Bravo").stream()
            .map(name -> articles.save(new Article(name, bd(1), bd(2), "Stück", "", "")))
            .toList();
    Article first = created.getFirst();
    first.setName("Zulu");
    articles.update(first);

    assertEquals(sorted(ids(created, Article::getId)), ids(articles.findAll(), Article::getId));
  }

  @Test
  void servicesStayInIdOrderAfterARename() {
    List<Service> created =
        List.of("Charlie", "Alpha", "Bravo").stream()
            .map(name -> services.save(new Service(name, 0, "", bd(50), "Stunde", "")))
            .toList();
    Service first = created.getFirst();
    first.setName("Zulu");
    services.update(first);

    assertEquals(sorted(ids(created, Service::getId)), ids(services.findAll(), Service::getId));
  }

  @Test
  void equipmentStaysInIdOrderAfterARename() {
    List<Equipment> created =
        List.of("Charlie", "Alpha", "Bravo").stream()
            .map(
                name ->
                    equipment.save(
                        new Equipment(0, name, bd(100), null, 36, bd(0), bd(0), "active", null)))
            .toList();
    Equipment first = created.getFirst();
    first.setName("Zulu");
    equipment.update(first);

    assertEquals(
        sorted(ids(created, Equipment::getId)), ids(equipment.findAll(), Equipment::getId));
  }

  /** The ids of the items in list order. */
  private static <T> List<Integer> ids(List<T> items, Function<T, Integer> id) {
    return items.stream().map(id).toList();
  }

  /** The same ids in ascending order: the expected catalog order. */
  private static List<Integer> sorted(List<Integer> ids) {
    return ids.stream().sorted().toList();
  }

  private static BigDecimal bd(int value) {
    return BigDecimal.valueOf(value);
  }
}
