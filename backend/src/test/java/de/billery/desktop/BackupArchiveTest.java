package de.billery.desktop;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The backup file format: what is accepted, what is refused before anything is written, and that
 * unpacking cannot leave its folder ("Zip Slip").
 */
class BackupArchiveTest {

  @TempDir Path dir;

  @Test
  void aValidBackupIsUnpackedIntoItsFolder() throws Exception {
    Path zip = zip("ok.zip", "backup.json", "database.mv.db", "files/logo.png");
    Path target = dir.resolve("work");

    BackupArchive.extract(zip, target);

    assertTrue(Files.exists(target.resolve("database.mv.db")));
    assertEquals("files/logo.png", Files.readString(target.resolve("files/logo.png")));
  }

  @Test
  void entriesWithAPathOrOutsideTheFormatAreRefused() throws Exception {
    for (String evil :
        new String[] {"files/../evil.sh", "files/sub/x.png", "../x", "other.txt", "files/.."}) {
      Path zip = zip("evil.zip", "backup.json", "database.mv.db", evil);
      assertThrows(IOException.class, () -> BackupArchive.validate(zip), evil);
      assertThrows(IOException.class, () -> BackupArchive.extract(zip, dir.resolve("w")), evil);
    }
    assertFalse(Files.exists(dir.resolve("evil.sh")));
  }

  @Test
  void aSecondDatabaseOrDescriptionIsRefused() throws Exception {
    // The check would read the first database, the restore would write the second.
    for (String twice : new String[] {"database.mv.db", "backup.json", "files/logo.png"}) {
      // ZipOutputStream refuses duplicates; write a look-alike name, then patch the raw bytes.
      String lookAlike = twice.substring(0, twice.length() - 1) + "Q";
      Path zip = zip("twice.zip", "backup.json", "database.mv.db", "files/logo.png", lookAlike);
      byte[] raw = Files.readAllBytes(zip);
      String patched = new String(raw, StandardCharsets.ISO_8859_1).replace(lookAlike, twice);
      Files.write(zip, patched.getBytes(StandardCharsets.ISO_8859_1));
      IOException e = assertThrows(IOException.class, () -> BackupArchive.validate(zip), twice);
      assertTrue(e.getMessage().contains("Duplicate"), e.getMessage());
      assertThrows(
          IOException.class, () -> BackupArchive.extract(zip, dir.resolve("w-" + twice.length())));
    }
  }

  @Test
  void aZipWithoutDatabaseOrDescriptionIsNoBackup() throws Exception {
    assertThrows(IOException.class, () -> BackupArchive.validate(zip("a.zip", "backup.json")));
    assertThrows(IOException.class, () -> BackupArchive.validate(zip("b.zip", "database.mv.db")));
  }

  @Test
  void tooManyEntriesAreRefusedBeforeAnythingIsReplaced() throws Exception {
    String[] names = new String[BackupArchive.MAX_ENTRIES + 1];
    names[0] = "backup.json";
    names[1] = "database.mv.db";
    for (int i = 2; i < names.length; i++) {
      names[i] = "files/f" + i;
    }
    Path zip = zip("many.zip", names);

    IOException e = assertThrows(IOException.class, () -> BackupArchive.validate(zip));
    assertTrue(e.getMessage().contains("too many"));
  }

  @Test
  void theFolderGuardRefusesEveryWayOut() throws Exception {
    assertEquals(dir.resolve("a.png"), BackupArchive.inside(dir, "a.png"));
    assertThrows(IOException.class, () -> BackupArchive.inside(dir, "../a.png"));
    assertThrows(IOException.class, () -> BackupArchive.inside(dir, "."));
    assertThrows(IOException.class, () -> BackupArchive.inside(dir, "/etc/passwd"));
  }

  @Test
  void encryptionNeedsTheRightPassword() throws Exception {
    Path zip = zip("plain.zip", "backup.json", "database.mv.db");
    Path encrypted = dir.resolve("x.zip.enc");
    BackupArchive.encrypt(zip, encrypted, BackupArchive.newKeys("geheim123".toCharArray()));

    assertTrue(BackupArchive.isEncrypted(encrypted));
    assertThrows(
        BackupArchive.WrongPasswordException.class,
        () -> BackupArchive.decrypt(encrypted, dir.resolve("bad.zip"), "falsch123".toCharArray()));
    BackupArchive.decrypt(encrypted, dir.resolve("back.zip"), "geheim123".toCharArray());
    assertArrayEquals(Files.readAllBytes(zip), Files.readAllBytes(dir.resolve("back.zip")));
  }

  /** A ZIP whose entries contain their own names. */
  private Path zip(String name, String... entries) throws IOException {
    Path file = dir.resolve(name);
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(file))) {
      for (String entry : entries) {
        out.putNextEntry(new ZipEntry(entry));
        out.write(entry.getBytes(StandardCharsets.UTF_8));
        out.closeEntry();
      }
    }
    return file;
  }

  @Test
  void aChangedEncryptedFileIsRefusedNotRestored() throws Exception {
    Path zip = zip("plain.zip", "backup.json", "database.mv.db");
    Path encrypted = dir.resolve("x.zip.enc");
    BackupArchive.encrypt(zip, encrypted, BackupArchive.newKeys("geheim123".toCharArray()));
    byte[] bytes = Files.readAllBytes(encrypted);
    bytes[bytes.length - 5] ^= 1; // one bit of the content
    Files.write(encrypted, bytes);

    assertThrows(
        BackupArchive.WrongPasswordException.class,
        () -> BackupArchive.decrypt(encrypted, dir.resolve("out.zip"), "geheim123".toCharArray()));
  }

  @Test
  void theSnapshotTimeIsOnlyTrustedWhenThisInstallationSignedItForThisDatabase() {
    java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 11, 14, 12, 0);
    String name = BackupNames.name(now.minusHours(2), BackupNames.MANUAL, false);
    java.time.LocalDateTime snapshot = now.minusHours(2).plusNanos(5_000_000);
    byte[] key =
        "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] otherKey =
        "fedcba9876543210fedcba9876543210".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    String db = "ab".repeat(32);
    String otherDb = "cd".repeat(32);

    assertEquals(
        snapshot,
        BackupNames.trustedSnapshot(
            BackupNames.meta(BackupNames.MANUAL, snapshot, db, key), name, now, key, db));
    String[][] untrusted = {
      {BackupNames.meta(BackupNames.MANUAL, snapshot, db, new byte[0]), db}, // not signed
      {BackupNames.meta(BackupNames.MANUAL, snapshot, db, otherKey), db}, // another installation
      {BackupNames.meta(BackupNames.MANUAL, snapshot, db, key), otherDb}, // database exchanged
      {BackupNames.meta(BackupNames.MANUAL, snapshot, db, key).replace("manuell", "beenden"), db},
      {BackupNames.meta(BackupNames.MANUAL, now.plusDays(1), db, key), db}, // in the future
      {BackupNames.meta(BackupNames.MANUAL, now.minusHours(1), db, key), db}, // after the name
      {"{\"format\":1}", db} // none
    };
    for (String[] c : untrusted) {
      assertEquals(
          BackupNames.EVERYTHING, BackupNames.trustedSnapshot(c[0], name, now, key, c[1]), c[0]);
    }
    assertEquals(
        BackupNames.EVERYTHING,
        BackupNames.trustedSnapshot(
            BackupNames.meta(BackupNames.MANUAL, snapshot, db, key), name, now, new byte[0], db),
        "an installation without a key trusts nothing");
  }
}
