package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.billery.TestWiring;
import de.billery.api.dto.CustomerDto;
import de.billery.api.dto.CustomerPageDto;
import de.billery.data.Customer;
import de.billery.data.TestDatabaseConfig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * {@code GET /api/customers/page}: the search of the former in-browser customer filter in SQL,
 * paging, the two orders and the 400 limits.
 */
class CustomerPageTest {

  private CustomerController controller;
  private int max;
  private int erika;
  private int anna;

  @BeforeAll
  static void setup() throws Exception {
    TestDatabaseConfig.setupH2Database();
  }

  @AfterAll
  static void tearDown() {
    TestDatabaseConfig.resetDatabaseConfig();
  }

  @BeforeEach
  void fixture() throws Exception {
    TestDatabaseConfig.clearData();
    controller = TestWiring.customerController();
    max = customer("Max", "Zimmer", "Hauptstr.", "Berlin", "10115", "030 111", "max@zimmer.de");
    erika =
        customer("Erika", "Muster", "Bahnweg", "Köln", "50667", "0221 222", "erika@100%_muster.de");
    anna = customer("Anna", "Becker", "Ring", "Hamburg", "20095", "040 333", "anna@becker.de");
  }

  @Test
  void searchesEveryFieldOfTheFormerFilterCaseInsensitive() {
    assertEquals(List.of(max), ids(search("max")));
    assertEquals(List.of(max), ids(search("ZIMMER")));
    assertEquals(List.of(erika), ids(search("bahnweg")));
    assertEquals(List.of(anna), ids(search("hamburg")));
    assertEquals(List.of(erika), ids(search("50667")));
    assertEquals(List.of(anna), ids(search("040 3")));
    assertEquals(List.of(max), ids(search("@zimmer")));
  }

  @Test
  void findsTheFullNameAsTypedInThePicker() {
    assertEquals(List.of(erika), ids(search("erika mus")));
  }

  @Test
  void percentAndUnderscoreMatchLiterally() {
    assertEquals(List.of(erika), ids(search("100%_")));
    assertEquals(List.of(), ids(search("x%")), "% is not a wildcard");
  }

  @Test
  void ordersByIdForTheListAndByNameForThePicker() {
    assertEquals(
        List.of(max, erika, anna), ids(controller.getCustomerPage(0, 10, null, "id").getBody()));
    assertEquals(
        List.of(anna, erika, max), ids(controller.getCustomerPage(0, 10, null, "name").getBody()));
  }

  @Test
  void pagesAreCompleteAndWithoutOverlap() {
    List<Integer> all = new ArrayList<>();
    CustomerPageDto first = controller.getCustomerPage(0, 2, null, "id").getBody();
    assertEquals(3, first.total());
    all.addAll(ids(first));
    all.addAll(ids(controller.getCustomerPage(1, 2, null, "id").getBody()));
    assertEquals(3, all.size());
    assertEquals(3, new HashSet<>(all).size());
    assertTrue(controller.getCustomerPage(5, 2, null, "id").getBody().items().isEmpty());
  }

  @Test
  void refusesPagesSizesAndSortsOutOfRange() {
    assertEquals(
        HttpStatus.BAD_REQUEST, controller.getCustomerPage(-1, 10, null, "id").getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST, controller.getCustomerPage(0, 0, null, "id").getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller
            .getCustomerPage(0, CustomerController.MAX_PAGE_SIZE + 1, null, "id")
            .getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST,
        controller.getCustomerPage(Integer.MAX_VALUE, 200, null, "id").getStatusCode());
    assertEquals(
        HttpStatus.BAD_REQUEST, controller.getCustomerPage(0, 10, null, "city").getStatusCode());
    assertEquals(
        HttpStatus.OK,
        controller
            .getCustomerPage(0, CustomerController.MAX_PAGE_SIZE, null, "name")
            .getStatusCode());
  }

  private CustomerPageDto search(String q) {
    return controller.getCustomerPage(0, 50, q, "id").getBody();
  }

  private static List<Integer> ids(CustomerPageDto page) {
    return page.items().stream().map(Customer::getId).toList();
  }

  private int customer(
      String first,
      String last,
      String street,
      String city,
      String zip,
      String phone,
      String mail) {
    return controller
        .createCustomer(
            new CustomerDto(null, first, last, zip, city, street, 1, phone, mail, "", "", ""))
        .getId();
  }
}
