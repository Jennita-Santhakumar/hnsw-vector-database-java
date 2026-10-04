package dev.thedal.index;

/**
 * Per-thread working memory for HNSW layer searches, reused across queries so a search allocates
 * only its result arrays. One instance must serve one search at a time.
 */
final class SearchScratch {

  final VisitedSet visited = new VisitedSet();
  final MinHeap candidates = new MinHeap(64);
  final BoundedMaxHeap results = new BoundedMaxHeap(64);
  final int[] singleEntryPoint = new int[1];
  int[] sortedOrds = new int[64];
  float[] sortedDists = new float[64];

  /** Makes the sorted-output buffers hold at least {@code n} entries. */
  void ensureSortedCapacity(int n) {
    if (sortedOrds.length < n) {
      sortedOrds = new int[n];
      sortedDists = new float[n];
    }
  }
}
