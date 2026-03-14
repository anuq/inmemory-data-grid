package com.cache

import com.cache.cache.LFUCache
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Narrative

@Title("LFU Cache Eviction Behaviour")
@Narrative("""
    An LFU (Least-Frequently-Used) cache evicts the entry with the lowest
    access frequency. Ties are broken by recency (LRU among equal-frequency
    entries). All operations run in O(1) using the frequency-bucket
    LinkedHashSet technique.
""")
class LFUCacheSpec extends Specification {

    @Subject
    LFUCache cache = new LFUCache(3)

    def cleanup() {
        cache.shutdown()
    }

    // ── Basic get / put ──────────────────────────────────────────────────────

    def "get on an empty cache returns empty"() {
        expect:
        cache.get("anything").isEmpty()
    }

    def "put and get round-trips a value correctly"() {
        given:
        cache.put("lang", "Groovy".bytes, 0)

        expect:
        new String(cache.get("lang").get()) == "Groovy"
    }

    def "updating an existing key does not change cache size"() {
        given:
        cache.put("k", "v1".bytes, 0)

        when:
        cache.put("k", "v2".bytes, 0)

        then:
        cache.size() == 1
        new String(cache.get("k").get()) == "v2"
    }

    // ── Frequency-based eviction ─────────────────────────────────────────────

    def "evicts the least-frequently-used entry when at capacity"() {
        given: "three entries — a accessed 3x, b 2x, c 1x"
        cache.put("a", "A".bytes, 0)
        cache.put("b", "B".bytes, 0)
        cache.put("c", "C".bytes, 0)
        3.times { cache.get("a") }
        2.times { cache.get("b") }
        1.times { cache.get("c") }

        when: "a new entry is inserted"
        cache.put("d", "D".bytes, 0)

        then: "c (lowest frequency) is evicted"
        cache.get("c").isEmpty()
        cache.get("a").isPresent()
        cache.get("b").isPresent()
        cache.get("d").isPresent()
    }

    def "among equal-frequency entries the least-recently-used is evicted first"() {
        given: "all three entries accessed exactly once (equal frequency)"
        cache.put("first",  "F".bytes, 0)
        cache.put("second", "S".bytes, 0)
        cache.put("third",  "T".bytes, 0)
        // access order: first, second, third → third is MRU
        cache.get("first")
        cache.get("second")
        cache.get("third")

        when: "a new entry pushes one out"
        cache.put("fourth", "X".bytes, 0)

        then: "'first' (LRU among equal freq) is evicted"
        cache.get("first").isEmpty()
        cache.get("second").isPresent()
        cache.get("third").isPresent()
        cache.get("fourth").isPresent()
    }

    def "each access increments frequency and prevents eviction"() {
        given:
        cache.put("hot",  "H".bytes, 0)
        cache.put("warm", "W".bytes, 0)
        cache.put("cold", "C".bytes, 0)

        and: "hot is accessed many times"
        100.times { cache.get("hot") }

        when: "two new entries are inserted, filling capacity twice"
        cache.put("n1", "1".bytes, 0)
        cache.put("n2", "2".bytes, 0)

        then: "hot survives because of high frequency"
        cache.get("hot").isPresent()
    }

    // ── Delete ───────────────────────────────────────────────────────────────

    def "delete removes the key and returns true"() {
        given:
        cache.put("rm", "val".bytes, 0)

        when:
        def deleted = cache.delete("rm")

        then:
        deleted == true
        cache.get("rm").isEmpty()
    }

    def "delete on a missing key returns false"() {
        expect:
        cache.delete("phantom") == false
    }

    // ── TTL ──────────────────────────────────────────────────────────────────

    def "expired entries are treated as misses and do not count as hits"() {
        given:
        cache.put("ttl-key", "short-lived".bytes, 60L)

        when:
        Thread.sleep(120)
        def result = cache.get("ttl-key")

        then:
        result.isEmpty()
        cache.stats().misses() >= 1
    }

    // ── Stats ────────────────────────────────────────────────────────────────

    def "stats reflect accurate hit and miss counts"() {
        given:
        cache.put("present", "yes".bytes, 0)

        when:
        cache.get("present") // hit
        cache.get("absent")  // miss

        then:
        cache.stats().hits()   >= 1
        cache.stats().misses() >= 1
    }
}
