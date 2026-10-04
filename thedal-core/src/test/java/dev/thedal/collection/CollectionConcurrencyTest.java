package dev.thedal.collection;

import static org.assertj.core.api.Assertions.assertThat;

import dev.thedal.distance.Metric;
import dev.thedal.filter.Filter;
import dev.thedal.index.HnswParams;
import dev.thedal.index.SearchParams;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * Task 1.9 stress test: writers upsert and delete (triggering repeated auto-compactions) while
 * readers run filtered and unfiltered searches. Checks no thread fails, every result is internally
 * consistent, and the final state equals each writer's model.
 */
class CollectionConcurrencyTest {

  private static final int WRITERS = 4;
  private static final int READERS = 6;
  private static final int OPS_PER_WRITER = 3_000;
  private static final int IDS_PER_WRITER = 400;
  private static final int DIM = 8;

  @Test
  void concurrentSearchesAndWritesStayConsistent() throws Exception {
    Collection c =
        new Collection(
            new CollectionConfig(
                "stress", DIM, Metric.L2, IndexType.HNSW, new HnswParams(8, 64, 32, 1)));
    ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
    AtomicBoolean writersDone = new AtomicBoolean();
    AtomicLong searches = new AtomicLong();
    CountDownLatch start = new CountDownLatch(1);
    List<Map<String, float[]>> models = new ArrayList<>();
    List<Thread> threads = new ArrayList<>();

    for (int w = 0; w < WRITERS; w++) {
      int writer = w;
      Map<String, float[]> model = new HashMap<>();
      models.add(model);
      threads.add(
          new Thread(
              () -> {
                Random rnd = new Random(100 + writer);
                try {
                  start.await();
                  for (int op = 0; op < OPS_PER_WRITER; op++) {
                    String id = "w" + writer + "-" + rnd.nextInt(IDS_PER_WRITER);
                    if (rnd.nextInt(10) < 3) {
                      assertThat(c.delete(id)).isEqualTo(model.remove(id) != null);
                    } else {
                      float[] v = vector(rnd);
                      c.upsert(List.of(new Point(id, v, Map.of("writer", writer, "seq", op))));
                      model.put(id, v);
                    }
                  }
                } catch (Throwable t) {
                  failures.add(t);
                }
              }));
    }
    for (int r = 0; r < READERS; r++) {
      int reader = r;
      threads.add(
          new Thread(
              () -> {
                Random rnd = new Random(900 + reader);
                try {
                  start.await();
                  while (!writersDone.get()) {
                    int k = 1 + rnd.nextInt(20);
                    Filter filter =
                        rnd.nextBoolean() ? null : new Filter.Eq("writer", rnd.nextInt(WRITERS));
                    SearchResponse response =
                        c.search(vector(rnd), k, SearchParams.DEFAULT, filter);
                    checkConsistent(response, k, filter);
                    searches.incrementAndGet();
                  }
                } catch (Throwable t) {
                  failures.add(t);
                }
              }));
    }
    threads.forEach(Thread::start);
    start.countDown();
    for (int w = 0; w < WRITERS; w++) {
      threads.get(w).join();
    }
    writersDone.set(true);
    for (Thread t : threads) {
      t.join();
    }

    assertThat(failures).isEmpty();
    assertThat(searches.get()).isPositive();
    CollectionStats stats = c.stats();
    assertThat(stats.compactions()).isPositive();
    int expectedCount = models.stream().mapToInt(Map::size).sum();
    assertThat(stats.count()).isEqualTo(expectedCount);
    for (int w = 0; w < WRITERS; w++) {
      for (int i = 0; i < IDS_PER_WRITER; i++) {
        String id = "w" + w + "-" + i;
        float[] expected = models.get(w).get(id);
        if (expected == null) {
          assertThat(c.get(id)).isEmpty();
        } else {
          assertThat(c.get(id).orElseThrow().vector()).containsExactly(expected);
        }
      }
    }
  }

  /** A result must have <= k hits, unique ids, ascending scores, and metadata matching the id. */
  private static void checkConsistent(SearchResponse response, int k, Filter filter) {
    List<SearchHit> hits = response.hits();
    assertThat(hits.size()).isLessThanOrEqualTo(k);
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < hits.size(); i++) {
      SearchHit hit = hits.get(i);
      assertThat(seen.add(hit.id())).as("duplicate id %s", hit.id()).isTrue();
      if (i > 0) {
        assertThat(hit.score()).isGreaterThanOrEqualTo(hits.get(i - 1).score());
      }
      double writer = (Double) hit.metadata().get("writer");
      assertThat(hit.id()).startsWith("w" + (int) writer + "-");
      if (filter != null) {
        assertThat(filter.matches(hit.metadata())).isTrue();
      }
    }
  }

  private static float[] vector(Random rnd) {
    float[] v = new float[DIM];
    for (int i = 0; i < DIM; i++) {
      v[i] = (float) rnd.nextGaussian();
    }
    return v;
  }
}
