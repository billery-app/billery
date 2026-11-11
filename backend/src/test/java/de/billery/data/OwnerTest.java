package de.billery.data;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OwnerTest {

  @Test
  void testConstructorAndGetters() {
    Owner owner =
        new Owner(
            1,
            "Max",
            "Mustermann",
            "Mustermann GmbH",
            "GmbH",
            "12345",
            "Musterstadt",
            "Musterstraße",
            1,
            "0123456789",
            "max@mustermann-gmbh.de",
            "https://www.mustermann-gmbh.de",
            "0",
            "DE123456789",
            false);

    assertEquals(1, owner.getId());
    assertEquals("Max", owner.getFirstName());
    assertEquals("Mustermann", owner.getLastName());
    assertEquals("Mustermann GmbH", owner.getCompanyName());
    assertEquals("GmbH", owner.getCompanyType());
    assertEquals("12345", owner.getPostalCode());
    assertEquals("Musterstadt", owner.getCity());
    assertEquals("Musterstraße", owner.getStreet());
    assertEquals(1, owner.getHouseNumber());
    assertEquals("0123456789", owner.getPhone());
    assertEquals("max@mustermann-gmbh.de", owner.getEmail());
    assertEquals("https://www.mustermann-gmbh.de", owner.getUrl());
    assertEquals("0", owner.getTaxId());
    assertEquals("DE123456789", owner.getIban());
    assertEquals(false, owner.getSmallBusiness());
  }

  @Test
  void testSetters() {
    Owner owner = new Owner(0, "", "", "", "", "", "", "", 0, "", "", "", "", "", false);

    owner.setId(42);
    owner.setFirstName("Anna");
    owner.setLastName("Schmidt");
    owner.setCompanyName("Schmidt & Partner");
    owner.setCompanyType("OHG");
    owner.setPostalCode("54321");
    owner.setCity("Neustadt");
    owner.setStreet("Neue Straße");
    owner.setHouseNumber(4);
    owner.setPhone("999888777");
    owner.setEmail("anna@schmidt-partner.de");
    owner.setUrl("https://www.schmidt-partner.de");
    owner.setTaxId("0");
    owner.setIban("DE987654321");

    assertEquals(42, owner.getId());
    assertEquals("Anna", owner.getFirstName());
    assertEquals("Schmidt", owner.getLastName());
    assertEquals("Schmidt & Partner", owner.getCompanyName());
    assertEquals("OHG", owner.getCompanyType());
    assertEquals("Neue Straße", owner.getStreet());
    assertEquals(4, owner.getHouseNumber());
    assertEquals("54321", owner.getPostalCode());
    assertEquals("Neustadt", owner.getCity());
    assertEquals("999888777", owner.getPhone());
    assertEquals("anna@schmidt-partner.de", owner.getEmail());
    assertEquals("https://www.schmidt-partner.de", owner.getUrl());
    assertEquals("0", owner.getTaxId());
    assertEquals("DE987654321", owner.getIban());
  }

  @Test
  void testEmptyConstructor() {
    Owner owner = new Owner(0, "", "", "", "", "", "", "", 0, "", "", "", "", "", false);

    assertEquals(0, owner.getId());
    assertEquals("", owner.getFirstName());
    assertEquals("", owner.getLastName());
    assertEquals("", owner.getCompanyName());
    assertEquals("", owner.getCompanyType());
    assertEquals("", owner.getStreet());
    assertEquals(0, owner.getHouseNumber());
    assertEquals("", owner.getPostalCode());
    assertEquals("", owner.getCity());
    assertEquals("", owner.getPhone());
    assertEquals("", owner.getEmail());
    assertEquals("", owner.getUrl());
    assertEquals("", owner.getTaxId());
    assertEquals("", owner.getIban());
    assertEquals(false, owner.getSmallBusiness());
  }
}
