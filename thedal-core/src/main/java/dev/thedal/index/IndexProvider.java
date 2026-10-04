package dev.thedal.index;

import dev.thedal.distance.Metric;
import dev.thedal.internal.store.VectorStore;
import java.io.DataInputStream;
import java.io.IOException;

/** Creates an index over a store, and restores one from a snapshot's graph file. */
@FunctionalInterface
public interface IndexProvider {

  /** A new, empty index over {@code store}. */
  Index create(VectorStore store, Metric metric);

  /**
   * Restores an index written by {@link Index#writeTo} over an already-restored {@code store}.
   *
   * @throws IOException if the data is malformed or does not match the store
   */
  default Index read(VectorStore store, Metric metric, DataInputStream in) throws IOException {
    throw new UnsupportedOperationException("this index provider cannot restore snapshots");
  }
}
