package com.cache.hashing;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * Consistent hash ring with virtual nodes.
 *
 * Uses MurmurHash3 (32-bit) for uniform key distribution.
 * Virtual nodes (replicas) spread load evenly when nodes join/leave.
 *
 * Thread-safe via ConcurrentSkipListMap.
 */
public class ConsistentHashRing {

    private final int virtualNodesPerPhysical;
    private final ConcurrentSkipListMap<Long, Node> ring = new ConcurrentSkipListMap<>();
    private final Map<String, Node> nodesById = new HashMap<>();

    public ConsistentHashRing(int virtualNodesPerPhysical) {
        this.virtualNodesPerPhysical = virtualNodesPerPhysical;
    }

    public ConsistentHashRing() {
        this(150);
    }

    // ── Ring management ───────────────────────────────────────────────────────

    public synchronized void addNode(Node node) {
        nodesById.put(node.nodeId(), node);
        for (int i = 0; i < virtualNodesPerPhysical; i++) {
            long hash = murmur3(node.nodeId() + "#vn-" + i);
            ring.put(hash, node);
        }
    }

    public synchronized void removeNode(Node node) {
        nodesById.remove(node.nodeId());
        for (int i = 0; i < virtualNodesPerPhysical; i++) {
            long hash = murmur3(node.nodeId() + "#vn-" + i);
            ring.remove(hash);
        }
    }

    // ── Key routing ───────────────────────────────────────────────────────────

    /**
     * Returns the primary node responsible for this key.
     */
    public Optional<Node> getNode(String key) {
        if (ring.isEmpty()) return Optional.empty();
        long hash = murmur3(key);
        var entry = ring.ceilingEntry(hash);
        if (entry == null) entry = ring.firstEntry();
        return Optional.of(entry.getValue());
    }

    /**
     * Returns up to {@code replicationFactor} distinct nodes for this key
     * (primary + replicas), walking the ring clockwise.
     */
    public List<Node> getReplicaNodes(String key, int replicationFactor) {
        if (ring.isEmpty()) return Collections.emptyList();

        long hash = murmur3(key);
        var result = new LinkedHashSet<Node>();

        // Start at the primary position and walk clockwise
        var tailMap = ring.tailMap(hash);
        for (var node : tailMap.values()) {
            result.add(node);
            if (result.size() == replicationFactor) return List.copyOf(result);
        }
        // Wrap around
        for (var node : ring.values()) {
            result.add(node);
            if (result.size() == replicationFactor) break;
        }
        return List.copyOf(result);
    }

    public int size() {
        return nodesById.size();
    }

    public Collection<Node> nodes() {
        return Collections.unmodifiableCollection(nodesById.values());
    }

    // ── MurmurHash3 (32-bit, finalized to long) ───────────────────────────────

    public static long murmur3(String key) {
        byte[] data = key.getBytes(StandardCharsets.UTF_8);
        int len  = data.length;
        int seed = 0x9747b28c;
        int h1   = seed;

        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;

        int i = 0;
        while (i + 4 <= len) {
            int k1 = (data[i]     & 0xFF)
                   | ((data[i+1] & 0xFF) << 8)
                   | ((data[i+2] & 0xFF) << 16)
                   | ((data[i+3] & 0xFF) << 24);

            k1 *= c1;
            k1  = Integer.rotateLeft(k1, 15);
            k1 *= c2;

            h1 ^= k1;
            h1  = Integer.rotateLeft(h1, 13);
            h1  = h1 * 5 + 0xe6546b64;
            i  += 4;
        }

        // Tail
        int k1 = 0;
        switch (len & 3) {
            case 3: k1 ^= (data[i+2] & 0xFF) << 16; // fall through
            case 2: k1 ^= (data[i+1] & 0xFF) << 8;  // fall through
            case 1: k1 ^= (data[i]   & 0xFF);
                    k1 *= c1;
                    k1  = Integer.rotateLeft(k1, 15);
                    k1 *= c2;
                    h1 ^= k1;
        }

        // Finalization
        h1 ^= len;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);

        return Integer.toUnsignedLong(h1);
    }
}
