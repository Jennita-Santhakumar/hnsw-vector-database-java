package dev.thedal.internal.collection;

import dev.thedal.filter.Filter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.roaringbitmap.FastAggregation;
import org.roaringbitmap.RoaringBitmap;

/**
 * Metadata of each ordinal plus inverted indexes for filtering.
 *
 * <ul>
 *   <li>{@code eq}/{@code in}: per field, value to bitmap of ordinals.
 *   <li>{@code range}: per field, a {@link TreeMap} from numeric value to bitmap, so a range is one
 *       ordered sub-map walk. (ARCHITECTURE.md sketches sorted arrays; a tree avoids re-sorting on
 *       every insert or a lazy rebuild that would race with concurrent readers.)
 * </ul>
 *
 * Only live ordinals are indexed: the owner calls {@link #remove} for deleted or replaced points.
 * Not thread-safe; guarded by the collection lock.
 */
public final class MetadataStore {

  private final List<Map<String, Object>> fieldsByOrd = new ArrayList<>();
  private final Map<String, Map<Object, RoaringBitmap>> exact = new HashMap<>();
  private final Map<String, NavigableMap<Double, RoaringBitmap>> numeric = new HashMap<>();

  /** Indexes already-normalized {@code fields} for {@code ord}, replacing what it had. */
  public void put(int ord, Map<String, Object> fields) {
    remove(ord);
    while (fieldsByOrd.size() <= ord) {
      fieldsByOrd.add(null);
    }
    fieldsByOrd.set(ord, fields);
    fields.forEach(
        (field, value) -> {
          exact
              .computeIfAbsent(field, f -> new HashMap<>())
              .computeIfAbsent(value, v -> new RoaringBitmap())
              .add(ord);
          if (value instanceof Double d) {
            numeric
                .computeIfAbsent(field, f -> new TreeMap<>())
                .computeIfAbsent(d, v -> new RoaringBitmap())
                .add(ord);
          }
        });
  }

  /** Drops {@code ord} from every index. */
  public void remove(int ord) {
    Map<String, Object> fields = get(ord);
    if (fields.isEmpty()) {
      return;
    }
    fieldsByOrd.set(ord, null);
    fields.forEach(
        (field, value) -> {
          removeFrom(exact.get(field), value, ord);
          if (value instanceof Double d) {
            removeFrom(numeric.get(field), d, ord);
          }
        });
  }

  /** Fields of {@code ord}; empty if none. */
  public Map<String, Object> get(int ord) {
    Map<String, Object> fields = ord >= 0 && ord < fieldsByOrd.size() ? fieldsByOrd.get(ord) : null;
    return fields == null ? Map.of() : fields;
  }

  /** Ordinals matching {@code filter}, as a new bitmap the caller may modify. */
  public RoaringBitmap match(Filter filter) {
    return switch (filter) {
      case Filter.Eq eq -> copyOf(bitmap(eq.field(), eq.value()));
      case Filter.In in -> {
        List<RoaringBitmap> parts = new ArrayList<>();
        for (Object value : in.values()) {
          RoaringBitmap part = bitmap(in.field(), value);
          if (part != null) {
            parts.add(part);
          }
        }
        yield FastAggregation.or(parts.iterator());
      }
      case Filter.Range range -> matchRange(range);
      case Filter.And and -> {
        RoaringBitmap result = match(and.filters().get(0));
        for (int i = 1; i < and.filters().size() && !result.isEmpty(); i++) {
          result.and(match(and.filters().get(i)));
        }
        yield result;
      }
      case Filter.Or or -> {
        RoaringBitmap result = new RoaringBitmap();
        for (Filter child : or.filters()) {
          result.or(match(child));
        }
        yield result;
      }
    };
  }

  /**
   * Rewrites ordinals after compaction.
   *
   * @param remap old ordinal to new ordinal, {@code -1} for dropped ones
   */
  public void remap(int[] remap) {
    List<Map<String, Object>> old = new ArrayList<>(fieldsByOrd);
    fieldsByOrd.clear();
    exact.clear();
    numeric.clear();
    for (int ord = 0; ord < old.size() && ord < remap.length; ord++) {
      Map<String, Object> fields = old.get(ord);
      if (fields != null && remap[ord] >= 0) {
        put(remap[ord], fields);
      }
    }
  }

  private RoaringBitmap matchRange(Filter.Range range) {
    NavigableMap<Double, RoaringBitmap> values = numeric.get(range.field());
    if (values == null) {
      return new RoaringBitmap();
    }
    NavigableMap<Double, RoaringBitmap> view = values;
    if (range.gt() != null || range.gte() != null) {
      boolean inclusive = range.gte() != null;
      view = view.tailMap(inclusive ? range.gte() : range.gt(), inclusive);
    }
    if (range.lt() != null || range.lte() != null) {
      boolean inclusive = range.lte() != null;
      view = view.headMap(inclusive ? range.lte() : range.lt(), inclusive);
    }
    Iterator<RoaringBitmap> parts = view.values().iterator();
    return parts.hasNext() ? FastAggregation.or(parts) : new RoaringBitmap();
  }

  private RoaringBitmap bitmap(String field, Object value) {
    Map<Object, RoaringBitmap> values = exact.get(field);
    return values == null ? null : values.get(value);
  }

  private static RoaringBitmap copyOf(RoaringBitmap bitmap) {
    return bitmap == null ? new RoaringBitmap() : bitmap.clone();
  }

  private static <K> void removeFrom(Map<K, RoaringBitmap> values, K key, int ord) {
    RoaringBitmap bitmap = values.get(key);
    bitmap.remove(ord);
    if (bitmap.isEmpty()) {
      values.remove(key);
    }
  }
}
