package dev.thedal.internal.storage;

import java.time.Duration;

/**
 * WAL settings.
 *
 * @param fsyncMode when records are forced to disk
 * @param batchInterval group-commit period for {@link FsyncMode#BATCH}
 * @param segmentBytes size after which the writer rotates to a new segment file
 */
public record WalConfig(FsyncMode fsyncMode, Duration batchInterval, long segmentBytes) {

  /** fsync=always, 10 ms batch interval (unused), 64 MiB segments. */
  public static final WalConfig DEFAULT =
      new WalConfig(FsyncMode.ALWAYS, Duration.ofMillis(10), 64L << 20);

  /** Validates the settings. */
  public WalConfig {
    if (fsyncMode == null) {
      throw new IllegalArgumentException("fsyncMode is required");
    }
    if (batchInterval == null || batchInterval.isNegative() || batchInterval.isZero()) {
      throw new IllegalArgumentException("batchInterval must be positive");
    }
    if (segmentBytes < 1024) {
      throw new IllegalArgumentException("segmentBytes must be >= 1024: " + segmentBytes);
    }
  }
}
