package de.billery.saas.storage;

import de.billery.config.AppConfig;
import java.nio.file.Paths;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Chooses where the SaaS uploads are kept ({@code BILLERY_FILES_STORE}):
 *
 * <ul>
 *   <li>{@code local} (default): a directory, {@code BILLERY_FILES_DIR} or {@code FILES_PATH}. One
 *       instance, or several on a shared volume.
 *   <li>{@code s3}: a bucket of an S3-compatible store ({@code BILLERY_FILES_S3_*}). Several
 *       instances without a shared disk.
 * </ul>
 *
 * An unknown value or an incomplete S3 configuration stops the start.
 */
@Configuration
@Profile("saas")
public class ObjectStoreConfig {

  /**
   * The configured store.
   *
   * @param store {@code local} or {@code s3}
   * @param directory the directory of the local store; empty for {@code FILES_PATH}
   * @param endpoint the S3 endpoint; empty for AWS S3
   * @param region the S3 region
   * @param bucket the S3 bucket
   * @param accessKey the S3 access key id; empty for the AWS default credential chain
   * @param secretKey the S3 secret access key
   * @param pathStyle path-style bucket addressing
   * @return the store
   */
  @Bean
  public ObjectStore objectStore(
      @Value("${billery.files.store:${BILLERY_FILES_STORE:local}}") String store,
      @Value("${billery.files.dir:}") String directory,
      @Value("${billery.files.s3.endpoint:${BILLERY_FILES_S3_ENDPOINT:}}") String endpoint,
      @Value("${billery.files.s3.region:${BILLERY_FILES_S3_REGION:}}") String region,
      @Value("${billery.files.s3.bucket:${BILLERY_FILES_S3_BUCKET:}}") String bucket,
      @Value("${billery.files.s3.access-key:${BILLERY_FILES_S3_ACCESS_KEY:}}") String accessKey,
      @Value("${billery.files.s3.secret-key:${BILLERY_FILES_S3_SECRET_KEY:}}") String secretKey,
      @Value("${billery.files.s3.path-style:${BILLERY_FILES_S3_PATH_STYLE:true}}")
          boolean pathStyle) {
    return switch (store.trim().toLowerCase(Locale.ROOT)) {
      case "", "local" ->
          new DirectoryObjectStore(
              directory.isBlank() ? AppConfig.FILES_PATH : Paths.get(directory));
      case "s3" ->
          new S3ObjectStore(
              S3ObjectStore.newClient(endpoint, region, accessKey, secretKey, pathStyle), bucket);
      default ->
          throw new IllegalStateException(
              "BILLERY_FILES_STORE must be 'local' or 's3', not '" + store + "'");
    };
  }
}
