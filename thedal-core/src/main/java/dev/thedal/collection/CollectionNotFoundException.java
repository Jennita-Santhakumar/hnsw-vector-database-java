package dev.thedal.collection;

/** No collection has the requested name. */
public final class CollectionNotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception for {@code name}. */
  public CollectionNotFoundException(String name) {
    super("collection '" + name + "' does not exist");
  }
}
