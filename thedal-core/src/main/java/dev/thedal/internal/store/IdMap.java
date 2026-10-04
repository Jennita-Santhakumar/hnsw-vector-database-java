package dev.thedal.internal.store;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-way mapping between external string ids and internal ordinals.
 *
 * <p>An upsert of an existing id gives it a fresh ordinal (graph indexes cannot rewrite a node's
 * vector in place); {@link #bind} returns the previous ordinal so the caller can tombstone it.
 * Ordinals that no id points at any more map back to {@code null}.
 *
 * <p>Not thread-safe; guarded by the owning collection's lock.
 */
public final class IdMap {

  /** Returned when an id or ordinal has no mapping. */
  public static final int ABSENT = -1;

  /** Longest accepted id, in UTF-16 chars. */
  public static final int MAX_ID_LENGTH = 256;

  private final Map<String, Integer> ordById = new HashMap<>();
  private final List<String> idByOrd = new ArrayList<>();

  /**
   * Points {@code id} at {@code ord}.
   *
   * @return the ordinal {@code id} pointed at before, or {@link #ABSENT}
   * @throws IllegalArgumentException if the id is invalid, {@code ord} is negative, or {@code ord}
   *     is already bound to another id
   */
  public int bind(String id, int ord) {
    requireValidId(id);
    if (ord < 0) {
      throw new IllegalArgumentException("ordinal must be non-negative: " + ord);
    }
    String current = idOf(ord);
    if (current != null && !current.equals(id)) {
      throw new IllegalArgumentException("ordinal " + ord + " is already bound to id " + current);
    }
    while (idByOrd.size() <= ord) {
      idByOrd.add(null);
    }
    Integer previous = ordById.put(id, ord);
    if (previous != null && previous != ord) {
      idByOrd.set(previous, null);
    }
    idByOrd.set(ord, id);
    return previous == null ? ABSENT : previous;
  }

  /**
   * Removes {@code id}.
   *
   * @return the ordinal it pointed at, or {@link #ABSENT} if it was not present
   */
  public int unbind(String id) {
    Integer ord = ordById.remove(id);
    if (ord == null) {
      return ABSENT;
    }
    idByOrd.set(ord, null);
    return ord;
  }

  /** Returns the ordinal for {@code id}, or {@link #ABSENT}. */
  public int ordOf(String id) {
    Integer ord = ordById.get(id);
    return ord == null ? ABSENT : ord;
  }

  /** Returns the id currently bound to {@code ord}, or {@code null}. */
  public String idOf(int ord) {
    return ord >= 0 && ord < idByOrd.size() ? idByOrd.get(ord) : null;
  }

  /** Number of live ids. */
  public int size() {
    return ordById.size();
  }

  /**
   * Checks an id without binding it, so callers can validate before mutating other state.
   *
   * @throws IllegalArgumentException if the id is null, empty or longer than {@link #MAX_ID_LENGTH}
   */
  public static void requireValidId(String id) {
    if (id == null || id.isEmpty()) {
      throw new IllegalArgumentException("id must be a non-empty string");
    }
    if (id.length() > MAX_ID_LENGTH) {
      throw new IllegalArgumentException(
          "id longer than " + MAX_ID_LENGTH + " chars: " + id.length());
    }
  }
}
