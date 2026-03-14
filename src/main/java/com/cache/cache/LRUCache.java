package com.cache.cache;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * O(1) LRU cache using a doubly-linked list + HashMap.
 *
 * The list maintains recency order: head = MRU, tail = LRU.
 * On eviction, we remove from the tail.
 */
public class LRUCache implements Cache {

    // ── Doubly-linked node ───────────────────────────────────────────────────

    private static final class Node {
        String     key;
        CacheEntry entry;
        Node       prev, next;

        Node(String key, CacheEntry entry) {
            this.key   = key;
            this.entry = entry;
        }
    }

    // ── State ────────────────────────────────────────────────────────────────

    private final int maxSize;
    private final Map<String, Node> map;
    private final Node head, tail;   // sentinels

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock.ReadLock  readLock  = lock.readLock();
    private final ReentrantReadWriteLock.WriteLock writeLock = lock.writeLock();

    // Stats
    private final AtomicLong hits        = new AtomicLong();
    private final AtomicLong misses      = new AtomicLong();
    private final AtomicLong evictions   = new AtomicLong();
    private final AtomicLong expirations = new AtomicLong();

    private final ScheduledExecutorService cleaner;

    public LRUCache(int maxSize) {
        this.maxSize = maxSize;
        this.map     = new HashMap<>(maxSize * 4 / 3 + 1);

        head = new Node(null, null);
        tail = new Node(null, null);
        head.next = tail;
        tail.prev = head;

        cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "lru-ttl-cleaner");
            t.setDaemon(true);
            return t;
        });
        cleaner.scheduleAtFixedRate(this::evictExpired, 5, 5, TimeUnit.SECONDS);
    }

    // ── Cache interface ───────────────────────────────────────────────────────

    @Override
    public Optional<byte[]> get(String key) {
        writeLock.lock();   // write lock because we reorder the list on hit
        try {
            var node = map.get(key);
            if (node == null) {
                misses.incrementAndGet();
                return Optional.empty();
            }
            if (node.entry.isExpired()) {
                removeNode(node);
                map.remove(key);
                expirations.incrementAndGet();
                misses.incrementAndGet();
                return Optional.empty();
            }
            node.entry.touch();
            moveToFront(node);
            hits.incrementAndGet();
            return Optional.of(node.entry.getValue());
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public void put(String key, byte[] value, long ttlMillis) {
        writeLock.lock();
        try {
            var existing = map.get(key);
            if (existing != null) {
                existing.entry.setValue(value);
                moveToFront(existing);
                return;
            }

            if (map.size() >= maxSize) {
                evictLRU();
            }

            var node = new Node(key, new CacheEntry(key, value, ttlMillis));
            map.put(key, node);
            addToFront(node);
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public boolean delete(String key) {
        writeLock.lock();
        try {
            var node = map.remove(key);
            if (node == null) return false;
            removeNode(node);
            return true;
        } finally {
            writeLock.unlock();
        }
    }

    @Override
    public int size() {
        readLock.lock();
        try { return map.size(); }
        finally { readLock.unlock(); }
    }

    @Override
    public CacheStats stats() {
        readLock.lock();
        try {
            return new CacheStats(hits.get(), misses.get(), evictions.get(),
                                  expirations.get(), map.size(), maxSize);
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void clear() {
        writeLock.lock();
        try {
            map.clear();
            head.next = tail;
            tail.prev = head;
        } finally {
            writeLock.unlock();
        }
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private void addToFront(Node node) {
        node.next       = head.next;
        node.prev       = head;
        head.next.prev  = node;
        head.next       = node;
    }

    private void removeNode(Node node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
    }

    private void moveToFront(Node node) {
        removeNode(node);
        addToFront(node);
    }

    private void evictLRU() {
        var lru = tail.prev;
        if (lru == head) return;
        removeNode(lru);
        map.remove(lru.key);
        evictions.incrementAndGet();
    }

    private void evictExpired() {
        writeLock.lock();
        try {
            var node = tail.prev;
            while (node != head) {
                var prev = node.prev;
                if (node.entry.isExpired()) {
                    removeNode(node);
                    map.remove(node.key);
                    expirations.incrementAndGet();
                }
                node = prev;
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void shutdown() {
        cleaner.shutdownNow();
    }
}
