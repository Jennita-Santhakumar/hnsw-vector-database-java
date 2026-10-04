package dev.thedal.collection;

/** A collection with the requested name already exists. */
public final class CollectionAlreadyExistsException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception for {@code name}. */
  public CollectionAlreadyExistsException(String name) {
    super("collection '" + name + "' already exists");
  }
}
