package dev.thedal.collection;

import dev.thedal.internal.storage.Fs;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

/**
 * Registry of collections by name, in memory or durable under a data directory ({@code
 * <dataDir>/<name>/}). Lookups are lock-free; create and drop are serialized. Each {@link
 * Collection} handles its own locking.
 */
public final class CollectionManager implements Closeable {

  /** Marks a collection directory that is being deleted. */
  static final String DROPPED_SUFFIX = ".dropped-";

  private final ConcurrentMap<String, Collection> collections = new ConcurrentHashMap<>();
  private final Path dataDir;
  private final DurabilityConfig durability;

  /** Serializes create, drop and close (private: no outside code can take it). */
  private final Object lifecycleLock = new Object();

  /** An in-memory manager: nothing survives a restart. */
  public CollectionManager() {
    this(null, null);
  }

  private CollectionManager(Path dataDir, DurabilityConfig durability) {
    this.dataDir = dataDir;
    this.durability = durability;
  }

  /**
   * Opens (or initializes) a data directory and recovers every collection in it. Leftovers of
   * interrupted drops and of creates that never became durable are removed.
   */
  public static CollectionManager open(Path dataDir, DurabilityConfig durability) {
    CollectionManager manager = new CollectionManager(dataDir, durability);
    try {
      Files.createDirectories(dataDir);
      List<Path> dirs;
      try (Stream<Path> entries = Files.list(dataDir)) {
        dirs = entries.filter(Files::isDirectory).sorted().toList();
      }
      for (Path dir : dirs) {
        Path fileName = dir.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        if (name.contains(DROPPED_SUFFIX)) {
          Fs.deleteRecursively(dir);
          continue;
        }
        Collection collection = Collection.recover(dir, durability);
        if (collection == null) {
          Fs.deleteRecursively(dir);
        } else {
          manager.collections.put(collection.name(), collection);
        }
      }
    } catch (IOException e) {
      throw new UncheckedIOException("opening data directory " + dataDir, e);
    }
    return manager;
  }

  /** Whether collections are persisted. */
  public boolean isDurable() {
    return dataDir != null;
  }

  /**
   * Creates and registers a collection (durably, if this manager is durable).
   *
   * @throws CollectionAlreadyExistsException if the name is taken; in durable mode also when it
   *     differs only in case (directories collide on case-insensitive filesystems)
   */
  public Collection create(CollectionConfig config) {
    synchronized (lifecycleLock) {
      String name = config.name();
      boolean taken =
          collections.containsKey(name)
              || (isDurable() && collections.keySet().stream().anyMatch(name::equalsIgnoreCase));
      if (taken) {
        throw new CollectionAlreadyExistsException(name);
      }
      Collection collection =
          isDurable()
              ? Collection.createDurable(config, dataDir.resolve(name), durability)
              : new Collection(config);
      collections.put(name, collection);
      return collection;
    }
  }

  /**
   * Returns the named collection.
   *
   * @throws CollectionNotFoundException if there is none
   */
  public Collection get(String name) {
    Collection collection = collections.get(name);
    if (collection == null) {
      throw new CollectionNotFoundException(name);
    }
    return collection;
  }

  /**
   * Removes the named collection and, if durable, its data. Operations already holding the
   * in-memory instance finish normally; durable writes to it then fail.
   *
   * @throws CollectionNotFoundException if there is none
   */
  public void drop(String name) {
    synchronized (lifecycleLock) {
      Collection collection = collections.remove(name);
      if (collection == null) {
        throw new CollectionNotFoundException(name);
      }
      collection.dropStorage();
    }
  }

  /** All collections, sorted by name. */
  public List<Collection> list() {
    return collections.values().stream().sorted(Comparator.comparing(Collection::name)).toList();
  }

  /** Closes every collection's WAL. */
  @Override
  public void close() {
    synchronized (lifecycleLock) {
      collections.values().forEach(Collection::close);
    }
  }
}
