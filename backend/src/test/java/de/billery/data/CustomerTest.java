package de.billery.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the Customer entity class. Tests the functionality of the Customer data model
 * including constructor behavior and property access.
 *
 * @author Invoice Management System
 * @version 1.0
 */
class CustomerTest {

  /**
   * Tests the Customer constructor and getter methods. Verifies that all properties are correctly
   * set and retrieved.
   */
  @Test
  void testConstructorAndGetters() {
    Customer customer =
        new Customer(
            1,
            "Max",
            "Mustermann",
            "12345",
            "Musterstadt",
            "Musterstraße",
            1,
            "0123456789",
            "max@example.com",
            "DE123456789",
            "No notes");

    assertEquals(1, customer.getId());
    assertEquals("Max", customer.getFirstName());
    assertEquals("Mustermann", customer.getLastName());
    assertEquals("12345", customer.getPostalCode());
    assertEquals("Musterstadt", customer.getCity());
    assertEquals("Musterstraße", customer.getStreet());
    assertEquals(1, customer.getHouseNumber());
    assertEquals("0123456789", customer.getPhone());
    assertEquals("max@example.com", customer.getEmail());
    assertEquals("DE123456789", customer.getTaxId());
    assertEquals("No notes", customer.getNote());
  }

  @Test
  void testSecondConstructor() {
    Customer customer =
        new Customer(
            "Erika",
            "Mustermann",
            "12345",
            "Musterstadt",
            "Beispielweg",
            2,
            "9876543210",
            "erika@example.com",
            "DE987654321",
            "Keine");

    assertEquals(0, customer.getId()); // id default 0
    assertEquals("Erika", customer.getFirstName());
    assertEquals("Mustermann", customer.getLastName());
    assertEquals("12345", customer.getPostalCode());
    assertEquals("Musterstadt", customer.getCity());
    assertEquals("Beispielweg", customer.getStreet());
    assertEquals(2, customer.getHouseNumber());
    assertEquals("9876543210", customer.getPhone());
    assertEquals("erika@example.com", customer.getEmail());
    assertEquals("DE987654321", customer.getTaxId());
    assertEquals("Keine", customer.getNote());
  }

  @Test
  void testSetters() {
    Customer customer =
        new Customer(
            "Anna",
            "Schmidt",
            "00000",
            "test",
            "Straße",
            1,
            "000111222",
            "anna@example.com",
            "DE000111222",
            "");

    customer.setId(42);
    customer.setFirstName("Annette");
    customer.setLastName("Schneider");
    customer.setPostalCode("12345");
    customer.setCity("Musterstadt");
    customer.setStreet("Neue Straße");
    customer.setHouseNumber(4);
    customer.setPhone("999888777");
    customer.setEmail("annette@example.com");
    customer.setTaxId("DE999888777");
    customer.setNote("Wichtig");

    assertEquals(42, customer.getId());
    assertEquals("Annette", customer.getFirstName());
    assertEquals("Schneider", customer.getLastName());
    assertEquals("12345", customer.getPostalCode());
    assertEquals("Musterstadt", customer.getCity());
    assertEquals("Neue Straße", customer.getStreet());
    assertEquals(4, customer.getHouseNumber());
    assertEquals("999888777", customer.getPhone());
    assertEquals("annette@example.com", customer.getEmail());
    assertEquals("DE999888777", customer.getTaxId());
    assertEquals("Wichtig", customer.getNote());
  }

  @Test
  void testToString() {
    Customer customer =
        new Customer(
            "Lena",
            "Fischer",
            "12345",
            "Musterstadt",
            "Beispielweg",
            2,
            "0123456789",
            "lena@example.com",
            "DE123456789",
            "");
    assertEquals("Lena Fischer", customer.toString());
  }
}
