package de.billery.data;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppProfilesTest {

  private String before;

  @BeforeEach
  void remember() {
    before = System.getProperty("spring.profiles.active");
  }

  @AfterEach
  void restore() {
    if (before == null) {
      System.clearProperty("spring.profiles.active");
    } else {
      System.setProperty("spring.profiles.active", before);
    }
  }

  @Test
  void saasIsRecognisedInASingleValueAndInAList() {
    System.setProperty("spring.profiles.active", "saas");
    assertTrue(AppProfiles.isSaas());
    System.setProperty("spring.profiles.active", "desktop, SaaS ,other");
    assertTrue(AppProfiles.isSaas());
  }

  @Test
  void aSaasBuildWithoutTheProfileRefusesToStart() {
    assumeTrue(AppProfiles.isSaasBuild(), "only the SaaS build (mvn -Psaas) contains the marker");
    System.setProperty("spring.profiles.active", "prod");
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, AppProfiles::requireProfileForBuild);
    assertTrue(refused.getMessage().contains("without any authentication"), refused.getMessage());
  }

  @Test
  void aSaasBuildWithTheProfileStarts() {
    assumeTrue(AppProfiles.isSaasBuild(), "only the SaaS build (mvn -Psaas) contains the marker");
    System.setProperty("spring.profiles.active", "saas");
    assertDoesNotThrow(AppProfiles::requireProfileForBuild);
    System.setProperty("spring.profiles.active", "desktop,saas");
    assertDoesNotThrow(AppProfiles::requireProfileForBuild);
  }

  @Test
  void aSelfHostBuildDoesNotNeedAProfile() {
    assumeFalse(AppProfiles.isSaasBuild(), "the SaaS build is covered by the tests above");
    System.clearProperty("spring.profiles.active");
    assertDoesNotThrow(AppProfiles::requireProfileForBuild);
  }

  @Test
  void otherProfilesAreNotSaas() {
    System.setProperty("spring.profiles.active", "desktop");
    assertFalse(AppProfiles.isSaas());
    System.setProperty("spring.profiles.active", "saasless");
    assertFalse(AppProfiles.isSaas());
  }
}
