package dev.thedal.collection;

/**
 * Point-in-time statistics of a collection.
 *
 * @param count live points
 * @param tombstones deleted or overwritten points not yet compacted away
 * @param memoryBytes vectors + index structure + tombstones (metadata excluded)
 */
public record CollectionStats(
    CollectionConfig config, int count, int tombstones, long memoryBytes, long compactions) {}
