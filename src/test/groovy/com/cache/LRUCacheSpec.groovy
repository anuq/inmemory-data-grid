package com.cache

import com.cache.cache.LRUCache
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Narrative

@Title("LRU Cache Eviction Behaviour")
@Narrative("""
    An LRU (Least-Recently-Used) cache with a fixed capacity evicts the
    entry that was accessed least recently when the cache is full.
    All operations (get, put, delete) run in O(1) time using a
    doubly-linked list + HashMap.
""")
class LRUCacheSpec extends Specification {

    @Subject
    LRUCache cache = new LRUCache(3)

    def cleanup() {
        cache.shutdown()
    }

    // ── Basic get / put ──────────────────────────────────────────────────────

    def "get returns empty Optional for a key that was never inserted"() {
        when:
        def result = cache.get("ghost")

        then:
        result.isEmpty()
    }

    def "put then get returns the stored value"() {
        given:
        cache.put("city", "Boston".bytes, 0)

        when:
        def result = cache.get("city")

        then:
        result.isPresent()
        new String(result.get()) == "Boston"
    }

    def "overwriting an existing key updates the value without growing the cache"() {
        given:
        cache.put("k", "v1".bytes, 0)

        when:
        cache.put("k", "v2".bytes, 0)

        then:
        cache.size() == 1
        new String(cache.get("k").get()) == "v2"
    }

    // ── Eviction order ───────────────────────────────────────────────────────

    def "inserts beyond capacity evict the least-recently-used entry"() {
        given: "cache filled with three entries"
        cache.put("a", "1".bytes, 0)
        cache.put("b", "2".bytes, 0)
        cache.put("c", "3".bytes, 0)

        and: "a and b are accessed — making c the LRU"
        cache.get("a")
        cache.get("b")

        when: "a fourth entry is inserted"
        cache.put("d", "4".bytes, 0)

        then: "c (LRU) is gone, everything else remains"
        cache.get("c").isEmpty()
        cache.get("a").isPresent()
        cache.get("b").isPresent()
        cache.get("d").isPresent()
        cache.stats().evictions() >= 1
    }

    def "a get promotes the entry so it is not the next eviction victim"() {
        given:
        cache.put("x", "X".bytes, 0)
        cache.put("y", "Y".bytes, 0)
        cache.put("z", "Z".bytes, 0)

        and: "touch x so it becomes MRU; y is now LRU"
        cache.get("x")

        when:
        cache.put("new", "N".bytes, 0)

        then: "y (LRU) was evicted, not x"
        cache.get("y").isEmpty()
        cache.get("x").isPresent()
        cache.get("z").isPresent()
        cache.get("new").isPresent()
    }

    def "inserting the same key repeatedly never evicts anything"() {
        when: "same key written many times"
        (1..10).each { i -> cache.put("stable", "val-$i".bytes, 0) }

        then:
        cache.size() == 1
        cache.stats().evictions() == 0
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    def "delete removes the entry and returns true"() {
        given:
        cache.put("del", "value".bytes, 0)

        when:
        def result = cache.delete("del")

        then:
        result == true
        cache.get("del").isEmpty()
        cache.size() == 0
    }

    def "delete on a non-existent key returns false"() {
        when:
        def result = cache.delete("never-existed")

        then:
        result == false
    }

    // ── TTL / expiry ─────────────────────────────────────────────────────────

    def "entries with an expired TTL are treated as misses"() {
        given:
        cache.put("fleeting", "value".bytes, 50L) // 50 ms TTL

        when:
        Thread.sleep(120)
        def result = cache.get("fleeting")

        then:
        result.isEmpty()
        cache.stats().expirations() >= 1
    }

    def "entries with no TTL (0) never expire"() {
        given:
        cache.put("permanent", "value".bytes, 0L)

        when:
        Thread.sleep(50)

        then:
        cache.get("permanent").isPresent()
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    def "stats correctly track hits and misses"() {
        given:
        cache.put("h", "hit-me".bytes, 0)

        when:
        cache.get("h")       // hit
        cache.get("h")       // hit
        cache.get("miss1")   // miss
        cache.get("miss2")   // miss

        then:
        cache.stats().hits()   == 2
        cache.stats().misses() == 2
    }

    // ── Clear ────────────────────────────────────────────────────────────────

    def "clear empties the cache"() {
        given:
        cache.put("a", "1".bytes, 0)
        cache.put("b", "2".bytes, 0)

        when:
        cache.clear()

        then:
        cache.size() == 0
        cache.get("a").isEmpty()
        cache.get("b").isEmpty()
    }
}
