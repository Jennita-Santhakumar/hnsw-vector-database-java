package dev.thedal.internal.storage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * Snapshot files: contents followed by a big-endian u32 CRC32 of the contents. Writes are fsynced
 * before returning; reads verify the CRC and that nothing follows it.
 */
public final class SnapshotIo {

  /** Writes a file's contents. */
  @FunctionalInterface
  public interface Writer {
    /** Writes the contents. */
    void write(DataOutputStream out) throws IOException;
  }

  /** Reads a file's contents. */
  @FunctionalInterface
  public interface Reader<T> {
    /** Reads the contents. */
    T read(DataInputStream in) throws IOException;
  }

  private SnapshotIo() {}

  /** Writes {@code file} with a CRC32 footer and fsyncs it. */
  public static void writeFile(Path file, Writer writer) {
    CRC32 crc = new CRC32();
    try (FileOutputStream fos = new FileOutputStream(file.toFile());
        BufferedOutputStream buffered = new BufferedOutputStream(fos, 1 << 16);
        CheckedOutputStream checked = new CheckedOutputStream(buffered, crc);
        DataOutputStream data = new DataOutputStream(checked)) {
      writer.write(data);
      data.flush();
      int value = (int) crc.getValue();
      buffered.write(
          new byte[] {
            (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value
          });
      buffered.flush();
      fos.getFD().sync();
    } catch (IOException e) {
      throw new UncheckedIOException("writing " + file, e);
    }
  }

  /**
   * Reads {@code file}, verifying its CRC32 footer.
   *
   * @throws SnapshotCorruptedException if the file is missing, short, malformed or fails the CRC
   */
  public static <T> T readFile(Path file, Reader<T> reader) {
    CRC32 crc = new CRC32();
    try (InputStream raw = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        CheckedInputStream checked = new CheckedInputStream(raw, crc);
        DataInputStream data = new DataInputStream(checked)) {
      T value = reader.read(data);
      byte[] footer = raw.readNBytes(4);
      if (footer.length != 4) {
        throw new SnapshotCorruptedException("missing CRC footer in " + file, null);
      }
      int expected =
          ((footer[0] & 0xFF) << 24)
              | ((footer[1] & 0xFF) << 16)
              | ((footer[2] & 0xFF) << 8)
              | (footer[3] & 0xFF);
      if (expected != (int) crc.getValue()) {
        throw new SnapshotCorruptedException("CRC mismatch in " + file, null);
      }
      if (raw.read() != -1) {
        throw new SnapshotCorruptedException("unexpected bytes after the CRC in " + file, null);
      }
      return value;
    } catch (IOException | RuntimeException e) {
      if (e instanceof SnapshotCorruptedException corrupted) {
        throw corrupted;
      }
      throw new SnapshotCorruptedException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }
}
