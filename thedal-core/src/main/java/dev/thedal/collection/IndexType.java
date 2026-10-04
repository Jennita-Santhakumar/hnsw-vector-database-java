package dev.thedal.collection;

/** Index kind of a collection. */
public enum IndexType {
  /** Exact brute-force search. */
  FLAT,
  /** Approximate HNSW graph search. */
  HNSW
}
