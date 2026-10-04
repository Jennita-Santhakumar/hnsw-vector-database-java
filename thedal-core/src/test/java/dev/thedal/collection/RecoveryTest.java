package dev.thedal.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.index.HnswParams;
import dev.thedal.index.SearchParams;
import dev.thedal.internal.storage.FsyncMode;
import dev.thedal.internal.storage.Snapshot;
import dev.thedal.internal.storage.WalConfig;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Task 2.3: recovery = snapshot + WAL replay, WAL pruning after snapshots. */
class RecoveryTest {

  @TempDir Path dataDir;

  /** Small segments so pruning has something to delete; snapshots only on demand. */
  private static final DurabilityConfig DURABLE =
      new DurabilityConfig(new WalConfig(FsyncMode.ALWAYS, Duration.ofMillis(5), 4096), 1_000_000);

  private static final CollectionConfig CONFIG =
      new CollectionConfig("docs", 4, Metric.L2, IndexType.HNSW, new HnswParams(8, 50, 32, 3));

  private static float[] vector(Random rnd) {
    float[] v = new float[4];
    for (int i = 0; i < 4; i++) {
      v[i] = (float) rnd.nextGaussian();
    }
    return v;
  }

  /** Random writes recorded in a model so recovered state can be compared exactly. */
  private static void randomWrites(
      Collection c, Map<String, float[]> model, Map<String, Integer> tags, Random rnd, int ops) {
    for (int op = 0; op < ops; op++) {
      String id = "p" + rnd.nextInt(120);
      if (rnd.nextInt(4) == 0) {
        c.delete(id);
        model.remove(id);
        tags.remove(id);
      } else {
        float[] v = vector(rnd);
        int tag = rnd.nextInt(5);
        c.upsert(List.of(new Point(id, v, Map.of("tag", tag))));
        model.put(id, v);
        tags.put(id, tag);
      }
    }
  }

  private static void assertMatchesModel(
      Collection c, Map<String, float[]> model, Map<String, Integer> tags) {
    assertThat(c.stats().count()).isEqualTo(model.size());
    for (int i = 0; i < 120; i++) {
      String id = "p" + i;
      if (model.containsKey(id)) {
        Point p = c.get(id).orElseThrow();
        assertThat(p.vector()).containsExactly(model.get(id));
        assertThat(p.metadata()).containsEntry("tag", (double) tags.get(id));
      } else {
        assertThat(c.get(id)).isEmpty();
      }
    }
  }

