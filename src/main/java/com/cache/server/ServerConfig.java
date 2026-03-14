package com.cache.server;

import com.cache.cache.EvictionPolicy;
import com.cache.hashing.Node;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/**
 * Loaded from {@code cache.properties} on classpath, or system properties.
 */
public record ServerConfig(
    String         nodeId,
    String         host,
    int            port,
    int            maxEntries,
    EvictionPolicy evictionPolicy,
    List<Node>     peers,
    int            replicationFactor,
    int            workerThreads
) {
    private static final String PROPS_FILE = "cache.properties";

    public static ServerConfig load() {
        var props = new Properties();

        // Try loading from file first
        var propsPath = Path.of(PROPS_FILE);
        if (Files.exists(propsPath)) {
            try (InputStream in = Files.newInputStream(propsPath)) {
                props.load(in);
            } catch (IOException ignored) {}
        } else {
            // Fall back to classpath
            try (InputStream in = ServerConfig.class.getClassLoader().getResourceAsStream(PROPS_FILE)) {
                if (in != null) props.load(in);
            } catch (IOException ignored) {}
        }

        String nodeId    = get(props, "cache.node.id",          "node-1");
        String host      = get(props, "cache.host",             "127.0.0.1");
        int    port      = getInt(props, "cache.port",          6379);
        int    maxEntries= getInt(props, "cache.max.entries",   10_000);
        var    policy    = EvictionPolicy.from(get(props, "cache.eviction.policy", "LRU"));
        int    repFactor = getInt(props, "cache.replication.factor", 1);
        int    workers   = getInt(props, "cache.worker.threads", Runtime.getRuntime().availableProcessors());

        List<Node> peers = parsePeers(get(props, "cache.peers", ""));

        return new ServerConfig(nodeId, host, port, maxEntries, policy, peers, repFactor, workers);
    }

    private static List<Node> parsePeers(String peersStr) {
        if (peersStr.isBlank()) return List.of();
        return Arrays.stream(peersStr.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(peer -> {
                String[] parts = peer.split(":");
                if (parts.length != 3) {
                    throw new IllegalArgumentException("Invalid peer format (expected nodeId:host:port): " + peer);
                }
                return new Node(parts[0], parts[1], Integer.parseInt(parts[2]));
            })
            .toList();
    }

    private static String get(Properties p, String key, String defaultVal) {
        return System.getProperty(key, p.getProperty(key, defaultVal));
    }

    private static int getInt(Properties p, String key, int defaultVal) {
        return Integer.parseInt(get(p, key, String.valueOf(defaultVal)));
    }
}
