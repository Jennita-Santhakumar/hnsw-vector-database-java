package dev.thedal.internal.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/** Shared WAL file naming, record encoding and directory fsync. */
final class Wal {

  private static final Pattern SEGMENT = Pattern.compile("wal-(\\d{20})\\.log");

  private Wal() {}

  /** File name of the segment whose first record has {@code firstLsn}. */
  static String segmentName(long firstLsn) {
    return String.format(java.util.Locale.ROOT, "wal-%020d.log", firstLsn);
  }

  /** First LSN encoded in a segment file name, or -1 if it is not a segment. */
  static long firstLsn(Path file) {
    Path name = file.getFileName();
    if (name == null) {
      return -1;
    }
    Matcher m = SEGMENT.matcher(name.toString());
    return m.matches() ? Long.parseLong(m.group(1)) : -1;
  }

  /** Segment files of {@code dir}, ordered by first LSN. */
  static List<Path> segments(Path dir) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      List<Path> segments = new ArrayList<>(files.filter(f -> firstLsn(f) >= 0).toList());
      segments.sort((a, b) -> Long.compare(firstLsn(a), firstLsn(b)));
      return segments;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Encodes one record, CRC included. */
  static ByteBuffer encode(byte op, long lsn, byte[] payload) {
    int bodyLength = WalRecord.BODY_HEADER_BYTES + payload.length;
    ByteBuffer buf = ByteBuffer.allocate(WalRecord.PREFIX_BYTES + bodyLength);
    buf.putInt(bodyLength);
    buf.putInt(0); // CRC placeholder
    buf.put(op);
    buf.putLong(lsn);
    buf.put(payload);
    CRC32 crc = new CRC32();
    crc.update(buf.array(), WalRecord.PREFIX_BYTES, bodyLength);
    buf.putInt(4, (int) crc.getValue());
    return buf.flip();
  }

  /**
   * fsyncs a directory so a created, renamed or deleted entry is durable. Windows cannot open
   * directories as channels; NTFS journals metadata, so the step is skipped there.
   */
  static void fsyncDirectory(Path dir) {
    try (FileChannel channel = FileChannel.open(dir, StandardOpenOption.READ)) {
      channel.force(true);
    } catch (IOException e) {
      if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
        throw new UncheckedIOException("fsync of directory " + dir + " failed", e);
      }
    }
  }
}