  @Test
  void everythingSurvivesARestartViaWalReplay() {
    Map<String, float[]> model = new HashMap<>();
    Map<String, Integer> tags = new HashMap<>();
    List<String> searchBefore;
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = manager.create(CONFIG);
      assertThat(c.isDurable()).isTrue();
      randomWrites(c, model, tags, new Random(1), 400);
      searchBefore = ids(c.search(new float[] {1, 0, 0, 0}, 10, SearchParams.DEFAULT, null));
    }
    try (CollectionManager reopened = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = reopened.get("docs");
      assertThat(c.config()).isEqualTo(CONFIG);
      assertMatchesModel(c, model, tags);
      assertThat(ids(c.search(new float[] {1, 0, 0, 0}, 10, SearchParams.DEFAULT, null)))
          .isEqualTo(searchBefore);
      assertThat(
              c.search(new float[] {0, 1, 0, 0}, 5, SearchParams.DEFAULT, new Filter.Eq("tag", 3))
                  .hits())
          .allSatisfy(h -> assertThat(h.metadata()).containsEntry("tag", 3.0));
    }
  }

  @Test
  void snapshotPlusLaterWalRecordsRecover() {
    Map<String, float[]> model = new HashMap<>();
    Map<String, Integer> tags = new HashMap<>();
    Random rnd = new Random(2);
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = manager.create(CONFIG);
      randomWrites(c, model, tags, rnd, 300);
      long lsn = c.snapshot();
      assertThat(lsn).isPositive();
      randomWrites(c, model, tags, rnd, 200); // only in the WAL
    }
    try (CollectionManager reopened = CollectionManager.open(dataDir, DURABLE)) {
      assertMatchesModel(reopened.get("docs"), model, tags);
    }
  }

  @Test
  void walSegmentsCoveredByTheOlderSnapshotArePruned() throws IOException {
    Map<String, float[]> model = new HashMap<>();
    Map<String, Integer> tags = new HashMap<>();
    Random rnd = new Random(3);
    Path dir = dataDir.resolve("docs");
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = manager.create(CONFIG);
      randomWrites(c, model, tags, rnd, 300);
      int segmentsBefore = walSegments(dir).size();
      assertThat(segmentsBefore).isGreaterThan(3);
      long first = c.snapshot();
      // Only one snapshot: nothing older to fall back to, so it covers what can be pruned.
      randomWrites(c, model, tags, rnd, 300);
      long second = c.snapshot();
      assertThat(second).isGreaterThan(first);
      List<Path> remaining = walSegments(dir);
      // The first segment holds only records covered by the older snapshot: it must be gone.
      assertThat(remaining).doesNotContain(dir.resolve("wal-00000000000000000001.log"));
      // Every remaining segment except the first starts after the older snapshot.
      for (int i = 1; i < remaining.size(); i++) {
        assertThat(firstLsn(remaining.get(i))).isGreaterThan(first + 1);
      }
      assertThat(Snapshot.list(dir)).extracting(Snapshot::lsnOf).containsExactly(first, second);
      randomWrites(c, model, tags, rnd, 50);
    }
    // Damage the newest snapshot: recovery must fall back to the older one plus the kept WAL.
    Path newest = Snapshot.list(dir).get(1);
    Files.writeString(newest.resolve("graph.bin"), "garbage");
    try (CollectionManager reopened = CollectionManager.open(dataDir, DURABLE)) {
      assertMatchesModel(reopened.get("docs"), model, tags);
    }
  }

  @Test
  void tornTailLosesOnlyTheUnfinishedWrite() throws IOException {
    Map<String, float[]> model = new HashMap<>();
    Map<String, Integer> tags = new HashMap<>();
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      randomWrites(manager.create(CONFIG), model, tags, new Random(4), 100);
    }
    // A crash in the middle of appending the next record leaves a partial record behind.
    List<Path> segments = walSegments(dataDir.resolve("docs"));
    try (RandomAccessFile f =
        new RandomAccessFile(segments.get(segments.size() - 1).toFile(), "rw")) {
      f.seek(f.length());
      f.write(new byte[] {0, 0, 0, 40, 1, 2, 3});
    }
    try (CollectionManager reopened = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = reopened.get("docs");
      assertMatchesModel(c, model, tags);
      c.upsert(List.of(new Point("after", new float[] {1, 2, 3, 4})));
    }
    try (CollectionManager again = CollectionManager.open(dataDir, DURABLE)) {
      assertThat(again.get("docs").get("after")).isPresent();
    }
  }

  @Test
  void createThatNeverBecameDurableIsRemoved() throws IOException {
    Path half = Files.createDirectories(dataDir.resolve("ghost"));
    Files.write(half.resolve("wal-00000000000000000001.log"), new byte[] {0, 0, 0}); // torn CREATE
    Files.createDirectories(dataDir.resolve("old.dropped-123"));
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      assertThat(manager.list()).isEmpty();
    }
    assertThat(half).doesNotExist();
    assertThat(dataDir.resolve("old.dropped-123")).doesNotExist();
  }

  @Test
  void droppedCollectionsStayDropped() throws IOException {
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = manager.create(CONFIG);
      c.upsert(List.of(new Point("a", new float[] {1, 2, 3, 4})));
      manager.drop("docs");
      assertThat(dataDir.resolve("docs")).doesNotExist();
      assertThatThrownBy(() -> c.upsert(List.of(new Point("b", new float[] {1, 1, 1, 1}))))
          .isInstanceOf(IllegalStateException.class);
      manager.create(CONFIG).upsert(List.of(new Point("fresh", new float[] {4, 3, 2, 1})));
    }
    try (CollectionManager reopened = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = reopened.get("docs");
      assertThat(c.get("a")).isEmpty();
      assertThat(c.get("fresh")).isPresent();
    }
  }

  @Test
  void dropRecordInTheWalFinishesAnInterruptedDrop() throws IOException {
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      manager.create(CONFIG).upsert(List.of(new Point("a", new float[] {1, 2, 3, 4})));
    }
    // Simulate: DROP logged, crash before the directory was renamed away.
    Path dir = dataDir.resolve("docs");
    try (var wal = new dev.thedal.internal.storage.WalWriter(dir, DURABLE.wal(), lastLsn(dir))) {
      wal.sync(
          wal.append(
              (byte) 4, new dev.thedal.internal.storage.WalEntry.DropCollection("docs").encode()));
    }
    try (CollectionManager reopened = CollectionManager.open(dataDir, DURABLE)) {
      assertThat(reopened.list()).isEmpty();
    }
    assertThat(dir).doesNotExist();
  }

  @Test
  void compactionBeforeASnapshotRecovers() {
    Map<String, float[]> model = new HashMap<>();
    Map<String, Integer> tags = new HashMap<>();
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      Collection c = manager.create(CONFIG);
      randomWrites(c, model, tags, new Random(5), 500); // many overwrites -> auto-compactions
      assertThat(c.stats().compactions()).isPositive();
      c.snapshot();
      randomWrites(c, model, tags, new Random(6), 100);
    }
    try (CollectionManager reopened = CollectionManager.open(dataDir, DURABLE)) {
      assertMatchesModel(reopened.get("docs"), model, tags);
    }
  }

  @Test
  void snapshotsHappenAutomaticallyAndLsnsKeepIncreasing() throws IOException {
    DurabilityConfig frequent =
        new DurabilityConfig(new WalConfig(FsyncMode.BATCH, Duration.ofMillis(2), 4096), 50);
    Map<String, float[]> model = new HashMap<>();
    Map<String, Integer> tags = new HashMap<>();
    Path dir = dataDir.resolve("docs");
    long lastSnapshot;
    try (CollectionManager manager = CollectionManager.open(dataDir, frequent)) {
      randomWrites(manager.create(CONFIG), model, tags, new Random(7), 400);
      List<Path> snapshots = Snapshot.list(dir);
      assertThat(snapshots).hasSize(Snapshot.KEEP);
      lastSnapshot = Snapshot.lsnOf(snapshots.get(1));
    }
    try (CollectionManager reopened = CollectionManager.open(dataDir, frequent)) {
      Collection c = reopened.get("docs");
      assertMatchesModel(c, model, tags);
      c.upsert(List.of(new Point("next", new float[] {1, 1, 1, 1})));
      c.snapshot();
      assertThat(Snapshot.lsnOf(Snapshot.list(dir).get(1))).isGreaterThan(lastSnapshot);
    }
  }

  @Test
  void durableNamesMustDifferBeyondCase() {
    try (CollectionManager manager = CollectionManager.open(dataDir, DURABLE)) {
      manager.create(CONFIG);
      assertThatThrownBy(() -> manager.create(CollectionConfig.flat("DOCS", 4, Metric.L2)))
          .isInstanceOf(CollectionAlreadyExistsException.class);
      assertThat(manager.isDurable()).isTrue();
    }
    CollectionManager memory = new CollectionManager();
    memory.create(CONFIG);
    memory.create(CollectionConfig.flat("DOCS", 4, Metric.L2));
    assertThat(memory.isDurable()).isFalse();
    assertThatThrownBy(() -> memory.get("docs").snapshot())
        .isInstanceOf(IllegalStateException.class);
  }

  private static List<String> ids(SearchResponse response) {
    return response.hits().stream().map(SearchHit::id).toList();
  }

  private static List<Path> walSegments(Path dir) throws IOException {
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(p -> p.getFileName().toString().startsWith("wal-")).sorted().toList();
    }
  }

  private static long firstLsn(Path segment) {
    String name = segment.getFileName().toString();
    return Long.parseLong(name.substring(4, name.length() - 4));
  }

  private static long lastLsn(Path dir) {
    List<Long> lsns = new ArrayList<>();
    dev.thedal.internal.storage.WalReader.recover(dir, 0, r -> lsns.add(r.lsn()));
    return lsns.isEmpty() ? 0 : lsns.get(lsns.size() - 1);
  }
}
