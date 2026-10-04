package dev.thedal.internal.storage;

/**
 * A bad record somewhere other than the tail of the last segment. That is not a torn write from a
 * crash, so recovery refuses to guess: discarding later segments could lose acknowledged writes.
 */
public final class WalCorruptedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public WalCorruptedException(String message) {
    super(message);
  }
}
