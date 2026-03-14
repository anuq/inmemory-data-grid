package com.cache;

import com.cache.cache.LFUCache;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

class LFUCacheTest {

    private LFUCache cache;

    @BeforeEach
    void setUp() {
        cache = new LFUCache(3);
    }

    @AfterEach
    void tearDown() {
        cache.shutdown();
    }

    @Test
    void getReturnsValue() {
        cache.put("k", "v".getBytes(), 0);
        assertTrue(cache.get("k").isPresent());
        assertEquals("v", new String(cache.get("k").get()));
    }

    @Test
    void evictsLFUEntry() {
        cache.put("a", "A".getBytes(), 0);  // freq=1
        cache.put("b", "B".getBytes(), 0);  // freq=1
        cache.put("c", "C".getBytes(), 0);  // freq=1

        // Access "a" and "b" to increase their frequency
        cache.get("a");   // a freq=2
        cache.get("a");   // a freq=3
        cache.get("b");   // b freq=2

        // "c" has the lowest frequency (1) — should be evicted
        cache.put("d", "D".getBytes(), 0);

        assertTrue(cache.get("a").isPresent(), "a (freq=3) should survive");
        assertTrue(cache.get("b").isPresent(), "b (freq=2) should survive");
        assertTrue(cache.get("c").isEmpty(),   "c (freq=1) should be evicted");
        assertTrue(cache.get("d").isPresent(), "d should be present (just inserted)");
    }

    @Test
    void updateIncreasesFrequency() {
        cache.put("a", "A1".getBytes(), 0);  // freq=1
        cache.put("b", "B".getBytes(),  0);  // freq=1
        cache.put("c", "C".getBytes(),  0);  // freq=1

        cache.put("a", "A2".getBytes(), 0);  // update — freq becomes 2

        // "b" or "c" should be evicted (freq=1), not "a"
        cache.put("d", "D".getBytes(), 0);

        assertEquals("A2", new String(cache.get("a").orElseThrow()),
            "a should have updated value");
        assertTrue(cache.get("d").isPresent());
    }

    @Test
    void tieBreakByLRU() {
        // When two entries have the same frequency, evict the least recently used
        cache.put("a", "A".getBytes(), 0);   // freq=1 inserted first
        cache.put("b", "B".getBytes(), 0);   // freq=1 inserted second
        cache.put("c", "C".getBytes(), 0);   // freq=1 inserted third

        // "a" was inserted first — among freq=1, it's LRU → should be evicted
        cache.put("d", "D".getBytes(), 0);

        assertTrue(cache.get("a").isEmpty(),   "a (oldest with freq=1) should be evicted");
        assertTrue(cache.get("b").isPresent());
        assertTrue(cache.get("c").isPresent());
        assertTrue(cache.get("d").isPresent());
    }

    @Test
    void deleteRemovesEntry() {
        cache.put("k", "v".getBytes(), 0);
        cache.get("k");  // freq=2
        assertTrue(cache.delete("k"));
        assertTrue(cache.get("k").isEmpty());
        assertFalse(cache.delete("k"));
    }

    @Test
    void ttlExpiresEntry() throws InterruptedException {
        cache.put("k", "v".getBytes(), 80);
        assertTrue(cache.get("k").isPresent());
        Thread.sleep(150);
        assertTrue(cache.get("k").isEmpty(), "should have expired");
    }

    @Test
    void statsTrackEvictions() {
        cache.put("a", "A".getBytes(), 0);
        cache.put("b", "B".getBytes(), 0);
        cache.put("c", "C".getBytes(), 0);
        cache.put("d", "D".getBytes(), 0);  // causes eviction

        assertEquals(1, cache.stats().evictions());
    }
}
