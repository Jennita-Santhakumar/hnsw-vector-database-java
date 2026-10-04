package dev.thedal.internal.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.thedal.collection.CollectionConfig;
import dev.thedal.collection.IndexType;
import dev.thedal.distance.Metric;
import dev.thedal.filter.Metadata;
import dev.thedal.index.HnswParams;
import dev.thedal.index.NeighborSelection;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Typed WAL payloads (op codes from ARCHITECTURE.md): 1 = UPSERT(id, vector, metadata-json), 2 =
 * DELETE(id), 3 = CREATE_COLLECTION(config), 4 = DROP_COLLECTION(name). Binary big-endian fields;
 * strings as u16 length + UTF-8 (via {@link DataOutputStream#writeUTF}).
 */
public sealed interface WalEntry {

  /** UPSERT op code. */
  byte OP_UPSERT = 1;

  /** DELETE op code. */
  byte OP_DELETE = 2;

  /** CREATE_COLLECTION op code. */
  byte OP_CREATE_COLLECTION = 3;

  /** DROP_COLLECTION op code. */
  byte OP_DROP_COLLECTION = 4;

  /** Op code stored in the record header. */
  byte op();

  /** Payload bytes. */
  byte[] encode();

  /** Insert or replace one point. Metadata is stored as JSON. */
  record Upsert(String id, float[] vector, Map<String, Object> metadata) implements WalEntry {
    /** Copies the vector and metadata. */
    public Upsert {
      vector = vector.clone();
      metadata = Map.copyOf(metadata);
    }

    /** A copy of the vector. */
    @Override
    public float[] vector() {
      return vector.clone();
    }

    @Override
    public byte op() {
      return OP_UPSERT;
    }

    @Override
    public byte[] encode() {
      return Json.write(
          out -> {
            out.writeUTF(id);
            out.writeInt(vector.length);
            for (float x : vector) {
              out.writeFloat(x);
            }
            byte[] json = Json.MAPPER.writeValueAsBytes(metadata);
            out.writeInt(json.length);
            out.write(json);
          });
    }
  }

  /** Delete one point. */
  record Delete(String id) implements WalEntry {
    @Override
    public byte op() {
      return OP_DELETE;
    }

    @Override
    public byte[] encode() {
      return Json.write(out -> out.writeUTF(id));
    }
  }

  /** Create a collection with its full configuration. */
  record CreateCollection(CollectionConfig config) implements WalEntry {
    @Override
    public byte op() {
      return OP_CREATE_COLLECTION;
    }

    @Override
    public byte[] encode() {
      return Json.write(
          out -> {
            out.writeUTF(config.name());
            out.writeInt(config.dim());
            out.writeUTF(config.metric().name());
            out.writeUTF(config.indexType().name());
            HnswParams h = config.hnsw();
            out.writeInt(h.m());
            out.writeInt(h.efConstruction());
            out.writeInt(h.efSearch());
            out.writeLong(h.seed());
            out.writeUTF(h.selection().name());
          });
    }
  }

  /** Drop a collection. */
  record DropCollection(String name) implements WalEntry {
    @Override
    public byte op() {
      return OP_DROP_COLLECTION;
    }

    @Override
    public byte[] encode() {
      return Json.write(out -> out.writeUTF(name));
    }
  }

  /**
   * Decodes a record's payload.
   *
   * @throws IllegalArgumentException for an unknown op code or malformed payload
   */
  static WalEntry decode(WalRecord record) {
    try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(record.payload()))) {
      WalEntry entry =
          switch (record.op()) {
            case OP_UPSERT -> {
              String id = in.readUTF();
              float[] vector = new float[in.readInt()];
              for (int i = 0; i < vector.length; i++) {
                vector[i] = in.readFloat();
              }
              byte[] json = new byte[in.readInt()];
              in.readFully(json);
              Map<String, Object> raw = Json.MAPPER.readValue(json, Json.MAP_TYPE);
              yield new Upsert(id, vector, Metadata.normalize(raw));
            }
            case OP_DELETE -> new Delete(in.readUTF());
            case OP_CREATE_COLLECTION -> {
              String name = in.readUTF();
              int dim = in.readInt();
              Metric metric = Metric.valueOf(in.readUTF());
              IndexType type = IndexType.valueOf(in.readUTF());
              HnswParams hnsw =
                  new HnswParams(
                      in.readInt(),
                      in.readInt(),
                      in.readInt(),
                      in.readLong(),
                      NeighborSelection.valueOf(in.readUTF()));
              yield new CreateCollection(new CollectionConfig(name, dim, metric, type, hnsw));
            }
            case OP_DROP_COLLECTION -> new DropCollection(in.readUTF());
            default -> throw new IllegalArgumentException("unknown WAL op " + record.op());
          };
      if (in.available() > 0) {
        throw new IllegalArgumentException("trailing bytes in WAL record " + record.lsn());
      }
      return entry;
    } catch (IOException e) {
      throw new IllegalArgumentException("malformed WAL record " + record.lsn(), e);
    }
  }

  /** Writes fields through a {@link DataOutputStream}. */
  @FunctionalInterface
  interface FieldWriter {
    void write(DataOutputStream out) throws IOException;
  }

  /** Encoding helpers: shared, thread-safe JSON mapper and a field-writer runner. */
  final class Json {
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private Json() {}

    static byte[] write(FieldWriter writer) {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream out = new DataOutputStream(bytes)) {
        writer.write(out);
      } catch (JsonProcessingException e) {
        throw new IllegalArgumentException("metadata is not serializable", e);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      return bytes.toByteArray();
    }
  }
}
