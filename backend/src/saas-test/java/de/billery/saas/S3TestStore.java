package de.billery.saas;

import de.billery.saas.storage.S3ObjectStore;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;

/**
 * The S3-compatible service the tests run against, given as {@code S3_TEST_URL=http://<access
 * key>:<secret key>@<host>:<port>/<bucket>} (CI: a SeaweedFS container, see {@code
 * .github/workflows/maven.yml}). Without the variable the S3 tests are skipped and the SaaS suite
 * keeps its uploads in a directory.
 *
 * @param endpoint the service URL without credentials and bucket
 * @param accessKey the access key id
 * @param secretKey the secret access key
 * @param bucket the bucket (created if missing)
 */
record S3TestStore(String endpoint, String accessKey, String secretKey, String bucket) {

  /** The configured service, or {@code null} when {@code S3_TEST_URL} is not set. */
  static final S3TestStore CONFIGURED = parse(System.getenv("S3_TEST_URL"));

  static boolean configured() {
    return CONFIGURED != null;
  }

  private static S3TestStore parse(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    URI uri = URI.create(url);
    String[] credentials = uri.getRawUserInfo().split(":", 2);
    String bucket = uri.getPath().replaceAll("^/+|/+$", "");
    S3TestStore store =
        new S3TestStore(
            uri.getScheme() + "://" + uri.getHost() + ":" + uri.getPort(),
            URLDecoder.decode(credentials[0], StandardCharsets.UTF_8),
            URLDecoder.decode(credentials[1], StandardCharsets.UTF_8),
            bucket);
    store.createBucket();
    return store;
  }

  /**
   * A client of its own, as each backend instance has.
   *
   * @return a new client
   */
  S3Client client() {
    return S3ObjectStore.newClient(endpoint, "us-east-1", accessKey, secretKey, true);
  }

  /**
   * A store on a client of its own.
   *
   * @return a new store
   */
  S3ObjectStore store() {
    return new S3ObjectStore(client(), bucket);
  }

  private void createBucket() {
    try (S3Client client = client()) {
      client.createBucket(b -> b.bucket(bucket));
    } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException e) {
      // Created by an earlier test class or run.
    }
  }
}
