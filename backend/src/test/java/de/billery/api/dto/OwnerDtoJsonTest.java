package de.billery.api.dto;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The owner as the SPA sends it. Jackson 3 refuses a missing primitive, and the owner the API
 * returns names the Kleinunternehmer flag {@code smallBusiness}: an owner sent back as it was read
 * used to answer 400 ("Fehler beim Speichern des Inhabers").
 */
class OwnerDtoJsonTest {

  private final JsonMapper json = JsonMapper.builder().build();

  @Test
  void acceptsTheFlagUnderBothNamesAndWithout() {
    assertTrue(read("{\"lastName\":\"A\",\"isSmallBusiness\":true}").smallBusiness());
    assertTrue(read("{\"lastName\":\"A\",\"smallBusiness\":true}").smallBusiness());
    assertFalse(read("{\"lastName\":\"A\"}").smallBusiness());
    assertFalse(read("{\"lastName\":\"A\",\"isSmallBusiness\":null}").smallBusiness());
    assertFalse(read("{\"lastName\":\"A\",\"isSmallBusiness\":false}").smallBusiness());
  }

  private OwnerDto read(String body) {
    return json.readValue(body, OwnerDto.class);
  }
}
