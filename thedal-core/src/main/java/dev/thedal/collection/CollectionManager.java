package dev.thedal.collection;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry of collections by name. Thread-safe; each {@link Collection} handles its own locking.
 */
public final class CollectionManager {

  private final ConcurrentMap<String, Collection> collections = new ConcurrentHashMap<>();

  /**
   * Creates and registers a collection.
   *
   * @throws CollectionAlreadyExistsException if the name is taken
   */
  public Collection create(CollectionConfig config) {
    Collection collection = new Collection(config);
    if (collections.putIfAbsent(config.name(), collection) != null) {
      throw new CollectionAlreadyExistsException(config.name());
    }
    return collection;
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
   * Removes the named collection. Operations already holding the instance finish normally.
   *
   * @throws CollectionNotFoundException if there is none
   */
  public void drop(String name) {
    if (collections.remove(name) == null) {
      throw new CollectionNotFoundException(name);
    }
  }

  /** All collections, sorted by name. */
  public List<Collection> list() {
    return collections.values().stream().sorted(Comparator.comparing(Collection::name)).toList();
  }
}
