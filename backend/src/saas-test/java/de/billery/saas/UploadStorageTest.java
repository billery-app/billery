package de.billery.saas;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import de.billery.saas.storage.DirectoryObjectStore;
import de.billery.saas.storage.ObjectStore;
import de.billery.saas.storage.ObjectStoreConfig;
import de.billery.saas.storage.S3ObjectStore;
import de.billery.saas.storage.TenantFileStorage;
import de.billery.saas.storage.UploadSigner;
import de.billery.saas.tenant.TenantContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tenant-scoped uploads: where files go, what the PDF renderer may read, what a signature opens.
 * The tenant rules run against both stores: a directory, and an S3-compatible service when {@code
 * S3_TEST_URL} is set ({@link S3TestStore}).
 */
class UploadStorageTest {

  /** A fresh random secret per run: nothing fixed that a scanner could mistake for a real one. */
  private static final String SECRET =
      java.util.UUID.randomUUID() + "-" + java.util.UUID.randomUUID();

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};

  @TempDir static Path dir;

  @AfterEach
  void clearTenant() {
    TenantContext.clear();
  }

  /** The stores every tenant rule is checked against, as factories over the temp directory. */
  static Stream<Arguments> stores() {
    List<Arguments> stores = new ArrayList<>();
    stores.add(Arguments.of("directory", (Function<Path, ObjectStore>) DirectoryObjectStore::new));
    if (S3TestStore.configured()) {
      stores.add(
          Arguments.of(
              "s3", (Function<Path, ObjectStore>) directory -> S3TestStore.CONFIGURED.store()));
    }
    return stores.stream();
  }

  private static TenantFileStorage storage(ObjectStore store) {
    return new TenantFileStorage(SECRET, store);
  }

  private static String nameOf(String url) {
    return url.substring(url.lastIndexOf('/') + 1, url.indexOf('?'));
  }

  /** Reads and closes what the endpoint would stream; fails if there is nothing. */
  private static byte[] bytes(Optional<InputStream> stream) throws IOException {
    try (InputStream in = stream.orElseThrow()) {
      return in.readAllBytes();
    }
  }

  private static String signatureOf(String url) {
    return url.substring(url.indexOf("sig=") + 4);
  }

  @Test
  void aSecretThatIsMissingOrShortIsRefused() {
    assertThrows(IllegalStateException.class, () -> new UploadSigner(null));
    assertThrows(IllegalStateException.class, () -> new UploadSigner("too-short"));
    assertThrows(
        IllegalStateException.class,
        () -> new TenantFileStorage("", new DirectoryObjectStore(dir)));
  }

  @Test
  void aSignatureBelongsToExactlyOneTenantAndFile() {
    UploadSigner signer = new UploadSigner(SECRET);
    String signature = signer.sign(1, "a.png");

    assertTrue(signer.verify(1, "a.png", signature));
    assertFalse(signer.verify(2, "a.png", signature), "another tenant");
    assertFalse(signer.verify(1, "b.png", signature), "another file");
    assertFalse(signer.verify(1, "a.png", signature + "x"));
    assertFalse(signer.verify(1, "a.png", null));
    assertFalse(new UploadSigner(SECRET + "other").verify(1, "a.png", signature), "other secret");
  }

  @Test
  void theDirectoryStoreKeepsAFileInTheActiveTenantsDirectory() throws Exception {
    TenantContext.set(7, "user");

    String url = storage(new DirectoryObjectStore(dir)).save(PNG, "png");

    assertTrue(url.matches("/uploads/7/[0-9a-f-]{36}\\.png\\?sig=[A-Za-z0-9_-]{43}"), url);
    assertArrayEquals(PNG, Files.readAllBytes(dir.resolve("7").resolve(nameOf(url))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stores")
  void savingNeedsATenantAndReturnsASignedUrl(String kind, Function<Path, ObjectStore> store)
      throws Exception {
    TenantContext.set(7, "user");
    String url = storage(store.apply(dir)).save(PNG, "png");

    assertTrue(url.matches("/uploads/7/[0-9a-f-]{36}\\.png\\?sig=[A-Za-z0-9_-]{43}"), url);
    assertArrayEquals(PNG, storage(store.apply(dir)).read(url).orElseThrow());
    TenantContext.clear();
    assertThrows(IllegalStateException.class, () -> storage(store.apply(dir)).save(PNG, "png"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stores")
  void theRendererReadsOnlyFilesOfTheActiveTenant(String kind, Function<Path, ObjectStore> store)
      throws Exception {
    TenantFileStorage storage = storage(store.apply(dir));
    TenantContext.set(1, "user");
    String own = storage.save(PNG, "png");
    TenantContext.set(2, "other");
    String foreign = storage.save(PNG, "png");

    TenantContext.set(1, "user");
    assertArrayEquals(
        PNG,
        storage.read("https://app.example.com" + own).orElseThrow(),
        "host and query are ignored");
    assertTrue(storage.read(foreign).isEmpty(), "tenant 2's logo, even with its valid signature");
    assertTrue(
        storage.read("/uploads/1/" + nameOf(foreign)).isEmpty(), "tenant 2's name under tenant 1");
    assertTrue(
        storage.read("/uploads/01/" + nameOf(own)).isEmpty(), "the tenant must match exactly");
    TenantContext.clear();
    assertTrue(storage.read(own).isEmpty(), "no tenant, no file");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stores")
  void pathsThatLeaveTheTenantsFilesAreRefused(String kind, Function<Path, ObjectStore> store)
      throws Exception {
    TenantFileStorage storage = storage(store.apply(dir));
    TenantContext.set(1, "user");
    Files.writeString(dir.resolve("secret.txt"), "x");

    assertTrue(storage.read("/uploads/1/../secret.txt").isEmpty());
    assertTrue(storage.read("/uploads/1/..%2Fsecret.txt").isEmpty());
    assertTrue(storage.read("/uploads/1/a/b.png").isEmpty());
    assertTrue(storage.read("file:///etc/passwd").isEmpty());
    assertTrue(
        storage.read("/uploads/secret.txt").isEmpty(),
        "the flat self-host layout is not valid here");
    assertTrue(storage.read("/uploads/1/missing.png").isEmpty());
    assertTrue(storage.read(null).isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("stores")
  void theEndpointNeedsTheSignatureOfThatTenantAndFile(
      String kind, Function<Path, ObjectStore> store) throws Exception {
    TenantFileStorage storage = storage(store.apply(dir));
    TenantContext.set(1, "user");
    String url = storage.save(PNG, "png");
    String name = nameOf(url);
    String signature = signatureOf(url);
    TenantContext.clear();

    assertArrayEquals(PNG, bytes(storage.openSigned(1, name, signature)));
    assertTrue(storage.openSigned(2, name, signature).isEmpty(), "same name under tenant 2");
    assertTrue(storage.openSigned(1, name, "forged").isEmpty());
    assertTrue(storage.openSigned(1, name, null).isEmpty());
    assertTrue(storage.openSigned(1, "../" + name, signature).isEmpty());
  }

  /**
   * The point of the object store: two backend instances, each with a client of its own and no
   * shared disk, serve each other's uploads.
   */
  @Test
  void twoInstancesWithoutASharedDiskServeEachOthersUploads() throws Exception {
    assumeTrue(S3TestStore.configured(), "S3_TEST_URL is not set");
    TenantFileStorage first = storage(S3TestStore.CONFIGURED.store());
    TenantFileStorage second = storage(S3TestStore.CONFIGURED.store());

    TenantContext.set(3, "user");
    String url = first.save(PNG, "png");
    assertArrayEquals(PNG, second.read(url).orElseThrow(), "the renderer of the other instance");
    TenantContext.clear();
    assertArrayEquals(
        PNG,
        bytes(second.openSigned(3, nameOf(url), signatureOf(url))),
        "the endpoint of the other instance");
  }

  /** A store that cannot be read is an error, never "no such file". */
  @Test
  void anUnreadableStoreIsAnErrorNotAMissingFile() throws Exception {
    assumeTrue(S3TestStore.configured(), "S3_TEST_URL is not set");
    S3TestStore s3 = S3TestStore.CONFIGURED;
    TenantContext.set(1, "user");
    String url = storage(s3.store()).save(PNG, "png");
    TenantFileStorage wrongKey =
        storage(
            new S3ObjectStore(
                S3ObjectStore.newClient(s3.endpoint(), "us-east-1", s3.accessKey(), "wrong", true),
                s3.bucket()));

    assertThrows(IOException.class, () -> wrongKey.read(url));
    assertThrows(IOException.class, () -> wrongKey.save(PNG, "png"));
  }

  @Test
  void theStoreIsChosenByConfigurationAndAnIncompleteOneStopsTheStart() {
    ObjectStoreConfig config = new ObjectStoreConfig();

    assertInstanceOf(
        DirectoryObjectStore.class,
        config.objectStore("local", dir.toString(), "", "", "", "", "", true));
    assertInstanceOf(
        DirectoryObjectStore.class,
        config.objectStore(" ", dir.toString(), "", "", "", "", "", true),
        "unset means local");
    assertInstanceOf(
        S3ObjectStore.class,
        config.objectStore("S3", "", "http://127.0.0.1:1", "", "bucket", "key", "secret", true));
    assertThrows(
        IllegalStateException.class,
        () -> config.objectStore("ftp", "", "", "", "", "", "", true),
        "an unknown store");
    assertThrows(
        IllegalStateException.class,
        () -> config.objectStore("s3", "", "http://127.0.0.1:1", "", "", "key", "secret", true),
        "no bucket");
    assertThrows(
        IllegalStateException.class,
        () -> config.objectStore("s3", "", "http://127.0.0.1:1", "", "bucket", "key", "", true),
        "a key without its secret");
  }
}
