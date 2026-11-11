package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;

import de.billery.saas.identity.Role;
import org.junit.jupiter.api.Test;

class RoleTest {

  @Test
  void aReaderMayOnlyReadEveryoneElseMayAlsoWrite() {
    for (String read : new String[] {"GET", "HEAD", "OPTIONS"}) {
      assertTrue(Role.READER.allows(read), read);
    }
    for (String write : new String[] {"POST", "PUT", "PATCH", "DELETE"}) {
      assertFalse(Role.READER.allows(write), write);
      assertTrue(Role.ACCOUNTANT.allows(write), write);
      assertTrue(Role.ADMIN.allows(write), write);
      assertTrue(Role.OWNER.allows(write), write);
    }
  }
}
