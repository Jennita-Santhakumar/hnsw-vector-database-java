package dev.thedal.internal.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thedal.filter.Metadata;
import java.io.IOException;
import java.util.Map;

/** JSON encoding of point metadata for the WAL and snapshots. Thread-safe. */
public final class MetadataJson {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

  private MetadataJson() {}

  /** Encodes normalized metadata as UTF-8 JSON. */
  public static byte[] write(Map<String, Object> metadata) throws IOException {
    return MAPPER.writeValueAsBytes(metadata);
  }

  /** Decodes and normalizes metadata written by {@link #write}. */
  public static Map<String, Object> read(byte[] json) throws IOException {
    return Metadata.normalize(MAPPER.readValue(json, MAP_TYPE));
  }
}
