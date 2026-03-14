package com.cache.cache;

public record CacheStats(
    long hits,
    long misses,
    long evictions,
    long expirations,
    int  currentSize,
    int  maxSize
) {
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    @Override
    public String toString() {
        return String.format(
            "CacheStats{hits=%d, misses=%d, hitRate=%.2f%%, evictions=%d, expirations=%d, size=%d/%d}",
            hits, misses, hitRate() * 100, evictions, expirations, currentSize, maxSize);
    }
}
