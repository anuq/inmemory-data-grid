package com.cache

import com.cache.hashing.ConsistentHashRing
import com.cache.hashing.Node
import spock.lang.Specification
import spock.lang.Subject
import spock.lang.Title
import spock.lang.Narrative

@Title("Consistent Hash Ring Behaviour")
@Narrative("""
    A consistent hash ring routes keys to nodes using MurmurHash3.
    Virtual nodes (150 per physical node) ensure uniform distribution.
    When a node is added or removed, only keys on the adjacent ring
    segment are remapped — minimising disruption.
""")
class ConsistentHashRingSpec extends Specification {

    @Subject
    ConsistentHashRing ring = new ConsistentHashRing(10) // 10 vnodes for fast tests

    Node nodeA = new Node("node-A", "localhost", 7001)
    Node nodeB = new Node("node-B", "localhost", 7002)
    Node nodeC = new Node("node-C", "localhost", 7003)

    // ── Empty ring ───────────────────────────────────────────────────────────

    def "an empty ring returns empty for any key lookup"() {
        expect:
        ring.getNode("any-key").isEmpty()
    }

    def "an empty ring returns empty replica list"() {
        expect:
        ring.getReplicaNodes("any-key", 3).isEmpty()
    }

    // ── Single-node ring ─────────────────────────────────────────────────────

    def "a single node handles all key lookups"() {
        given:
        ring.addNode(nodeA)

        expect:
        ring.getNode("hello").get() == nodeA
        ring.getNode("world").get() == nodeA
        ring.getNode("foo-bar-baz").get() == nodeA
    }

    // ── Multi-node routing ───────────────────────────────────────────────────

    def "keys are distributed across multiple nodes"() {
        given:
        ring.addNode(nodeA)
        ring.addNode(nodeB)
        ring.addNode(nodeC)

        when:
        def nodes = (1..100).collect { i -> ring.getNode("key-$i").get() }.toSet()

        then: "at least two distinct nodes received keys"
        nodes.size() >= 2
    }

    def "the same key always resolves to the same node (determinism)"() {
        given:
        ring.addNode(nodeA)
        ring.addNode(nodeB)
        ring.addNode(nodeC)

        when:
        def first  = ring.getNode("deterministic").get()
        def second = ring.getNode("deterministic").get()

        then:
        first == second
    }

    // ── Replica nodes ────────────────────────────────────────────────────────

    def "getReplicaNodes returns the requested number of distinct nodes"() {
        given:
        ring.addNode(nodeA)
        ring.addNode(nodeB)
        ring.addNode(nodeC)

        when:
        def replicas = ring.getReplicaNodes("replicated-key", 2)

        then:
        replicas.size() == 2
        replicas.toSet().size() == 2   // distinct
    }

    def "replica count is capped by the number of available nodes"() {
        given:
        ring.addNode(nodeA)
        ring.addNode(nodeB)

        when:
        def replicas = ring.getReplicaNodes("key", 5)

        then: "at most 2 replicas since only 2 nodes exist"
        replicas.size() <= 2
    }

    // ── Node add / remove ────────────────────────────────────────────────────

    def "adding a node increases the ring size"() {
        when:
        ring.addNode(nodeA)
        ring.addNode(nodeB)

        then:
        ring.size() == 2
    }

    def "removing a node decreases the ring size"() {
        given:
        ring.addNode(nodeA)
        ring.addNode(nodeB)
        ring.addNode(nodeC)

        when:
        ring.removeNode(nodeB)

        then:
        ring.size() == 2
        !ring.nodes().contains(nodeB)
    }

    def "after removing a node its keys are reassigned to remaining nodes"() {
        given:
        ring.addNode(nodeA)
        ring.addNode(nodeB)
        def keyNode = ring.getNode("migrate-me").get()

        when: "remove whichever node held the key"
        ring.removeNode(keyNode)

        then: "the key is still routable"
        ring.getNode("migrate-me").isPresent()
        ring.getNode("migrate-me").get() != keyNode
    }

    def "adding a node does not disrupt keys assigned to other nodes"() {
        given: "a larger ring for stable distribution with 50 vnodes"
        def bigRing = new ConsistentHashRing(50)
        bigRing.addNode(nodeA)
        bigRing.addNode(nodeB)
        def before = (1..200).collect { i -> bigRing.getNode("key-${i}").get().nodeId() }

        when: "nodeC is added and disruption measured"
        bigRing.addNode(nodeC)
        def after     = (1..200).collect { i -> bigRing.getNode("key-${i}").get().nodeId() }
        def unchanged = [before, after].transpose().count { it[0] == it[1] }

        then: "fewer than 50% of keys are remapped (consistent hashing guarantee)"
        unchanged >= 100
    }

    // ── MurmurHash3 ──────────────────────────────────────────────────────────

    def "murmur3 is deterministic for the same input"() {
        expect:
        ConsistentHashRing.murmur3("spock") == ConsistentHashRing.murmur3("spock")
    }

    def "murmur3 produces different hashes for different inputs"() {
        expect:
        ConsistentHashRing.murmur3("node-A") != ConsistentHashRing.murmur3("node-B")
    }
}
