package com.cache.server;

import com.cache.cache.Cache;
import com.cache.cache.EvictionPolicy;
import com.cache.cache.LFUCache;
import com.cache.cache.LRUCache;
import com.cache.hashing.ConsistentHashRing;
import com.cache.hashing.Node;
import com.cache.replication.ReplicationManager;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Non-blocking NIO cache server using a single Selector loop.
 *
 * Architecture:
 *   - Main thread: Selector loop (accept + I/O events)
 *   - Worker pool: CPU-bound cache operations
 *   - Replication pool: async writes to peers
 *   - Stats thread: periodic ops/sec and hit-rate logging
 */
public class CacheServer {

    private static final Logger log = Logger.getLogger(CacheServer.class.getName());

    private final ServerConfig      config;
    private final Cache             cache;
    private final ReplicationManager replicationManager;
    private final ScheduledExecutorService statsScheduler;

    private volatile boolean running = true;
    private Selector selector;
    private ServerSocketChannel serverChannel;

    public CacheServer(ServerConfig config) {
        this.config = config;
        this.cache  = createCache(config);

        var ring = new ConsistentHashRing();
        ring.addNode(new Node(config.nodeId(), config.host(), config.port()));
        config.peers().forEach(ring::addNode);

        this.replicationManager = new ReplicationManager(ring, config.nodeId(), config.replicationFactor());

        this.statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "cache-stats");
            t.setDaemon(true);
            return t;
        });
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void start() throws IOException {
        selector      = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false);
        serverChannel.socket().setReuseAddress(true);
        serverChannel.bind(new InetSocketAddress(config.host(), config.port()));
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);

        registerShutdownHook();

        statsScheduler.scheduleAtFixedRate(this::logStats, 10, 10, TimeUnit.SECONDS);

        log.info(String.format("Cache server [%s] started on %s:%d | policy=%s maxEntries=%d",
            config.nodeId(), config.host(), config.port(),
            config.evictionPolicy(), config.maxEntries()));

        if (!config.peers().isEmpty()) {
            log.info("Peers: " + config.peers());
        }

        selectorLoop();
    }

    // ── Selector loop ─────────────────────────────────────────────────────────

    private void selectorLoop() {
        while (running) {
            try {
                int ready = selector.select(1000);
                if (ready == 0) continue;

                var keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    var key = keys.next();
                    keys.remove();

                    if (!key.isValid()) continue;

                    if (key.isAcceptable()) {
                        accept(key);
                    } else if (key.isReadable()) {
                        read(key);
                    } else if (key.isWritable()) {
                        write(key);
                    }
                }
            } catch (IOException ex) {
                if (running) log.log(Level.WARNING, "Selector error", ex);
            }
        }
        cleanup();
    }

    private void accept(SelectionKey key) throws IOException {
        var serverCh = (ServerSocketChannel) key.channel();
        var client   = serverCh.accept();
        if (client == null) return;

        client.configureBlocking(false);
        client.socket().setTcpNoDelay(true);

        var clientKey = client.register(selector, SelectionKey.OP_READ);
        var handler   = new ConnectionHandler(client, clientKey, cache, replicationManager);
        clientKey.attach(handler);

        String addr;
        try { addr = String.valueOf(client.getRemoteAddress()); } catch (IOException e) { addr = "unknown"; }
        final String remoteAddr = addr;
        log.fine(() -> "Client connected: " + remoteAddr);
    }

    private void read(SelectionKey key) {
        var handler = (ConnectionHandler) key.attachment();
        try {
            handler.handleRead();
        } catch (IOException ex) {
            log.fine(() -> "Client disconnected: " + ex.getMessage());
            handler.close();
        }
    }

    private void write(SelectionKey key) {
        var handler = (ConnectionHandler) key.attachment();
        try {
            handler.handleWrite();
        } catch (IOException ex) {
            handler.close();
        }
    }

    // ── Stats ─────────────────────────────────────────────────────────────────

    private void logStats() {
        log.info(cache.stats().toString());
    }

    // ── Shutdown ──────────────────────────────────────────────────────────────

    private void registerShutdownHook() {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutting down cache server...");
            running = false;
            try { if (selector != null) selector.close(); } catch (IOException ignored) {}
            replicationManager.shutdown();
            statsScheduler.shutdownNow();
        }, "shutdown-hook"));
    }

    private void cleanup() {
        try {
            if (serverChannel != null) serverChannel.close();
            if (selector != null) selector.close();
        } catch (IOException ignored) {}
        log.info("Cache server stopped.");
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    private static Cache createCache(ServerConfig config) {
        return config.evictionPolicy() == EvictionPolicy.LFU
            ? new LFUCache(config.maxEntries())
            : new LRUCache(config.maxEntries());
    }

    // ── Main ──────────────────────────────────────────────────────────────────

    public static void main(String[] args) throws IOException {
        if (args.length > 0 && args[0].equals("cli")) {
            com.cache.cli.CacheCLI.main(args);
            return;
        }

        var config = ServerConfig.load();

        // Allow overrides from CLI args: --port=6380 --nodeId=node-2
        for (String arg : args) {
            if (arg.startsWith("--port=")) {
                System.setProperty("cache.port", arg.substring(7));
            } else if (arg.startsWith("--nodeId=")) {
                System.setProperty("cache.node.id", arg.substring(9));
            } else if (arg.startsWith("--policy=")) {
                System.setProperty("cache.eviction.policy", arg.substring(9));
            }
        }

        if (args.length > 0) config = ServerConfig.load();   // reload with overrides

        new CacheServer(config).start();
    }
}
