package de.billery.tenant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DefaultCurrentTenantTest {

  @Test
  void selfHostHasExactlyTheDefaultTenant() {
    assertEquals(1, new DefaultCurrentTenant().tenantId());
    assertEquals(CurrentTenant.DEFAULT_TENANT_ID, new DefaultCurrentTenant().tenantId());
  }
}
