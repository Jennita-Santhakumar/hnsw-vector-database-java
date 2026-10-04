package dev.thedal.collection;

import dev.thedal.filter.Metadata;
import dev.thedal.internal.store.IdMap;
import java.util.Map;

/**
 * A point to upsert or a stored point: id, vector and metadata. The id and metadata are validated
 * on construction; the vector is checked against the collection on upsert. The vector is copied in
 * and out, so instances are immutable.
 */
public record Point(String id, float[] vector, Map<String, Object> metadata) {

  /** Validates the id, normalizes metadata and copies the vector. */
  public Point {
    IdMap.requireValidId(id);
    if (vector == null) {
      throw new IllegalArgumentException("point '" + id + "' has no vector");
    }
    vector = vector.clone();
    metadata = Map.copyOf(Metadata.normalize(metadata));
  }

  /** Point without metadata. */
  public Point(String id, float[] vector) {
    this(id, vector, Map.of());
  }

  /** A copy of the vector. */
  @Override
  public float[] vector() {
    return vector.clone();
  }
}
