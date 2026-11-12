package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.TestWiring;
import de.billery.api.OwnerAlreadyExistsException;
import de.billery.api.dto.ArticleDto;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.EquipmentDto;
import de.billery.api.dto.OwnerDto;
import de.billery.api.dto.SearchResult;
import de.billery.api.dto.ServiceDto;
import de.billery.data.Customer;
import de.billery.data.Owner;
import de.billery.data.TestDatabaseConfig;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * CRUD round trips for the master-data controllers (customers, articles, services, owner,
 * equipment) and the global search, called directly against the H2 test database.
 */
class MasterDataControllersTest {

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
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
  void customerCrudRoundTrip() {
    CustomerController controller = TestWiring.customerController();

    Customer created =
        controller.createCustomer(
            new CustomerDto(
                null,
                "Erika",
                "Müller & Co",
                "12345",
                "Ort",
                "Weg",
                7,
                "",
                "e@x.de",
                "",
                "",
                null));
    assertTrue(created.getId() > 0);
    assertEquals("Müller & Co", controller.getCustomer(created.getId()).getBody().getLastName());

    controller.updateCustomer(
        created.getId(),
        new CustomerDto(null, "Erika", "Neu", "12345", "Ort", "Weg", 8, "", "", "", "", null));
    assertEquals("Neu", controller.getCustomer(created.getId()).getBody().getLastName());
    assertEquals(1, controller.getCustomerPage(0, 50, null, "id").getBody().total());

    controller.deleteCustomer(created.getId());
    assertEquals(HttpStatus.NOT_FOUND, controller.getCustomer(created.getId()).getStatusCode());
  }

  @Test
  void customerWithoutHouseNumberCanBeSaved() {
    // e.g. a P.O. box address; the field is optional in the form.
    Customer created =
        TestWiring.customerController()
            .createCustomer(
                new CustomerDto(
                    null, "Max", "Postfach", "12345", "Ort", "", null, "", "", "", "", null));
    assertTrue(created.getId() > 0);
  }

  @Test
  void updatingMissingCustomerIsNotFound() {
    assertEquals(
        HttpStatus.NOT_FOUND,
        TestWiring.customerController()
            .updateCustomer(
                999, new CustomerDto(null, "a", "b", "c", "d", "e", 1, "", "", "", "", null))
            .getStatusCode());
  }

  @Test
  void articleCrudRoundTrip() {
    ArticleController controller = TestWiring.articleController();

    int id =
        controller
            .createArticle(
                new ArticleDto(
                    null,
                    "Kabel",
                    "3 m",
                    "Stk",
                    BigDecimal.valueOf(2.5),
                    BigDecimal.valueOf(4.99),
                    null,
                    "Lieferant: Müller"))
            .getId();
    ArticleDto loaded = controller.getArticle(id).getBody();
    assertEquals("Kabel", loaded.name());
    assertEquals(0, BigDecimal.valueOf(4.99).compareTo(loaded.salesPrice()));
    assertEquals("Lieferant: Müller", loaded.note());

    controller.updateArticle(
        id,
        new ArticleDto(
            null, "Kabel lang", "5 m", "Stk", BigDecimal.ONE, BigDecimal.valueOf(6), null, null));
    assertEquals("Kabel lang", controller.getArticle(id).getBody().name());
    assertEquals("", controller.getArticle(id).getBody().note(), "a missing note clears it");
    assertEquals(1, controller.getAllArticles().size());

    controller.deleteArticle(id);
    assertEquals(HttpStatus.NOT_FOUND, controller.getArticle(id).getStatusCode());
  }

  @Test
  void serviceCrudRoundTrip() {
    ServiceController controller = TestWiring.serviceController();

    int id =
        controller
            .createService(
                new ServiceDto(
                    null, "Montage", "vor Ort", BigDecimal.valueOf(65), 0, null, "Std", null))
            .getId();
    assertEquals("Montage", controller.getService(id).getBody().name());

    controller.updateService(
        id,
        new ServiceDto(null, "Montage+", "vor Ort", BigDecimal.valueOf(70), 0, null, "Std", null));
    assertEquals(
        0, BigDecimal.valueOf(70).compareTo(controller.getService(id).getBody().hourlyRate()));
    assertEquals(1, controller.getAllServices().size());

    controller.deleteService(id);
    assertEquals(HttpStatus.NOT_FOUND, controller.getService(id).getStatusCode());
  }

  @Test
  void ownerRoundTripKeepsIbanUrlAndSmallBusinessFlag() {
    OwnerController controller = TestWiring.ownerController();

    Owner created =
        controller.createOwner(
            new OwnerDto(
                null,
                "Erika",
                "Muster",
                "Muster GmbH",
                "GmbH",
                "12345",
                "Ort",
                "Weg",
                1,
                "",
                "info@x.de",
                "https://x.de",
                "DE1",
                "DE02120300000000202051",
                true));

    Owner primary = controller.getPrimaryOwner().getBody();
    assertNotNull(primary);
    assertEquals("DE02120300000000202051", primary.getIban());
    assertEquals("https://x.de", primary.getUrl());
    assertTrue(primary.isSmallBusiness());

    // The POST answer carries the generated id (it was 0 before, found by the e2e smoke test).
    assertTrue(created.getId() > 0, "created owner has no id");
    assertEquals(primary.getId(), created.getId());

    // There is one owner per company: a second one is refused with a message, not a database error.
    OwnerDto second =
        new OwnerDto(null, "Max", "Zwei", "", "", "1", "O", "W", 1, "", "", "", "", "", null);
    OwnerAlreadyExistsException refused =
        assertThrows(OwnerAlreadyExistsException.class, () -> controller.createOwner(second));
    assertTrue(refused.getMessage().contains("bereits einen Inhaber"));
    assertEquals(1, controller.getAllOwners().size());

    controller.deleteOwner(created.getId());
    assertEquals(0, controller.getAllOwners().size());
  }

  @Test
  void equipmentCrudRoundTrip() {
    EquipmentController controller = TestWiring.equipmentController();

    EquipmentDto created =
        controller.create(
            new EquipmentDto(
                null,
                "Bohrmaschine",
                BigDecimal.valueOf(1200),
                LocalDate.of(2025, 1, 1),
                48,
                BigDecimal.TEN,
                BigDecimal.valueOf(100),
                "ACTIVE",
                ""));
    assertNotNull(created.id());
    assertEquals("Bohrmaschine", controller.getAll().get(0).name());
    assertEquals(1, controller.getSummary().size());

    controller.delete(created.id());
    assertTrue(controller.getAll().isEmpty());
  }

  @Test
  void searchFindsCustomersCaseInsensitively() {
    TestWiring.customerController()
        .createCustomer(
            new CustomerDto(
                null, "Jürgen", "Größmann", "1", "Ort", "Weg", 1, "", "", "", "", null));

    SearchResult result = TestWiring.searchController().search("GRÖßMANN").getBody();

    assertNotNull(result);
    assertEquals(1, result.customers().size());
  }
}
