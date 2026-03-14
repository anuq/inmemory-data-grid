package com.cache.cache;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A single cached value with metadata for eviction policies.
 */
public final class CacheEntry {

    private final String key;
    private volatile byte[] value;
    private final long ttlMillis;          // 0 = no expiry
    private final Instant createdAt;
    private final AtomicLong lastAccessedAt;
    private final AtomicInteger accessCount;

    public CacheEntry(String key, byte[] value, long ttlMillis) {
        this.key            = key;
        this.value          = value;
        this.ttlMillis      = ttlMillis;
        this.createdAt      = Instant.now();
        this.lastAccessedAt = new AtomicLong(System.currentTimeMillis());
        this.accessCount    = new AtomicInteger(1);
    }

    public String getKey()   { return key; }
    public byte[] getValue() { return value; }

    public void setValue(byte[] value) {
        this.value = value;
        touch();
    }

    /** Update access metadata on a cache hit. */
    public void touch() {
        lastAccessedAt.set(System.currentTimeMillis());
        accessCount.incrementAndGet();
    }

    public int getAccessCount()    { return accessCount.get(); }
    public long getLastAccessedAt(){ return lastAccessedAt.get(); }
    public Instant getCreatedAt()  { return createdAt; }
    public long getTtlMillis()     { return ttlMillis; }

    public boolean isExpired() {
        if (ttlMillis <= 0) return false;
        return System.currentTimeMillis() - createdAt.toEpochMilli() > ttlMillis;
    }
}
