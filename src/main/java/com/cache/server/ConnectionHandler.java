package com.cache.server;

import com.cache.cache.Cache;
import com.cache.protocol.BinaryProtocol;
import com.cache.replication.ReplicationManager;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.logging.Logger;

/**
 * Per-connection state machine.
 * Manages read accumulation and write flushing for a single client channel.
 */
public class ConnectionHandler {

    private static final Logger log = Logger.getLogger(ConnectionHandler.class.getName());

    private static final int READ_BUFFER_SIZE  = 64 * 1024;
    private static final int WRITE_BUFFER_SIZE = 64 * 1024;

    private final SocketChannel       channel;
    private final SelectionKey        key;
    private final Cache               cache;
    private final ReplicationManager  replication;

    private final ByteBuffer readBuf  = ByteBuffer.allocate(READ_BUFFER_SIZE);
    private final Queue<ByteBuffer> writeQueue = new ArrayDeque<>();

    public ConnectionHandler(SocketChannel channel, SelectionKey key,
                             Cache cache, ReplicationManager replication) {
        this.channel     = channel;
        this.key         = key;
        this.cache       = cache;
        this.replication = replication;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    public void handleRead() throws IOException {
        int bytesRead = channel.read(readBuf);
        if (bytesRead == -1) {
            close();
            return;
        }

        readBuf.flip();
        BinaryProtocol.Request request;
        while ((request = BinaryProtocol.tryDecodeRequest(readBuf)) != null) {
            ByteBuffer response = dispatch(request);
            enqueueWrite(response);
        }
        readBuf.compact();
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    private ByteBuffer dispatch(BinaryProtocol.Request req) {
        try {
            return switch (req.commandType()) {
                case BinaryProtocol.CMD_GET   -> handleGet(req);
                case BinaryProtocol.CMD_SET   -> handleSet(req);
                case BinaryProtocol.CMD_DEL   -> handleDelete(req);
                case BinaryProtocol.CMD_STATS -> handleStats();
                default -> BinaryProtocol.encodeError("Unknown command: 0x" + Integer.toHexString(req.commandType()));
            };
        } catch (Exception ex) {
            log.warning("Error handling command: " + ex.getMessage());
            return BinaryProtocol.encodeError("Internal error: " + ex.getMessage());
        }
    }

    private ByteBuffer handleGet(BinaryProtocol.Request req) {
        return cache.get(req.key())
            .map(value -> BinaryProtocol.encodeResponse(BinaryProtocol.STATUS_OK, value))
            .orElse(BinaryProtocol.encodeNotFound());
    }

    private ByteBuffer handleSet(BinaryProtocol.Request req) {
        cache.put(req.key(), req.value(), req.ttlMillis());
        // Async replicate to peers
        replication.replicate(req.key(), req.value(), req.ttlMillis());
        return BinaryProtocol.encodeOk();
    }

    private ByteBuffer handleDelete(BinaryProtocol.Request req) {
        cache.delete(req.key());
        return BinaryProtocol.encodeOk();
    }

    private ByteBuffer handleStats() {
        String stats = cache.stats().toString();
        return BinaryProtocol.encodeResponse(BinaryProtocol.STATUS_OK,
                                              stats.getBytes(StandardCharsets.UTF_8));
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    public void handleWrite() throws IOException {
        while (!writeQueue.isEmpty()) {
            var buf = writeQueue.peek();
            channel.write(buf);
            if (buf.hasRemaining()) break;   // socket buffer full — retry next time
            writeQueue.poll();
        }

        if (writeQueue.isEmpty()) {
            key.interestOps(SelectionKey.OP_READ);   // stop watching OP_WRITE
        }
    }

    private void enqueueWrite(ByteBuffer buf) {
        writeQueue.add(buf);
        key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
        key.selector().wakeup();
    }

    public void close() {
        try {
            key.cancel();
            channel.close();
        } catch (IOException ignored) {}
    }
}
