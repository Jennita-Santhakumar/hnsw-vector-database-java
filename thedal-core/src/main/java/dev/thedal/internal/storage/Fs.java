package dev.thedal.internal.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Filesystem helpers shared by storage and collection code. */
public final class Fs {

  private Fs() {}

  /**
   * fsyncs a directory so entries created, renamed or deleted in it are durable (skipped on
   * Windows, where directories cannot be opened as channels and NTFS journals metadata).
   */
  public static void fsyncDirectory(Path dir) {
    Wal.fsyncDirectory(dir);
  }

  /** Deletes a file tree; does nothing if it does not exist. */
  public static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> files = Files.walk(root)) {
      for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(p);
      }
    }
  }
}
