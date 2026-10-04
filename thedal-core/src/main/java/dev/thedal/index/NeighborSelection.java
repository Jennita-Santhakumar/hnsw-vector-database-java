package dev.thedal.index;

/** How HNSW picks a node's links from its candidate list. */
public enum NeighborSelection {
  /** The M closest candidates (paper Algorithm 3). */
  SIMPLE,
  /**
   * Diversity heuristic (paper Algorithm 4): walk candidates closest-first and keep one only if it
   * is closer to the base node than to every neighbour kept so far. Links then spread across
   * directions, keeping bridges between clusters.
   */
  HEURISTIC
}
