package de.billery.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

/** {@link CredentialCipher}: round trip, the tenant binding and a missing or wrong key. */
class CredentialCipherTest {

  private static final byte[] KEY = new byte[32];

  static {
    for (int i = 0; i < KEY.length; i++) {
      KEY[i] = (byte) i;
    }
  }

  @Test
  void encryptsAndDecryptsWithoutStoringThePlainText() {
    CredentialCipher cipher = CredentialCipher.withKey(KEY, () -> 1);
    String stored = cipher.encrypt("geheim-ß");
    assertTrue(stored.startsWith(CredentialCipher.VERSION), stored);
    assertFalse(stored.contains("geheim"), stored);
    assertEquals("geheim-ß", cipher.decrypt(stored));
    assertNotEquals(stored, cipher.encrypt("geheim-ß"), "a fresh IV per value");
  }

  @Test
  void aValueOfAnotherCompanyDoesNotDecrypt() {
    String stored = CredentialCipher.withKey(KEY, () -> 1).encrypt("geheim");
    CredentialCipher other = CredentialCipher.withKey(KEY, () -> 2);
    assertThrows(IllegalStateException.class, () -> other.decrypt(stored));
  }

  @Test
  void anotherKeyDoesNotDecrypt() {
    String stored = CredentialCipher.withKey(KEY, () -> 1).encrypt("geheim");
    byte[] otherKey = KEY.clone();
    otherKey[0] = 99;
    assertThrows(
        IllegalStateException.class,
        () -> CredentialCipher.withKey(otherKey, () -> 1).decrypt(stored));
  }

  @Test
  void withoutKeyNothingIsStored() {
    CredentialCipher none = CredentialCipher.none(() -> 1);
    assertFalse(none.available());
    assertThrows(IllegalStateException.class, () -> none.encrypt("geheim"));
  }

  @Test
  void theConfiguredKeyMustBe32BytesOfBase64() {
    String good = Base64.getEncoder().encodeToString(KEY);
    assertTrue(new CredentialCipher(good, () -> 1).available());
    assertThrows(IllegalStateException.class, () -> new CredentialCipher("not base64!", () -> 1));
    String short16 = Base64.getEncoder().encodeToString(new byte[16]);
    assertThrows(IllegalStateException.class, () -> new CredentialCipher(short16, () -> 1));
  }
}
