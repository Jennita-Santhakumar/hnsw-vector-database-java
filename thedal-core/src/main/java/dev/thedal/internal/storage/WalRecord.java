package dev.thedal.internal.storage;

/**
 * One raw WAL record as stored on disk: {@code | u32 length | u32 crc32 | u8 op | u64 lsn | payload
 * |}, big-endian. {@code length} counts op + lsn + payload; the CRC32 covers the same bytes.
 */
public record WalRecord(byte op, long lsn, byte[] payload) {

  /** Bytes before the CRC-covered body: length + crc. */
  static final int PREFIX_BYTES = 8;

  /** Bytes of op + lsn. */
  static final int BODY_HEADER_BYTES = 9;

  /** Upper bound on one record's body; anything larger is treated as corruption. */
  static final int MAX_BODY_BYTES = 32 << 20;

  /** Copies the payload so the record is immutable. */
  public WalRecord {
    payload = payload.clone();
  }

  /** A copy of the payload. */
  @Override
  public byte[] payload() {
    return payload.clone();
  }

  /** Size of this record on disk. */
  int encodedSize() {
    return PREFIX_BYTES + BODY_HEADER_BYTES + payload.length;
  }
}
