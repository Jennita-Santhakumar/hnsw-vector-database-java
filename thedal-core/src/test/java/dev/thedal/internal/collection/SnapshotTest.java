package dev.thedal.internal.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thedal.collection.CollectionConfig;
import dev.thedal.collection.IndexType;
import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.index.FlatIndex;
import dev.thedal.index.HnswIndex;
import dev.thedal.index.HnswParams;
import dev.thedal.index.Index;
import dev.thedal.index.IndexProvider;
import dev.thedal.index.SearchParams;
import dev.thedal.internal.storage.Snapshot;
import dev.thedal.internal.storage.SnapshotCorruptedException;
import dev.thedal.internal.store.VectorStore;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Stream;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotTest {

  @TempDir Path dir;

  /** Providers that can also restore, as the real collection's provider does. */
  static IndexProvider provider(IndexType type, HnswParams params) {
    return new IndexProvider() {
      @Override
      public Index create(VectorStore store, Metric metric) {
        return type == IndexType.FLAT
            ? new FlatIndex(store, metric.distance())
            : new HnswIndex(store, metric.distance(), params);
      }

      @Override
      public Index read(VectorStore store, Metric metric, DataInputStream in) throws IOException {
        return type == IndexType.FLAT
            ? FlatIndex.read(store, metric.distance(), in)
            : HnswIndex.read(store, metric.distance(), params, in);
      }
    };
  }

  private static final HnswParams PARAMS = new HnswParams(8, 64, 32, 5);
  private static final CollectionConfig CONFIG =
      new CollectionConfig("docs", 6, Metric.COSINE, IndexType.HNSW, PARAMS);

  private static PointSet populated(IndexType type, Random rnd, int n) {
    PointSet points = new PointSet(6, Metric.COSINE, provider(type, PARAMS));
    for (int i = 0; i < n; i++) {
      points.upsert("p" + i, PointSetTest.gaussian(rnd, 6), RandomFilters.metadata(rnd));
    }
    for (int i = 0; i < n; i += 7) {
      points.delete("p" + i);
    }
    for (int i = 1; i < n; i += 11) {
      points.upsert("p" + i, PointSetTest.gaussian(rnd, 6), Map.of("rewritten", true));
    }
    return points;
  }

  private PointSet restore(IndexType type) {
    return Snapshot.loadLatest(
            dir,
            header ->
                PointSet.readParts(
                    header.dir(),
                    header.config().dim(),
                    header.config().metric(),
                    provider(type, header.config().hnsw()),
                    PointSet.FilterConfig.DEFAULT))
        .orElseThrow();
  }

  private static void assertSameObservableState(PointSet a, PointSet b, int n, Random rnd) {
    assertThat(b.size()).isEqualTo(a.size());
    assertThat(b.tombstoneCount()).isEqualTo(a.tombstoneCount());
    for (int i = 0; i < n; i++) {
      String id = "p" + i;
      assertThat(b.ordOf(id)).isEqualTo(a.ordOf(id));
      assertThat(b.get(id)).isEqualTo(a.get(id));
      assertThat(b.getMetadata(id)).isEqualTo(a.getMetadata(id));
    }
    for (int q = 0; q < 20; q++) {
      float[] query = PointSetTest.gaussian(rnd, 6);
      Filter filter = q % 2 == 0 ? null : RandomFilters.filter(rnd, 1);
      assertThat(b.search(query, 10, SearchParams.DEFAULT, filter))
          .isEqualTo(a.search(query, 10, SearchParams.DEFAULT, filter));
    }
  }

  @Test
  void hnswRoundTripIsExactAndKeepsEvolvingIdentically() {
    PointSet original = populated(IndexType.HNSW, new Random(1), 500);
    Path written = Snapshot.write(dir, 42, CONFIG, original::writeParts);
    assertThat(written.getFileName().toString()).isEqualTo("snapshot-00000000000000000042");

    PointSet restored = restore(IndexType.HNSW);
    assertSameObservableState(original, restored, 500, new Random(2));

    // Same inserts on both: the replayed level RNG must give the same graph and answers.
    Random a = new Random(3);
    Random b = new Random(3);
    for (int i = 500; i < 700; i++) {
      original.upsert("p" + i, PointSetTest.gaussian(a, 6));
      restored.upsert("p" + i, PointSetTest.gaussian(b, 6));
    }
    assertSameObservableState(original, restored, 700, new Random(4));
  }

  @Test
  void flatRoundTrip() {
    PointSet original = populated(IndexType.FLAT, new Random(5), 200);
    Snapshot.write(dir, 7, CONFIG, original::writeParts);
    assertSameObservableState(original, restore(IndexType.FLAT), 200, new Random(6));
  }

  @Test
  void emptyPointSetRoundTrips() {
    PointSet empty = new PointSet(6, Metric.COSINE, provider(IndexType.HNSW, PARAMS));
    Snapshot.write(dir, 0, CONFIG, empty::writeParts);
    PointSet restored = restore(IndexType.HNSW);
    assertThat(restored.size()).isZero();
    restored.upsert("a", new float[] {1, 0, 0, 0, 0, 0});
    assertThat(
            restored.searchByOrdinal(new float[] {1, 0, 0, 0, 0, 0}, 1, SearchParams.DEFAULT, null))
        .extracting(PointSet.Hit::id)
        .containsExactly("a");
  }

  @Test
  void headerCarriesConfigAndLsn() throws IOException {
    PointSet points = populated(IndexType.HNSW, new Random(7), 20);
    Path snap = Snapshot.write(dir, 9, CONFIG, points::writeParts);
    Snapshot.Header header = Snapshot.readHeader(snap);
    assertThat(header.lsn()).isEqualTo(9);
    assertThat(header.config()).isEqualTo(CONFIG);
    assertThat(Snapshot.lsnOf(snap)).isEqualTo(9);
    assertThat(Snapshot.lsnOf(dir.resolve("other"))).isEqualTo(-1);
    try (Stream<Path> files = Files.list(snap)) {
      assertThat(files.map(p -> p.getFileName().toString()).sorted().toList())
          .containsExactly(
              "graph.bin",
              "header.bin",
              "ids.bin",
              "metadata.bin",
              "tombstones.bin",
              "vectors.bin");
    }
  }

  @Test
  void keepsTheNewestTwoAndRemovesTemporaryLeftovers() throws IOException {
    PointSet points = populated(IndexType.FLAT, new Random(8), 10);
    for (long lsn : new long[] {5, 10, 15}) {
      Snapshot.write(dir, lsn, CONFIG, points::writeParts);
    }
    assertThat(Snapshot.list(dir)).extracting(Snapshot::lsnOf).containsExactly(10L, 15L);
    Path leftover = Files.createDirectory(dir.resolve("tmp-snapshot-crashed"));
    Files.writeString(leftover.resolve("vectors.bin"), "half written");
    restore(IndexType.FLAT);
    assertThat(leftover).doesNotExist();
  }

  @Test
  void rewritingTheSameLsnReplacesIt() {
    PointSet points = populated(IndexType.FLAT, new Random(9), 10);
    Snapshot.write(dir, 3, CONFIG, points::writeParts);
    points.upsert("extra", new float[] {1, 1, 1, 1, 1, 1});
    Snapshot.write(dir, 3, CONFIG, points::writeParts);
    assertThat(Snapshot.list(dir)).hasSize(1);
    assertThat(restore(IndexType.FLAT).get("extra")).isNotNull();
  }

  @Test
  void corruptNewestSnapshotFallsBackToThePreviousOne() throws IOException {
    PointSet older = populated(IndexType.HNSW, new Random(10), 50);
    Snapshot.write(dir, 1, CONFIG, older::writeParts);
    PointSet newer = populated(IndexType.HNSW, new Random(11), 80);
    Path newest = Snapshot.write(dir, 2, CONFIG, newer::writeParts);
    flipByte(newest.resolve("vectors.bin"), 100);

    PointSet restored = restore(IndexType.HNSW);
    assertThat(restored.size()).isEqualTo(older.size());
  }

  @Test
  void failsLoudlyWhenNoSnapshotIsValid() throws IOException {
    PointSet points = populated(IndexType.FLAT, new Random(12), 30);
    Path only = Snapshot.write(dir, 1, CONFIG, points::writeParts);
    flipByte(only.resolve("graph.bin"), 0);
    assertThatThrownBy(() -> restore(IndexType.FLAT))
        .isInstanceOf(SnapshotCorruptedException.class)
        .hasMessageContaining("no valid snapshot");
  }

  @Test
  void noSnapshotsMeansEmpty() {
    assertThat(Snapshot.loadLatest(dir.resolve("missing"), h -> h)).isEqualTo(Optional.empty());
    assertThat(Snapshot.loadLatest(dir, h -> h)).isEmpty();
  }

  @Test
  void rejectsBadMagicVersionTruncationAndTrailingBytes() throws IOException {
    PointSet points = populated(IndexType.FLAT, new Random(13), 10);
    Path snap = Snapshot.write(dir, 4, CONFIG, points::writeParts);
    byte[] header = Files.readAllBytes(snap.resolve("header.bin"));

    flipByte(snap.resolve("header.bin"), 0);
    assertThatThrownBy(() -> Snapshot.readHeader(snap))
        .isInstanceOf(SnapshotCorruptedException.class);

    Files.write(snap.resolve("header.bin"), java.util.Arrays.copyOf(header, header.length - 2));
    assertThatThrownBy(() -> Snapshot.readHeader(snap))
        .isInstanceOf(SnapshotCorruptedException.class);

    byte[] extra = java.util.Arrays.copyOf(header, header.length + 1);
    Files.write(snap.resolve("header.bin"), extra);
    assertThatThrownBy(() -> Snapshot.readHeader(snap))
        .isInstanceOf(SnapshotCorruptedException.class)
        .hasMessageContaining("after the CRC");

    Files.write(snap.resolve("header.bin"), header);
    Files.delete(snap.resolve("ids.bin"));
    assertThatThrownBy(() -> restore(IndexType.FLAT))
        .isInstanceOf(SnapshotCorruptedException.class);
  }

  @Test
  void rejectsSnapshotsWhoseFilesDisagree() throws IOException {
    PointSet small = populated(IndexType.FLAT, new Random(14), 5);
    PointSet big = populated(IndexType.FLAT, new Random(15), 9);
    Path snap = Snapshot.write(dir, 6, CONFIG, small::writeParts);
    Path other = Files.createDirectory(dir.resolve("other-parts"));
    big.writeParts(other);
    // Each file is individually valid (CRC ok), but the counts no longer match.
    Files.copy(
        other.resolve("ids.bin"),
        snap.resolve("ids.bin"),
        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    assertThatThrownBy(() -> restore(IndexType.FLAT))
        .isInstanceOf(SnapshotCorruptedException.class)
        // Stored ordinals include the overwritten version populated() creates: 5+1 and 9+1.
        .hasRootCauseMessage("ids has 10 entries, vectors have 6");
  }

  /** Random operations, snapshot, restore: the observable state must be identical. */
  @Property(tries = 40)
  void snapshotRoundTripPreservesState(
      @ForAll long seed, @ForAll @IntRange(min = 0, max = 150) int n) throws IOException {
    Path propDir = Files.createTempDirectory("snap-prop");
    try {
      Random rnd = new Random(seed);
      IndexType type = rnd.nextBoolean() ? IndexType.HNSW : IndexType.FLAT;
      PointSet original = populated(type, rnd, n);
      if (rnd.nextBoolean()) {
        original.compactIfNeeded();
      }
      Snapshot.write(propDir, n, CONFIG, original::writeParts);
      PointSet restored =
          Snapshot.loadLatest(
                  propDir,
                  h ->
                      PointSet.readParts(
                          h.dir(),
                          6,
                          Metric.COSINE,
                          provider(type, PARAMS),
                          PointSet.FilterConfig.DEFAULT))
              .orElseThrow();
      assertSameObservableState(original, restored, n, new Random(seed + 1));
    } finally {
      try (Stream<Path> files = Files.walk(propDir)) {
        for (Path p : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
          Files.delete(p);
        }
      }
    }
  }

  private static void flipByte(Path file, long position) throws IOException {
    try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
      f.seek(position);
      int b = f.read();
      f.seek(position);
      f.write(b ^ 0xFF);
    }
  }

  @Test
  void listIgnoresUnrelatedEntries() throws IOException {
    Files.createDirectory(dir.resolve("snapshot-abc"));
    Files.writeString(dir.resolve("wal-00000000000000000001.log"), "");
    assertThat(Snapshot.list(dir)).isEmpty();
    assertThat(List.of(Snapshot.dirName(12))).containsExactly("snapshot-00000000000000000012");
  }
}
