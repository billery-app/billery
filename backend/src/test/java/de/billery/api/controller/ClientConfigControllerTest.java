package de.billery.api.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ClientConfigControllerTest {

  @Test
  void aSelfHostedInstanceHasNoSignIn() {
    assertEquals(Map.of("mode", "selfhost"), new ClientConfigController().config());
  }
}
