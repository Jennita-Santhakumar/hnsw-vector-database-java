package dev.thedal.internal.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thedal.collection.CollectionConfig;
import dev.thedal.collection.IndexType;
import dev.thedal.distance.Metric;
import dev.thedal.index.HnswParams;
import dev.thedal.index.NeighborSelection;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalTest {

  @TempDir Path dir;

  private static final WalConfig SMALL_SEGMENTS =
      new WalConfig(FsyncMode.ALWAYS, Duration.ofMillis(5), 1024);

  private static byte[] payload(int i) {
    return ("record-" + i + "-" + "x".repeat(i % 50)).getBytes(StandardCharsets.UTF_8);
  }

  private static List<WalRecord> readAll(Path dir) {
    List<WalRecord> records = new ArrayList<>();
    WalReader.recover(dir, 0, records::add);
    return records;
  }

  private void writeRecords(Path walDir, int n) {
    try (WalWriter wal = new WalWriter(walDir, SMALL_SEGMENTS, 0)) {
      for (int i = 0; i < n; i++) {
        wal.sync(wal.append((byte) 1, payload(i)));
      }
    }
  }

  @Test
  void roundTripsAcrossSegmentRotation() {
    writeRecords(dir, 200);
    List<Path> segments = Wal.segments(dir);
    assertThat(segments.size()).isGreaterThan(3);
    assertThat(Wal.firstLsn(segments.get(0))).isEqualTo(1);
    for (Path segment : segments) {
      assertThat(segment.getFileName().toString()).matches("wal-\\d{20}\\.log");
    }

    List<WalRecord> records = readAll(dir);
    assertThat(records).hasSize(200);
    for (int i = 0; i < 200; i++) {
      assertThat(records.get(i).lsn()).isEqualTo(i + 1);
      assertThat(records.get(i).op()).isEqualTo((byte) 1);
      assertThat(records.get(i).payload()).isEqualTo(payload(i));
    }
  }

  @Test
  void recoverSkipsRecordsUpToAfterLsnAndReportsTotals() {
    writeRecords(dir, 50);
    List<WalRecord> tail = new ArrayList<>();
    WalReader.Recovery recovery = WalReader.recover(dir, 40, tail::add);
    assertThat(recovery.lastLsn()).isEqualTo(50);
    assertThat(recovery.records()).isEqualTo(50);
    assertThat(recovery.truncatedBytes()).isZero();
    assertThat(tail)
        .extracting(WalRecord::lsn)
        .containsExactly(41L, 42L, 43L, 44L, 45L, 46L, 47L, 48L, 49L, 50L);
  }

  @Test
  void writerContinuesLsnsAfterRecovery() {
    writeRecords(dir, 10);
    WalReader.Recovery recovery = WalReader.recover(dir, 0, r -> {});
    try (WalWriter wal = new WalWriter(dir, SMALL_SEGMENTS, recovery.lastLsn())) {
      assertThat(wal.append((byte) 2, new byte[] {7})).isEqualTo(11);
    }
    assertThat(readAll(dir)).hasSize(11).last().extracting(WalRecord::op).isEqualTo((byte) 2);
  }

  @Test
  void tornTailIsTruncatedAtEveryCutPoint() throws IOException {
    writeRecords(dir, 3);
    Path segment = Wal.segments(dir).get(0);
    byte[] full = Files.readAllBytes(segment);
    int lastRecordStart =
        full.length - (WalRecord.PREFIX_BYTES + WalRecord.BODY_HEADER_BYTES + payload(2).length);

    for (int cut = lastRecordStart; cut < full.length; cut++) {
      Files.write(segment, java.util.Arrays.copyOf(full, cut));
      List<WalRecord> records = new ArrayList<>();
      WalReader.Recovery recovery = WalReader.recover(dir, 0, records::add);
      assertThat(records).as("cut at %d", cut).hasSize(2);
      assertThat(recovery.lastLsn()).isEqualTo(2);
      assertThat(recovery.truncatedBytes()).isEqualTo(cut - lastRecordStart);
      assertThat(Files.size(segment)).isEqualTo(lastRecordStart);
    }
  }

  @Test
  void appendsAfterATornTailAreReadable() throws IOException {
    writeRecords(dir, 5);
    Path segment = Wal.segments(dir).get(0);
    try (RandomAccessFile f = new RandomAccessFile(segment.toFile(), "rw")) {
      f.setLength(f.length() - 3);
    }
    WalReader.Recovery recovery = WalReader.recover(dir, 0, r -> {});
    try (WalWriter wal = new WalWriter(dir, SMALL_SEGMENTS, recovery.lastLsn())) {
      wal.sync(wal.append((byte) 1, payload(99)));
    }
    List<WalRecord> records = readAll(dir);
    assertThat(records).extracting(WalRecord::lsn).containsExactly(1L, 2L, 3L, 4L, 5L);
    assertThat(records.get(4).payload()).isEqualTo(payload(99));
  }

  @Test
  void corruptCrcInLastSegmentIsTreatedAsTornTail() throws IOException {
    writeRecords(dir, 4);
    Path segment = Wal.segments(dir).get(0);
    byte[] bytes = Files.readAllBytes(segment);
    bytes[bytes.length - 1] ^= 0x5A; // flip a payload byte of the last record
    Files.write(segment, bytes);
    assertThat(readAll(dir)).hasSize(3);
  }

  @Test
  void garbageLengthIsTreatedAsTornTail() throws IOException {
    writeRecords(dir, 2);
    Path segment = Wal.segments(dir).get(0);
    try (RandomAccessFile f = new RandomAccessFile(segment.toFile(), "rw")) {
      f.seek(f.length());
      f.writeInt(Integer.MAX_VALUE); // a record header claiming a 2 GiB body
      f.writeInt(0);
      f.write(new byte[20]);
    }
    assertThat(readAll(dir)).hasSize(2);
  }

  @Test
  void corruptionBeforeTheLastSegmentFailsLoudly() throws IOException {
    writeRecords(dir, 200);
    Path first = Wal.segments(dir).get(0);
    byte[] bytes = Files.readAllBytes(first);
    bytes[20] ^= 0x01;
    Files.write(first, bytes);
    assertThatThrownBy(() -> readAll(dir))
        .isInstanceOf(WalCorruptedException.class)
        .hasMessageContaining("not the last segment");
  }

  @Test
  void emptyOrMissingDirectoryRecoversNothing() {
    assertThat(WalReader.recover(dir.resolve("missing"), 0, r -> {}).lastLsn()).isZero();
    assertThat(readAll(dir)).isEmpty();
  }

  @Test
  void emptySegmentLeftByACrashIsReused() throws IOException {
    Files.createFile(dir.resolve(Wal.segmentName(1)));
    try (WalWriter wal = new WalWriter(dir, SMALL_SEGMENTS, 0)) {
      wal.sync(wal.append((byte) 1, payload(0)));
    }
    assertThat(readAll(dir)).hasSize(1);
  }

  @Test
  void refusesToOverwriteASegmentThatHasRecords() {
    writeRecords(dir, 1);
    try (WalWriter wal = new WalWriter(dir, SMALL_SEGMENTS, 0)) {
      assertThatThrownBy(() -> wal.append((byte) 1, payload(0)))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("run recovery");
    }
  }

  @Test
  void alwaysModeFsyncsOncePerSync() {
    try (WalWriter wal = new WalWriter(dir, WalConfig.DEFAULT, 0)) {
      long last = 0;
      for (int i = 0; i < 10; i++) {
        last = wal.append((byte) 1, payload(i));
      }
      assertThat(wal.durableLsn()).isZero();
      wal.sync(last);
      wal.sync(last); // already durable: no extra fsync
      assertThat(wal.durableLsn()).isEqualTo(10);
      assertThat(wal.fsyncCount()).isEqualTo(1);
      assertThat(wal.fsyncNanos()).isPositive();
      assertThat(wal.lastLsn()).isEqualTo(10);
    }
  }

  @Test
  void batchModeGroupCommitsConcurrentWriters() throws Exception {
    WalConfig batch = new WalConfig(FsyncMode.BATCH, Duration.ofMillis(20), 64L << 20);
    int writers = 8;
    int perWriter = 50;
    try (WalWriter wal = new WalWriter(dir, batch, 0)) {
      ExecutorService pool = Executors.newFixedThreadPool(writers);
      try {
        List<Future<?>> futures = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
          futures.add(
              pool.submit(
                  () -> {
                    for (int i = 0; i < perWriter; i++) {
                      long lsn = wal.append((byte) 1, payload(i));
                      wal.sync(lsn);
                      assertThat(wal.durableLsn()).isGreaterThanOrEqualTo(lsn);
                    }
                  }));
        }
        for (Future<?> f : futures) {
          f.get();
        }
      } finally {
        pool.shutdownNow();
      }
      assertThat(wal.durableLsn()).isEqualTo(writers * perWriter);
      assertThat(wal.fsyncCount()).isLessThan(writers * perWriter);
    }
    assertThat(readAll(dir)).hasSize(writers * perWriter);
  }

  @Test
  void closedOrFailedWriterRejectsWrites() {
    WalWriter wal = new WalWriter(dir, SMALL_SEGMENTS, 0);
    wal.close();
    wal.close();
    assertThatThrownBy(() -> wal.append((byte) 1, payload(1)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void configIsValidated() {
    assertThatThrownBy(() -> new WalConfig(null, Duration.ofMillis(1), 4096))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WalConfig(FsyncMode.BATCH, Duration.ZERO, 4096))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new WalConfig(FsyncMode.BATCH, Duration.ofMillis(1), 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** After a crash at any byte, recovery yields exactly the records that were fully written. */
  @Property(tries = 100)
  void recoveryYieldsTheCompleteRecordPrefix(
      @ForAll @IntRange(min = 1, max = 60) int n, @ForAll long seed) throws IOException {
    Path walDir = Files.createTempDirectory("wal-prop");
    try {
      writeRecords(walDir, n);
      List<Path> segments = Wal.segments(walDir);
      Path last = segments.get(segments.size() - 1);
      long size = Files.size(last);
      long cut = new Random(seed).nextLong(size + 1);
      try (RandomAccessFile f = new RandomAccessFile(last.toFile(), "rw")) {
        f.setLength(cut);
      }
      List<WalRecord> records = readAll(walDir);
      for (int i = 0; i < records.size(); i++) {
        assertThat(records.get(i).lsn()).isEqualTo(i + 1);
        assertThat(records.get(i).payload()).isEqualTo(payload(i));
      }
      long recordsBeforeLast = Wal.firstLsn(last) - 1;
      long completeInLast = 0;
      long offset = 0;
      for (long lsn = Wal.firstLsn(last); lsn <= n; lsn++) {
        offset +=
            WalRecord.PREFIX_BYTES + WalRecord.BODY_HEADER_BYTES + payload((int) lsn - 1).length;
        if (offset <= cut) {
          completeInLast++;
        }
      }
      assertThat(records).hasSize((int) (recordsBeforeLast + completeInLast));
    } finally {
      try (var files = Files.walk(walDir)) {
        files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
      }
    }
  }

  @Test
  void entriesRoundTrip() {
    CollectionConfig config =
        new CollectionConfig(
            "docs",
            3,
            Metric.COSINE,
            IndexType.HNSW,
            new HnswParams(12, 100, 50, 9, NeighborSelection.SIMPLE));
    List<WalEntry> entries =
        List.of(
            new WalEntry.Upsert(
                "தமிழ்-id",
                new float[] {1.5f, -2f, 0f},
                Map.of("lang", "ta", "year", 2020.0, "draft", true)),
            new WalEntry.Upsert("empty-meta", new float[] {0f}, Map.of()),
            new WalEntry.Delete("gone"),
            new WalEntry.CreateCollection(config),
            new WalEntry.DropCollection("docs"));
    try (WalWriter wal = new WalWriter(dir, SMALL_SEGMENTS, 0)) {
      for (WalEntry entry : entries) {
        wal.sync(wal.append(entry.op(), entry.encode()));
      }
    }
    List<WalEntry> decoded = readAll(dir).stream().map(WalEntry::decode).toList();
    WalEntry.Upsert upsert = (WalEntry.Upsert) decoded.get(0);
    assertThat(upsert.id()).isEqualTo("தமிழ்-id");
    assertThat(upsert.vector()).containsExactly(1.5f, -2f, 0f);
    assertThat(upsert.metadata()).isEqualTo(Map.of("lang", "ta", "year", 2020.0, "draft", true));
    assertThat(((WalEntry.Upsert) decoded.get(1)).metadata()).isEmpty();
    assertThat(decoded.get(2)).isEqualTo(new WalEntry.Delete("gone"));
    assertThat(decoded.get(3)).isEqualTo(new WalEntry.CreateCollection(config));
    assertThat(decoded.get(4)).isEqualTo(new WalEntry.DropCollection("docs"));
  }

  @Test
  void decodeRejectsUnknownOpsAndMalformedPayloads() {
    assertThatThrownBy(() -> WalEntry.decode(new WalRecord((byte) 9, 1, new byte[0])))
        .hasMessageContaining("unknown WAL op");
    assertThatThrownBy(() -> WalEntry.decode(new WalRecord(WalEntry.OP_DELETE, 1, new byte[] {0})))
        .hasMessageContaining("malformed");
    byte[] withTrailing = java.util.Arrays.copyOf(new WalEntry.Delete("a").encode(), 10);
    assertThatThrownBy(() -> WalEntry.decode(new WalRecord(WalEntry.OP_DELETE, 1, withTrailing)))
        .hasMessageContaining("trailing bytes");
  }
}
