package com.cache.cache;

import java.util.Optional;

/**
 * Core cache interface — implementations must be thread-safe.
 */
public interface Cache {

    /**
     * Returns the value for the key, or empty if absent or expired.
     */
    Optional<byte[]> get(String key);

    /**
     * Inserts or updates the entry.
     * @param ttlMillis time-to-live in milliseconds; 0 = no expiry
     */
    void put(String key, byte[] value, long ttlMillis);

    /**
     * Removes the entry. Returns true if it existed.
     */
    boolean delete(String key);

    /**
     * Current number of entries (excluding expired).
     */
    int size();

    /**
     * Cumulative statistics snapshot.
     */
    CacheStats stats();

    /**
     * Flush all entries (useful for testing).
     */
    void clear();
}
