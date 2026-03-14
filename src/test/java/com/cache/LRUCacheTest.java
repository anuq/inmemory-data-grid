package com.cache;

import com.cache.cache.LRUCache;
import org.junit.jupiter.api.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LRUCacheTest {

    private LRUCache cache;

    @BeforeEach
    void setUp() {
        cache = new LRUCache(4);
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Test
    void getReturnsStoredValue() {
        cache.put("key1", "value1".getBytes(), 0);
        var result = cache.get("key1");
        assertTrue(result.isPresent());
        assertEquals("value1", new String(result.get()));
    }

    @Test
    void getMissingKeyReturnsEmpty() {
        assertTrue(cache.get("nonexistent").isEmpty());
    }

    @Test
    void evictsLRUWhenFull() {
        cache.put("a", "A".getBytes(), 0);
        cache.put("b", "B".getBytes(), 0);
        cache.put("c", "C".getBytes(), 0);
        cache.put("d", "D".getBytes(), 0);

        // Access "a" to make it recently used
        cache.get("a");

        // Insert 5th entry — should evict "b" (LRU)
        cache.put("e", "E".getBytes(), 0);

        assertTrue(cache.get("a").isPresent(), "a should still be present (recently accessed)");
        assertTrue(cache.get("b").isEmpty(),   "b should have been evicted (LRU)");
        assertTrue(cache.get("e").isPresent(), "e should be present (just inserted)");

        assertEquals(1, cache.stats().evictions());
    }

    @Test
    void updateMovesKeyToMRU() {
        cache.put("a", "A1".getBytes(), 0);
        cache.put("b", "B".getBytes(), 0);
        cache.put("c", "C".getBytes(), 0);
        cache.put("d", "D".getBytes(), 0);

        // Update "a" — should move it to MRU
        cache.put("a", "A2".getBytes(), 0);

        // Insert 5th — should evict "b" (now LRU), not "a"
        cache.put("e", "E".getBytes(), 0);

        assertEquals("A2", new String(cache.get("a").orElseThrow()));
        assertTrue(cache.get("b").isEmpty(), "b should be evicted");
    }

    @Test
    void deleteRemovesEntry() {
        cache.put("key", "val".getBytes(), 0);
        assertTrue(cache.delete("key"));
        assertTrue(cache.get("key").isEmpty());
        assertFalse(cache.delete("key"));   // second delete returns false
    }

    @Test
    void ttlExpiresEntry() throws InterruptedException {
        cache.put("key", "val".getBytes(), 100);  // 100ms TTL
        assertTrue(cache.get("key").isPresent());

        Thread.sleep(200);

        assertTrue(cache.get("key").isEmpty(), "Entry should have expired");
    }

    @Test
    void sizeTracksCorrectly() {
        assertEquals(0, cache.size());
        cache.put("a", "A".getBytes(), 0);
        cache.put("b", "B".getBytes(), 0);
        assertEquals(2, cache.size());
        cache.delete("a");
        assertEquals(1, cache.size());
    }

    @Test
    void statsTrackHitsAndMisses() {
        cache.put("k", "v".getBytes(), 0);
        cache.get("k");      // hit
        cache.get("k");      // hit
        cache.get("miss");   // miss

        var stats = cache.stats();
        assertEquals(2, stats.hits());
        assertEquals(1, stats.misses());
        assertEquals(2.0 / 3.0, stats.hitRate(), 0.001);
    }

    @Test
    void concurrentAccessIsSafe() throws InterruptedException {
        int threads = 8;
        int ops     = 1000;

        var executor = Executors.newFixedThreadPool(threads);
        var latch    = new CountDownLatch(threads);
        var errors   = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < ops; i++) {
                        String key = "key-" + (threadId * ops + i) % 100;
                        cache.put(key, ("val-" + i).getBytes(), 0);
                        cache.get(key);
                    }
                } catch (Exception ex) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertEquals(0, errors.get(), "No concurrent errors expected");
    }
}
