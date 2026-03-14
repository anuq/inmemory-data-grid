package com.cache.hashing;

/**
 * Represents a physical cache node in the cluster.
 */
public record Node(String nodeId, String host, int port) {

    public String address() {
        return host + ":" + port;
    }

    @Override
    public String toString() {
        return "Node[" + nodeId + "@" + address() + "]";
    }
}
