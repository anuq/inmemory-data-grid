package com.cache.cache;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * O(1) LFU cache using the frequency-bucket LinkedHashSet technique.
 *
 * Data structures:
 *   keyMap:   key → [CacheEntry, frequency]
 *   freqMap:  frequency → LinkedHashSet<key>  (insertion-ordered for tie-breaking by recency)
 *   minFreq:  current minimum frequency (for O(1) eviction)
 *
 * On access: move key from freqMap[f] → freqMap[f+1], update minFreq if needed.
 * On evict:  remove first element of freqMap[minFreq] (LRU among least-frequent).
 */
public class LFUCache implements Cache {

    private static final class Entry {
        CacheEntry cacheEntry;
        int        freq;

        Entry(CacheEntry cacheEntry, int freq) {
            this.cacheEntry = cacheEntry;
            this.freq       = freq;
        }
    }

    private final int maxSize;
    private final Map<String, Entry>                 keyMap;
    private final Map<Integer, LinkedHashSet<String>> freqMap;
    private int minFreq;

    private final ReentrantReadWriteLock lock      = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = lock.readLock();

    private final AtomicLong hits        = new AtomicLong();
    private final AtomicLong misses      = new AtomicLong();
    private final AtomicLong evictions   = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();

    private final ScheduledExecutorService cleaner;

    public LFUCache(int maxSize) {
        this.maxSize = maxSize;
        this.keyMap  = new HashMap<>(maxSize * 4 / 3 + 1);
        this.freqMap = new HashMap<>();
        this.minFreq = 0;

        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "lfu-ttl-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.SECONDS);
    }

    @Override
    public Optional<byte[]> get(String key) {
        writeLock.lock();
        try {
            var entry = keyMap.get(key);
            if (entry == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            if (entry.cacheEntry.isExpired()) {
                removeEntry(key, entry);
                expirations.incrementAndGet();
                misses.incrementAndGet();
                return Optional.empty();
            }
            promote(key, entry);
            entry.cacheEntry.touch();
            hits.incrementAndGet();
            return Optional.of(entry.cacheEntry.getValue());
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void put(String key, byte[] value, long ttlMillis) {
        writeLock.lock();
        try {
            if (keyMap.containsKey(key)) {
                var entry = keyMap.get(key);
                entry.cacheEntry.setValue(value);
                promote(key, entry);
                return;
            }

            if (keyMap.size() >= maxSize) {
                evictLFU();
            }

            var cacheEntry = new CacheEntry(key, value, ttlMillis);
            var entry      = new Entry(cacheEntry, 1);
            keyMap.put(key, entry);
            freqMap.computeIfAbsent(1, k -> new LinkedHashSet<>()).add(key);
            minFreq = 1;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean delete(String key) {
        writeLock.lock();
        try {
            var entry = keyMap.remove(key);
            if (entry == null) return false;
            var bucket = freqMap.get(entry.freq);
            if (bucket != null) bucket.remove(key);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int size() {
        readLock.lock();
        try { return keyMap.size(); }
        finally { readLock.unlock(); }
    }

    @Override
    public CacheStats stats() {
        readLock.lock();
        try {
            return new CacheStats(hits.get(), misses.get(), evictions.get(),
                                  expirations.get(), keyMap.size(), maxSize);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            keyMap.clear();
            freqMap.clear();
            minFreq = 0;
        } finally {
            writeLock.unlock();
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void promote(String key, Entry entry) {
        int oldFreq = entry.freq;
        int newFreq = oldFreq + 1;

        freqMap.get(oldFreq).remove(key);
        if (freqMap.get(oldFreq).isEmpty()) {
            freqMap.remove(oldFreq);
            if (minFreq == oldFreq) minFreq = newFreq;
        }

        entry.freq = newFreq;
        freqMap.computeIfAbsent(newFreq, k -> new LinkedHashSet<>()).add(key);
    }

    private void evictLFU() {
        var leastFreqSet = freqMap.get(minFreq);
        if (leastFreqSet == null || leastFreqSet.isEmpty()) return;

        var victim = leastFreqSet.iterator().next();   // oldest among min-freq
        leastFreqSet.remove(victim);
        if (leastFreqSet.isEmpty()) freqMap.remove(minFreq);
        keyMap.remove(victim);
        evictions.incrementAndGet();
    }

    private void removeEntry(String key, Entry entry) {
        keyMap.remove(key);
        var set = freqMap.get(entry.freq);
        if (set != null) {
            set.remove(key);
            if (set.isEmpty()) freqMap.remove(entry.freq);
        }
    }

    private void evictExpired() {
        writeLock.lock();
        try {
            var expired = keyMap.entrySet().stream()
                .filter(e -> e.getValue().cacheEntry.isExpired())
                .map(Map.Entry::getKey)
                .toList();
            expired.forEach(key -> {
                removeEntry(key, keyMap.get(key));
                expirations.incrementAndGet();
            });
        } finally {
            writeLock.unlock();
        }
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}
