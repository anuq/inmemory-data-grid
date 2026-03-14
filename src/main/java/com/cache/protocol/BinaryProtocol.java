package com.cache.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Custom binary protocol encoder/decoder.
 *
 * Request frame:
 *   [1]  command type  (0x01=GET, 0x02=SET, 0x03=DEL, 0x04=STATS)
 *   [4]  key length    (big-endian int)
 *   [N]  key bytes     (UTF-8)
 *   [4]  value length  (0 for GET/DEL/STATS)
 *   [M]  value bytes
 *   [8]  TTL millis    (0 = no expiry)
 *
 * Response frame:
 *   [1]  status  (0x00=OK, 0x01=NOT_FOUND, 0x02=ERROR)
 *   [4]  payload length
 *   [N]  payload bytes
 */
public final class BinaryProtocol {

    public static final byte CMD_GET   = 0x01;
    public static final byte CMD_SET   = 0x02;
    public static final byte CMD_DEL   = 0x03;
    public static final byte CMD_STATS = 0x04;

    public static final byte STATUS_OK        = 0x00;
    public static final byte STATUS_NOT_FOUND = 0x01;
    public static final byte STATUS_ERROR     = 0x02;

    public static final int MIN_REQUEST_SIZE  = 1 + 4 + 0 + 4 + 0 + 8;  // 17 bytes

    private BinaryProtocol() {}

    // ── Encoding ──────────────────────────────────────────────────────────────

    public static ByteBuffer encodeGet(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        var buf = ByteBuffer.allocate(1 + 4 + keyBytes.length + 4 + 8);
        buf.put(CMD_GET);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(0);          // value length = 0
        buf.putLong(0L);        // TTL = 0
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeSet(String key, byte[] value, long ttlMillis) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        var buf = ByteBuffer.allocate(1 + 4 + keyBytes.length + 4 + value.length + 8);
        buf.put(CMD_SET);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(value.length);
        buf.put(value);
        buf.putLong(ttlMillis);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeDelete(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        var buf = ByteBuffer.allocate(1 + 4 + keyBytes.length + 4 + 8);
        buf.put(CMD_DEL);
        buf.putInt(keyBytes.length);
        buf.put(keyBytes);
        buf.putInt(0);
        buf.putLong(0L);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeStats() {
        var buf = ByteBuffer.allocate(1 + 4 + 4 + 8);
        buf.put(CMD_STATS);
        buf.putInt(0);   // key length = 0
        buf.putInt(0);   // value length = 0
        buf.putLong(0L);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeResponse(byte status, byte[] payload) {
        var buf = ByteBuffer.allocate(1 + 4 + payload.length);
        buf.put(status);
        buf.putInt(payload.length);
        buf.put(payload);
        buf.flip();
        return buf;
    }

    public static ByteBuffer encodeOk() {
        return encodeResponse(STATUS_OK, new byte[0]);
    }

    public static ByteBuffer encodeNotFound() {
        return encodeResponse(STATUS_NOT_FOUND, new byte[0]);
    }

    public static ByteBuffer encodeError(String message) {
        return encodeResponse(STATUS_ERROR, message.getBytes(StandardCharsets.UTF_8));
    }

    // ── Decoding ──────────────────────────────────────────────────────────────

    /**
     * Attempt to decode a complete request from the buffer.
     * Returns null if the buffer does not yet contain a full frame.
     * The buffer position is advanced past the consumed bytes on success.
     */
    public static Request tryDecodeRequest(ByteBuffer buf) {
        buf.mark();

        if (buf.remaining() < MIN_REQUEST_SIZE) {
            buf.reset();
            return null;
        }

        byte cmdType = buf.get();
        int  keyLen  = buf.getInt();

        if (keyLen < 0 || keyLen > 65535) {
            buf.reset();
            return null;
        }
        if (buf.remaining() < keyLen + 4 + 8) {
            buf.reset();
            return null;
        }

        byte[] keyBytes = new byte[keyLen];
        buf.get(keyBytes);
        String key = new String(keyBytes, StandardCharsets.UTF_8);

        int valLen = buf.getInt();
        if (valLen < 0 || valLen > 64 * 1024 * 1024) {
            buf.reset();
            return null;
        }
        if (buf.remaining() < valLen + 8) {
            buf.reset();
            return null;
        }

        byte[] value = new byte[valLen];
        if (valLen > 0) buf.get(value);

        long ttl = buf.getLong();

        return new Request(cmdType, key, value, ttl);
    }

    /**
     * Decode a response from the buffer. Returns null if incomplete.
     */
    public static Response tryDecodeResponse(ByteBuffer buf) {
        buf.mark();
        if (buf.remaining() < 5) { buf.reset(); return null; }

        byte status     = buf.get();
        int  payloadLen = buf.getInt();

        if (buf.remaining() < payloadLen) { buf.reset(); return null; }

        byte[] payload = new byte[payloadLen];
        buf.get(payload);
        return new Response(status, payload);
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record Request(byte commandType, String key, byte[] value, long ttlMillis) {}

    public record Response(byte status, byte[] payload) {
        public boolean isOk()       { return status == STATUS_OK;        }
        public boolean isNotFound() { return status == STATUS_NOT_FOUND; }
        public String  payloadAsString() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }
}
