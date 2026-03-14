package com.cache.replication;

import com.cache.hashing.Node;
import com.cache.protocol.BinaryProtocol;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Minimal blocking NIO client for sending replication writes to a peer node.
 * Creates a new connection per call — suitable for low-frequency async replication.
 * For high-throughput scenarios, upgrade to a persistent connection pool.
 */
public class PeerClient {

    private static final Logger log = Logger.getLogger(PeerClient.class.getName());
    private static final int CONNECT_TIMEOUT_MS = 2_000;

    private final Node peer;

    public PeerClient(Node peer) {
        this.peer = peer;
    }

    /**
     * Sends a SET command to the peer. Fire-and-forget — does not wait for response.
     */
    public void replicateSet(String key, byte[] value, long ttlMillis) {
        try (var channel = SocketChannel.open()) {
            channel.configureBlocking(true);
            channel.socket().connect(
                new InetSocketAddress(peer.host(), peer.port()), CONNECT_TIMEOUT_MS);

            ByteBuffer frame = BinaryProtocol.encodeSet(key, value, ttlMillis);
            while (frame.hasRemaining()) {
                channel.write(frame);
            }

            // Read and discard the OK response
            ByteBuffer responseBuf = ByteBuffer.allocate(64);
            channel.read(responseBuf);
        } catch (IOException ex) {
            log.log(Level.WARNING, "Replication to peer {0} failed: {1}",
                    new Object[]{peer, ex.getMessage()});
        }
    }
}
