package de.billery.saas.storage;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Optional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Objects in a bucket of an S3-compatible store (AWS S3, Supabase Storage, SeaweedFS, MinIO, ...),
 * key {@code <tenantId>/<name>}. Every backend instance reads the same bucket, so instances without
 * a shared disk serve each other's uploads.
 *
 * <p>The bucket stays private: browsers never talk to it. They load {@code
 * /uploads/<tenant>/<name>?sig=...} from the backend ({@code UploadController}), which checks the
 * signature and then reads the object here.
 */
public class S3ObjectStore implements ObjectStore {

  private final S3Client client;
  private final String bucket;

  /**
   * Uses an existing client (tests, or a client configured elsewhere).
   *
   * @param client the S3 client
   * @param bucket the bucket
   */
  public S3ObjectStore(S3Client client, String bucket) {
    if (bucket == null || bucket.isBlank()) {
      throw new IllegalStateException(
          "BILLERY_FILES_S3_BUCKET is required when BILLERY_FILES_STORE=s3");
    }
    this.client = client;
    this.bucket = bucket;
  }

  /**
   * Builds a client from the configuration.
   *
   * @param endpoint the endpoint of an S3-compatible service, e.g. {@code
   *     https://<project>.supabase.co/storage/v1/s3}; empty for AWS S3 itself
   * @param region the region; S3-compatible services usually accept any, Supabase wants the
   *     project's region
   * @param accessKey the access key id; empty for the AWS default credential chain
   * @param secretKey the secret access key
   * @param pathStyle {@code true} for {@code <endpoint>/<bucket>/<key>} URLs (needed by most
   *     S3-compatible services), {@code false} for virtual-host style
   * @return the client
   */
  public static S3Client newClient(
      String endpoint, String region, String accessKey, String secretKey, boolean pathStyle) {
    S3ClientBuilder builder =
        S3Client.builder()
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .region(Region.of(region == null || region.isBlank() ? "us-east-1" : region))
            .forcePathStyle(pathStyle)
            .credentialsProvider(credentials(accessKey, secretKey))
            // Newer SDK versions add CRC checksums to every request by default, which several
            // S3-compatible services reject; send and check them only where S3 requires them.
            .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
            .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
    if (endpoint != null && !endpoint.isBlank()) {
      builder.endpointOverride(URI.create(endpoint));
    }
    return builder.build();
  }

  private static AwsCredentialsProvider credentials(String accessKey, String secretKey) {
    boolean hasKey = accessKey != null && !accessKey.isBlank();
    boolean hasSecret = secretKey != null && !secretKey.isBlank();
    if (hasKey != hasSecret) {
      throw new IllegalStateException(
          "Set both BILLERY_FILES_S3_ACCESS_KEY and BILLERY_FILES_S3_SECRET_KEY, or neither"
              + " (then the AWS default credential chain is used)");
    }
    return hasKey
        ? StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
        : DefaultCredentialsProvider.builder().build();
  }

  @Override
  public void put(String key, byte[] content) throws IOException {
    try {
      client.putObject(
          PutObjectRequest.builder().bucket(bucket).key(key).build(),
          RequestBody.fromBytes(content));
    } catch (SdkException e) {
      throw new IOException("Upload to the object store failed", e);
    }
  }

  @Override
  public Optional<InputStream> open(String key) throws IOException {
    try {
      return Optional.of(
          client.getObject(GetObjectRequest.builder().bucket(bucket).key(key).build()));
    } catch (NoSuchKeyException e) {
      return Optional.empty();
    } catch (SdkException e) {
      throw new IOException("Reading from the object store failed", e);
    }
  }
}
