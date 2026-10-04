package dev.thedal.internal.storage;

/** A snapshot file failed validation (magic, version, CRC, length or consistency). */
public final class SnapshotCorruptedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Creates the exception. */
  public SnapshotCorruptedException(String message, Throwable cause) {
    super(message, cause);
  }
}
