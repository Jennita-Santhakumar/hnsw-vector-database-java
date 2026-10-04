package dev.thedal.index;

import java.util.Arrays;

/**
 * Reusable visited-node set for graph searches. A node counts as visited when {@code marks[ord]}
 * equals the current generation, so {@link #reset} is one increment instead of clearing the array.
 * The array is only cleared when the generation wraps after 2^32 resets.
 */
final class VisitedSet {

  private int[] marks = new int[0];
  private int generation;

  VisitedSet() {
    this(0);
  }

  /** Test hook: start from a chosen generation to exercise wrap-around. */
  VisitedSet(int initialGeneration) {
    this.generation = initialGeneration;
  }

  /** Forgets all visits and makes room for ordinals {@code [0, size)}. */
  void reset(int size) {
    if (marks.length < size) {
      marks = new int[Math.max(size, marks.length * 2)];
    }
    generation++;
    if (generation == 0) {
      // Wrapped: stale marks could now equal the generation, so clear once.
      Arrays.fill(marks, 0);
      generation = 1;
    }
  }

  /** Marks {@code ord} visited; returns true if it had not been visited since the last reset. */
  boolean visit(int ord) {
    if (marks[ord] == generation) {
      return false;
    }
    marks[ord] = generation;
    return true;
  }
}
