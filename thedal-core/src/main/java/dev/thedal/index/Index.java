package dev.thedal.index;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.function.IntPredicate;

/**
 * Nearest-neighbour index over the vectors of one collection. Vectors themselves live in the
 * collection's {@code VectorStore}; an index holds only its search structure on top of them.
 */
public interface Index {

  /**
   * Registers ordinal {@code ord}, whose vector is already in the store. {@code vector} is the same
   * data, passed so graph indexes can insert without a store lookup.
   */
  void add(int ord, float[] vector);

  /**
   * Returns up to {@code k} nearest stored vectors to {@code query}, closest first.
   *
   * @param allowed ordinals eligible for results; {@code null} allows all
   */
  SearchResult search(float[] query, int k, SearchParams params, IntPredicate allowed);

  /** Bytes used by the index structure, excluding the vectors in the store. */
  long memoryBytes();

  /** Default candidate-list size used when a query gives no ef; 0 for exact indexes. */
  default int defaultEf() {
    return 0;
  }

  /** Writes the index structure for a snapshot's graph file. */
  void writeTo(DataOutputStream out) throws IOException;
}
