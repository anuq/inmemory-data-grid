package com.cache.replication;

import com.cache.hashing.ConsistentHashRing;
import com.cache.hashing.Node;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Asynchronous, fire-and-forget replication to peer nodes.
 *
 * Uses a bounded queue to prevent OOM under sustained write pressure.
 * Drops replication tasks if the queue is full (acceptable for cache semantics — eventual consistency).
 */
public class ReplicationManager {

    private static final Logger log      = Logger.getLogger(ReplicationManager.class.getName());
    private static final int    QUEUE_CAP = 10_000;

    private final ConsistentHashRing ring;
    private final String             localNodeId;
    private final int                replicationFactor;
    private final ExecutorService    executor;

    public ReplicationManager(ConsistentHashRing ring, String localNodeId, int replicationFactor) {
        this.ring              = ring;
        this.localNodeId       = localNodeId;
        this.replicationFactor = replicationFactor;

        // Bounded queue: submit() returns immediately; tasks dropped if full
        var queue = new ArrayBlockingQueue<Runnable>(QUEUE_CAP);
        this.executor = new ThreadPoolExecutor(
            2, 4, 60L, TimeUnit.SECONDS, queue,
            r -> {
                var t = new Thread(r, "replication-worker");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.DiscardPolicy()   // drop tasks when queue is full
        );
    }

    /**
     * Asynchronously replicates a SET to all peer nodes that own this key.
     */
    public void replicate(String key, byte[] value, long ttlMillis) {
        if (replicationFactor <= 1 || ring.size() <= 1) return;

        List<Node> replicas = ring.getReplicaNodes(key, replicationFactor);

        for (Node node : replicas) {
            if (node.nodeId().equals(localNodeId)) continue;   // skip self

            executor.submit(() -> {
                try {
                    new PeerClient(node).replicateSet(key, value, ttlMillis);
                    log.fine(() -> "Replicated key=" + key + " to " + node);
                } catch (Exception ex) {
                    log.warning("Replication failed for key=" + key + " peer=" + node + ": " + ex.getMessage());
                }
            });
        }
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
