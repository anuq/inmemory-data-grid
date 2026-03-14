package com.cache;

import com.cache.protocol.BinaryProtocol;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class BinaryProtocolTest {

    @Test
    void encodeDecodeGetRoundTrip() {
        var encoded = BinaryProtocol.encodeGet("my-key");
        var request = BinaryProtocol.tryDecodeRequest(encoded);

        assertNotNull(request);
        assertEquals(BinaryProtocol.CMD_GET, request.commandType());
        assertEquals("my-key", request.key());
        assertEquals(0, request.value().length);
        assertEquals(0L, request.ttlMillis());
    }

    @Test
    void encodeDecodeSetRoundTrip() {
        byte[] value = "hello world".getBytes(StandardCharsets.UTF_8);
        var encoded  = BinaryProtocol.encodeSet("key123", value, 5000L);
        var request  = BinaryProtocol.tryDecodeRequest(encoded);

        assertNotNull(request);
        assertEquals(BinaryProtocol.CMD_SET, request.commandType());
        assertEquals("key123", request.key());
        assertArrayEquals(value, request.value());
        assertEquals(5000L, request.ttlMillis());
    }

    @Test
    void encodeDecodeDeleteRoundTrip() {
        var encoded = BinaryProtocol.encodeDelete("to-delete");
        var request = BinaryProtocol.tryDecodeRequest(encoded);

        assertNotNull(request);
        assertEquals(BinaryProtocol.CMD_DEL, request.commandType());
        assertEquals("to-delete", request.key());
    }

    @Test
    void encodeDecodeStatsRoundTrip() {
        var encoded = BinaryProtocol.encodeStats();
        var request = BinaryProtocol.tryDecodeRequest(encoded);

        assertNotNull(request);
        assertEquals(BinaryProtocol.CMD_STATS, request.commandType());
    }

    @Test
    void incompleteFrameReturnsNull() {
        var full = BinaryProtocol.encodeGet("key");

        // Truncate the buffer by 1 byte
        var truncated = ByteBuffer.allocate(full.limit() - 1);
        byte[] tmp = new byte[full.limit() - 1];
        full.get(tmp);
        truncated.put(tmp);
        truncated.flip();

        var result = BinaryProtocol.tryDecodeRequest(truncated);
        assertNull(result, "Incomplete frame should return null");

        // Buffer position should be reset (mark preserved)
        assertEquals(0, truncated.position(), "Buffer position should be reset after failed decode");
    }

    @Test
    void okResponseEncodeDecodeRoundTrip() {
        var buf  = BinaryProtocol.encodeOk();
        var resp = BinaryProtocol.tryDecodeResponse(buf);

        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertEquals(0, resp.payload().length);
    }

    @Test
    void valueResponseEncodeDecodeRoundTrip() {
        byte[] payload = "cached-value".getBytes(StandardCharsets.UTF_8);
        var buf  = BinaryProtocol.encodeResponse(BinaryProtocol.STATUS_OK, payload);
        var resp = BinaryProtocol.tryDecodeResponse(buf);

        assertNotNull(resp);
        assertTrue(resp.isOk());
        assertArrayEquals(payload, resp.payload());
        assertEquals("cached-value", resp.payloadAsString());
    }

    @Test
    void notFoundResponseRoundTrip() {
        var buf  = BinaryProtocol.encodeNotFound();
        var resp = BinaryProtocol.tryDecodeResponse(buf);

        assertNotNull(resp);
        assertTrue(resp.isNotFound());
    }

    @Test
    void errorResponseContainsMessage() {
        var buf  = BinaryProtocol.encodeError("Something went wrong");
        var resp = BinaryProtocol.tryDecodeResponse(buf);

        assertNotNull(resp);
        assertEquals(BinaryProtocol.STATUS_ERROR, resp.status());
        assertEquals("Something went wrong", resp.payloadAsString());
    }

    @Test
    void multipleFramesInBuffer() {
        // Concatenate two GET requests in one buffer
        var get1 = BinaryProtocol.encodeGet("key1");
        var get2 = BinaryProtocol.encodeGet("key2");

        var combined = ByteBuffer.allocate(get1.limit() + get2.limit());
        combined.put(get1).put(get2).flip();

        var req1 = BinaryProtocol.tryDecodeRequest(combined);
        assertNotNull(req1);
        assertEquals("key1", req1.key());

        var req2 = BinaryProtocol.tryDecodeRequest(combined);
        assertNotNull(req2);
        assertEquals("key2", req2.key());

        // Buffer should be empty now
        assertNull(BinaryProtocol.tryDecodeRequest(combined));
    }

    @Test
    void unicodeKeyHandledCorrectly() {
        String unicodeKey = "こんにちは-key-😀";
        var encoded = BinaryProtocol.encodeGet(unicodeKey);
        var request = BinaryProtocol.tryDecodeRequest(encoded);

        assertNotNull(request);
        assertEquals(unicodeKey, request.key());
    }

    @Test
    void largeValueRoundTrip() {
        byte[] largeValue = new byte[1024 * 1024];  // 1 MB
        for (int i = 0; i < largeValue.length; i++) {
            largeValue[i] = (byte) (i % 256);
        }

        var encoded = BinaryProtocol.encodeSet("big-key", largeValue, 0);
        var request = BinaryProtocol.tryDecodeRequest(encoded);

        assertNotNull(request);
        assertArrayEquals(largeValue, request.value());
    }
}
