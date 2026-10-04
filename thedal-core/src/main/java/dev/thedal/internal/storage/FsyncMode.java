package dev.thedal.internal.storage;

/** When appended WAL records are forced to disk. */
public enum FsyncMode {
  /** fsync before every acknowledgement: no acknowledged write is ever lost. */
  ALWAYS,
  /**
   * Group commit: a flusher fsyncs every batch interval and wakes all writers it covered. Writers
   * are still only acknowledged after their fsync, so nothing acknowledged is lost, at the cost of
   * up to one interval of added latency.
   */
  BATCH
}
