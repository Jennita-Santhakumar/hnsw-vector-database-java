package dev.thedal.collection;

import dev.thedal.internal.storage.WalConfig;

/**
 * Persistence settings for collections stored on disk.
 *
 * @param wal write-ahead log settings (fsync mode, group-commit interval, segment size)
 * @param snapshotEveryRecords take a snapshot (and truncate the WAL) after this many WAL records
 */
public record DurabilityConfig(WalConfig wal, long snapshotEveryRecords) {

  /** fsync=always, 64 MiB segments, snapshot every 100,000 records. */
  public static final DurabilityConfig DEFAULT = new DurabilityConfig(WalConfig.DEFAULT, 100_000);

  /** Validates the settings. */
  public DurabilityConfig {
    if (wal == null) {
      throw new IllegalArgumentException("wal config is required");
    }
    if (snapshotEveryRecords < 1) {
      throw new IllegalArgumentException("snapshotEveryRecords must be >= 1");
    }
  }
}
