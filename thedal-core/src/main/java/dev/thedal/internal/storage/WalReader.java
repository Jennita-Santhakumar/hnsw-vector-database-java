package dev.thedal.internal.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Reads WAL segments in LSN order and repairs a torn tail.
 *
 * <p>A record is valid when its length is plausible, its body is complete, its CRC32 matches and
 * its LSN is greater than the previous one. The first invalid record in the <em>last</em> segment
 * is a write the crash interrupted: that segment is truncated there. An invalid record anywhere
 * else is real corruption and raises {@link WalCorruptedException}.
 */
public final class WalReader {

  /**
   * What {@link #recover} found.
   *
   * @param lastLsn LSN of the last valid record, or 0 if there is none
   * @param records number of valid records
   * @param truncatedBytes bytes cut from the torn tail of the last segment
   */
  public record Recovery(long lastLsn, long records, long truncatedBytes) {}

  private WalReader() {}

  /**
   * Validates every segment of {@code dir}, truncates a torn tail, and passes each valid record
   * with LSN greater than {@code afterLsn} to {@code consumer}, in order.
   */
  public static Recovery recover(Path dir, long afterLsn, Consumer<WalRecord> consumer) {
    List<Path> segments = Wal.segments(dir);
    long lastLsn = 0;
    long count = 0;
    long truncated = 0;
    for (int i = 0; i < segments.size(); i++) {
      Path segment = segments.get(i);
      boolean last = i == segments.size() - 1;
      try (FileChannel channel =
          FileChannel.open(segment, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
        long size = channel.size();
        long position = 0;
        while (position < size) {
          WalRecord record = readRecord(channel, position, size, lastLsn);
          if (record == null) {
            if (!last) {
              throw new WalCorruptedException(
                  "invalid record at byte "
                      + position
                      + " of "
                      + segment
                      + " (not the last segment)");
            }
            truncated = size - position;
            channel.truncate(position);
            channel.force(true);
            break;
          }
          position += record.encodedSize();
          lastLsn = record.lsn();
          count++;
          if (record.lsn() > afterLsn) {
            consumer.accept(record);
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException("reading " + segment, e);
      }
    }
    return new Recovery(lastLsn, count, truncated);
  }

  /** Reads the record at {@code position}, or returns null if it is torn or corrupt. */
  private static WalRecord readRecord(
      FileChannel channel, long position, long size, long previousLsn) throws IOException {
    if (size - position < WalRecord.PREFIX_BYTES) {
      return null;
    }
    ByteBuffer prefix = ByteBuffer.allocate(WalRecord.PREFIX_BYTES);
    readFully(channel, prefix, position);
    int bodyLength = prefix.getInt(0);
    int expectedCrc = prefix.getInt(4);
    if (bodyLength < WalRecord.BODY_HEADER_BYTES
        || bodyLength > WalRecord.MAX_BODY_BYTES
        || size - position - WalRecord.PREFIX_BYTES < bodyLength) {
      return null;
    }
    ByteBuffer body = ByteBuffer.allocate(bodyLength);
    readFully(channel, body, position + WalRecord.PREFIX_BYTES);
    CRC32 crc = new CRC32();
    crc.update(body.array(), 0, bodyLength);
    if ((int) crc.getValue() != expectedCrc) {
      return null;
    }
    byte op = body.get(0);
    long lsn = body.getLong(1);
    if (lsn <= previousLsn) {
      return null;
    }
    byte[] payload = new byte[bodyLength - WalRecord.BODY_HEADER_BYTES];
    body.get(WalRecord.BODY_HEADER_BYTES, payload);
    return new WalRecord(op, lsn, payload);
  }

  private static void readFully(FileChannel channel, ByteBuffer buf, long position)
      throws IOException {
    while (buf.hasRemaining()) {
      int n = channel.read(buf, position + buf.position());
      if (n < 0) {
        throw new IOException("unexpected end of file");
      }
    }
  }
}
