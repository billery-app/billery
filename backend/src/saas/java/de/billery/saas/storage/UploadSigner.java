package de.billery.saas.storage;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs the URLs of uploaded files. A logo is loaded by the browser in an {@code <img>} tag, which
 * cannot send an {@code Authorization} header, so the URL itself proves the right to read the file:
 * {@code /uploads/<tenant>/<name>?sig=<HMAC>}. The signature covers tenant and file name with a
 * secret only the server knows, so a URL cannot be forged and a tenant's signature does not open
 * another tenant's file. The signature does not expire (the URL is stored in templates); rotating
 * the secret invalidates every URL.
 */
public final class UploadSigner {

  private static final int MIN_SECRET_LENGTH = 32;

  private final byte[] secret;

  /**
   * Creates the signer.
   *
   * @param secret the signing secret, at least 32 characters
   * @throws IllegalStateException if the secret is missing or too short
   */
  public UploadSigner(String secret) {
    if (secret == null || secret.length() < MIN_SECRET_LENGTH) {
      throw new IllegalStateException(
          "The saas profile needs billery.files.secret (BILLERY_FILES_SECRET) with at least "
              + MIN_SECRET_LENGTH
              + " characters");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * The signature of a file.
   *
   * @param tenantId the owning tenant
   * @param name the file name
   * @return the URL-safe signature
   */
  public String sign(int tenantId, String name) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      byte[] digest =
          mac.doFinal(("uploads/" + tenantId + "/" + name).getBytes(StandardCharsets.UTF_8));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }

  /**
   * Checks a signature in constant time.
   *
   * @param tenantId the tenant named in the URL
   * @param name the file name named in the URL
   * @param signature the signature from the URL, may be null
   * @return true if it is the signature of exactly this tenant and file
   */
  public boolean verify(int tenantId, String name, String signature) {
    if (signature == null) {
      return false;
    }
    return MessageDigest.isEqual(
        sign(tenantId, name).getBytes(StandardCharsets.UTF_8),
        signature.getBytes(StandardCharsets.UTF_8));
  }
}
