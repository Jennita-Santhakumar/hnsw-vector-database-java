package dev.thedal.internal.storage;

import dev.thedal.collection.CollectionConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Collection snapshots: {@code snapshot-<lsn>/} directories with {@code header.bin} (magic {@code
 * THDL}, format version, LSN, collection config) plus the data files a {@link PartsWriter} writes.
 *
 * <p>Writing is crash-safe: everything goes to a temporary directory, each file is fsynced, the
 * directory is fsynced, then it is atomically renamed to its final name and the parent is fsynced.
 * A crash leaves either the old snapshot or the new one, never half of one.
 */
public final class Snapshot {

  /** "THDL" in ASCII. */
  public static final int MAGIC = 0x5448444C;

  /** Current on-disk format version. */
  public static final int FORMAT_VERSION = 1;

  /** Snapshots kept after a new one is written: the newest plus one fallback. */
  public static final int KEEP = 2;

  private static final Pattern NAME = Pattern.compile("snapshot-(\\d{20})");
  private static final String TMP_PREFIX = "tmp-snapshot-";

  /** Writes the data files of a snapshot into a directory. */
  @FunctionalInterface
  public interface PartsWriter {
    /** Writes the parts into {@code dir}; every file must be fsynced (use {@link SnapshotIo}). */
    void writeParts(Path dir);
  }

  /**
   * A validated snapshot header.
   *
   * @param dir the snapshot directory, for reading the data files
   */
  public record Header(long lsn, CollectionConfig config, Path dir) {}

  private Snapshot() {}

  /** Final directory name for a snapshot at {@code lsn}. */
  public static String dirName(long lsn) {
    return String.format(Locale.ROOT, "snapshot-%020d", lsn);
  }

  /**
   * Writes a snapshot of the state at {@code lsn} into {@code collectionDir} and deletes snapshots
   * beyond the newest {@link #KEEP}.
   *
   * @return the snapshot directory
   */
  public static Path write(
      Path collectionDir, long lsn, CollectionConfig config, PartsWriter parts) {
    try {
      Files.createDirectories(collectionDir);
      Path tmp = Files.createTempDirectory(collectionDir, TMP_PREFIX);
      SnapshotIo.writeFile(
          tmp.resolve("header.bin"),
          out -> {
            out.writeInt(MAGIC);
            out.writeInt(FORMAT_VERSION);
            out.writeLong(lsn);
            byte[] configBytes = new WalEntry.CreateCollection(config).encode();
            out.writeInt(configBytes.length);
            out.write(configBytes);
          });
      parts.writeParts(tmp);
      Wal.fsyncDirectory(tmp);
      Path target = collectionDir.resolve(dirName(lsn));
      if (Files.exists(target)) {
        deleteRecursively(target); // an older snapshot at the same LSN holds the same state
      }
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      Wal.fsyncDirectory(collectionDir);
      prune(collectionDir);
      return target;
    } catch (IOException e) {
      throw new UncheckedIOException("writing snapshot " + lsn + " in " + collectionDir, e);
    }
  }

  /**
   * Loads the newest snapshot that validates, falling back to older ones; leftover temporary
   * directories are removed first.
   *
   * @param loader reads the data files of a validated header; throws {@link
   *     SnapshotCorruptedException} to reject the snapshot
   * @return empty if there is no snapshot at all
   * @throws SnapshotCorruptedException if snapshots exist but none is valid
   */
  public static <T> Optional<T> loadLatest(Path collectionDir, Function<Header, T> loader) {
    removeTemporaryDirs(collectionDir);
    List<Path> snapshots = list(collectionDir);
    SnapshotCorruptedException last = null;
    for (int i = snapshots.size() - 1; i >= 0; i--) {
      try {
        return Optional.of(loader.apply(readHeader(snapshots.get(i))));
      } catch (SnapshotCorruptedException e) {
        last = e;
      }
    }
    if (last != null) {
      throw new SnapshotCorruptedException("no valid snapshot in " + collectionDir, last);
    }
    return Optional.empty();
  }

  /** Snapshot directories in {@code collectionDir}, oldest first. */
  public static List<Path> list(Path collectionDir) {
    if (!Files.isDirectory(collectionDir)) {
      return List.of();
    }
    try (Stream<Path> entries = Files.list(collectionDir)) {
      List<Path> found = new ArrayList<>(entries.filter(p -> lsnOf(p) >= 0).toList());
      found.sort(Comparator.comparingLong(Snapshot::lsnOf));
      return found;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** LSN encoded in a snapshot directory name, or -1. */
  public static long lsnOf(Path dir) {
    Path name = dir.getFileName();
    if (name == null) {
      return -1;
    }
    Matcher m = NAME.matcher(name.toString());
    return m.matches() ? Long.parseLong(m.group(1)) : -1;
  }

  /** Reads and validates {@code header.bin} of a snapshot directory. */
  public static Header readHeader(Path dir) {
    return SnapshotIo.readFile(
        dir.resolve("header.bin"),
        in -> {
          int magic = in.readInt();
          if (magic != MAGIC) {
            throw new SnapshotCorruptedException("bad magic in " + dir, null);
          }
          int version = in.readInt();
          if (version != FORMAT_VERSION) {
            throw new SnapshotCorruptedException(
                "unsupported snapshot format " + version + " in " + dir, null);
          }
          long lsn = in.readLong();
          if (lsn != lsnOf(dir)) {
            throw new SnapshotCorruptedException(
                "header LSN " + lsn + " does not match " + dir, null);
          }
          byte[] configBytes = new byte[in.readInt()];
          in.readFully(configBytes);
          WalEntry entry =
              WalEntry.decode(new WalRecord(WalEntry.OP_CREATE_COLLECTION, lsn, configBytes));
          return new Header(lsn, ((WalEntry.CreateCollection) entry).config(), dir);
        });
  }

  private static void prune(Path collectionDir) throws IOException {
    List<Path> snapshots = list(collectionDir);
    for (int i = 0; i < snapshots.size() - KEEP; i++) {
      deleteRecursively(snapshots.get(i));
    }
  }

  private static void removeTemporaryDirs(Path collectionDir) {
    if (!Files.isDirectory(collectionDir)) {
      return;
    }
    try (Stream<Path> entries = Files.list(collectionDir)) {
      for (Path p : entries.toList()) {
        Path name = p.getFileName();
        if (name != null && name.toString().startsWith(TMP_PREFIX)) {
          deleteRecursively(p);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static void deleteRecursively(Path dir) throws IOException {
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
