package com.cache;

import com.cache.hashing.ConsistentHashRing;
import com.cache.hashing.Node;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashRingTest {

    private ConsistentHashRing ring;
    private final Node node1 = new Node("n1", "127.0.0.1", 6379);
    private final Node node2 = new Node("n2", "127.0.0.1", 6380);
    private final Node node3 = new Node("n3", "127.0.0.1", 6381);

    @BeforeEach
    void setUp() {
        ring = new ConsistentHashRing(150);
    }

    @Test
    void returnsEmptyWhenRingIsEmpty() {
        assertTrue(ring.getNode("any-key").isEmpty());
    }

    @Test
    void singleNodeOwnsAllKeys() {
        ring.addNode(node1);
        for (int i = 0; i < 100; i++) {
            var node = ring.getNode("key-" + i);
            assertTrue(node.isPresent());
            assertEquals("n1", node.get().nodeId());
        }
    }

    @Test
    void addingNodeRedistributesKeys() {
        ring.addNode(node1);
        ring.addNode(node2);

        // Both nodes should own some keys
        Map<String, Integer> dist = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            String nodeId = ring.getNode("key-" + i).orElseThrow().nodeId();
            dist.merge(nodeId, 1, Integer::sum);
        }

        assertTrue(dist.containsKey("n1"), "n1 should own some keys");
        assertTrue(dist.containsKey("n2"), "n2 should own some keys");

        // With 150 virtual nodes each, distribution should be roughly 50/50 ± 15%
        int n1Count = dist.getOrDefault("n1", 0);
        assertTrue(n1Count > 300 && n1Count < 700,
            "Expected roughly balanced distribution, got n1=" + n1Count + "/1000");
    }

    @Test
    void removingNodeReassignsKeys() {
        ring.addNode(node1);
        ring.addNode(node2);
        ring.addNode(node3);

        // Record which node owns each key before removal
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            before.put("k" + i, ring.getNode("k" + i).orElseThrow().nodeId());
        }

        ring.removeNode(node2);
        assertEquals(2, ring.size());

        // Keys previously owned by n2 must now go to n1 or n3
        int migrated = 0;
        for (var e : before.entrySet()) {
            var newOwner = ring.getNode(e.getKey()).orElseThrow().nodeId();
            if (!newOwner.equals(e.getValue())) {
                // If key moved, it must have been on n2 before
                assertEquals("n2", e.getValue(),
                    "Only keys from removed node should migrate");
                migrated++;
            }
        }
        assertTrue(migrated > 0, "Some keys should have migrated from n2");
    }

    @Test
    void replicaNodesAreDistinct() {
        ring.addNode(node1);
        ring.addNode(node2);
        ring.addNode(node3);

        List<Node> replicas = ring.getReplicaNodes("some-key", 3);
        assertEquals(3, replicas.size());

        // All nodes should be distinct
        long distinct = replicas.stream().map(Node::nodeId).distinct().count();
        assertEquals(3, distinct, "Replica nodes should all be distinct");
    }

    @Test
    void replicaFactorClampedByRingSize() {
        ring.addNode(node1);
        ring.addNode(node2);

        // Requesting 3 replicas when only 2 nodes exist
        List<Node> replicas = ring.getReplicaNodes("key", 3);
        assertEquals(2, replicas.size(), "Should return at most ring.size() distinct nodes");
    }

    @Test
    void murmurHashIsStable() {
        // Same input should always produce the same hash
        long h1 = ConsistentHashRing.murmur3("test-key");
        long h2 = ConsistentHashRing.murmur3("test-key");
        assertEquals(h1, h2);

        // Different inputs should (almost certainly) produce different hashes
        long h3 = ConsistentHashRing.murmur3("other-key");
        assertNotEquals(h1, h3);
    }
}
