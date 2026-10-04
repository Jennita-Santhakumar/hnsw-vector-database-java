package dev.thedal.index;

import dev.thedal.distance.Distance;
import dev.thedal.internal.store.VectorStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.IntPredicate;

/**
 * Exact brute-force index: scans every eligible vector and keeps the top k in a bounded heap. It is
 * the ground truth that HNSW recall is measured against, and the strategy used for very selective
 * filters.
 */
public final class FlatIndex implements Index {

  private final VectorStore store;
  private final Distance distance;
  private int count;

  /** Creates a flat index over {@code store} using {@code distance}. */
  @SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "The index reads the collection's shared store by design; never copied.")
  public FlatIndex(VectorStore store, Distance distance) {
    this.store = store;
    this.distance = distance;
  }

  /**
   * {@inheritDoc}
   *
   * @throws IllegalArgumentException if ordinals are not added densely in order (0, 1, 2, ...)
   */
  @Override
  public void add(int ord, float[] vector) {
    if (ord != count) {
      throw new IllegalArgumentException("expected ordinal " + count + ", got " + ord);
    }
    if (ord >= store.size()) {
      throw new IllegalArgumentException("ordinal " + ord + " is not in the vector store");
    }
    count++;
  }

  @Override
  public SearchResult search(float[] query, int k, SearchParams params, IntPredicate allowed) {
    if (k < 1) {
      throw new IllegalArgumentException("k must be >= 1: " + k);
    }
    if (query.length != store.dim()) {
      throw new IllegalArgumentException(
          "dimension mismatch: expected " + store.dim() + ", got " + query.length);
    }
    BoundedMaxHeap heap = new BoundedMaxHeap(Math.max(1, Math.min(k, count)));
    for (int ord = 0; ord < count; ord++) {
      if (allowed == null || allowed.test(ord)) {
        heap.offer(ord, store.distance(distance, query, ord));
      }
    }
    return heap.drainSorted();
  }

  /** A flat index has no structure beyond the vector store. */
  @Override
  public long memoryBytes() {
    return 0;
  }

  /** Number of registered ordinals. */
  public int size() {
    return count;
  }

  /** Writes the ordinal count; the vectors themselves are in the store's snapshot file. */
  @Override
  public void writeTo(DataOutputStream out) throws IOException {
    out.writeInt(count);
  }

  /** Restores a flat index written by {@link #writeTo} over a restored store. */
  public static FlatIndex read(VectorStore store, Distance distance, DataInputStream in)
      throws IOException {
    int count = in.readInt();
    if (count != store.size()) {
      throw new IOException("flat index has " + count + " ordinals, store has " + store.size());
    }
    FlatIndex index = new FlatIndex(store, distance);
    index.count = count;
    return index;
  }
}
