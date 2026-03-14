package com.cache

import com.cache.protocol.BinaryProtocol
import spock.lang.Specification
import spock.lang.Title
import spock.lang.Narrative

import java.nio.ByteBuffer

@Title("Custom Binary Protocol Encode / Decode")
@Narrative("""
    The binary protocol serialises cache commands into compact frames.
    A GET frame has no value bytes; a SET frame carries key, value and TTL;
    a DEL frame carries only the key. Responses carry a status byte and an
    optional payload. tryDecodeRequest returns null when the buffer is incomplete.
""")
class BinaryProtocolSpec extends Specification {

    // ── GET encode / decode ──────────────────────────────────────────────────

    def "encodeGet produces a frame with CMD_GET and the correct key"() {
        when:
        def buf = BinaryProtocol.encodeGet("city")
        def req = BinaryProtocol.tryDecodeRequest(buf)

        then:
        req != null
        req.commandType() == BinaryProtocol.CMD_GET
        req.key()         == "city"
        req.value().length == 0
        req.ttlMillis()   == 0L
    }

    def "encodeGet with a Unicode key round-trips correctly"() {
        when:
        def buf = BinaryProtocol.encodeGet("こんにちは")
        def req = BinaryProtocol.tryDecodeRequest(buf)

        then:
        req.key() == "こんにちは"
    }

    // ── SET encode / decode ──────────────────────────────────────────────────

    def "encodeSet round-trips key, value and TTL"() {
        given:
        def key   = "framework"
        def value = "Spock".bytes
        def ttl   = 60_000L

        when:
        def buf = BinaryProtocol.encodeSet(key, value, ttl)
        def req = BinaryProtocol.tryDecodeRequest(buf)

        then:
        req.commandType() == BinaryProtocol.CMD_SET
        req.key()         == key
        new String(req.value()) == "Spock"
        req.ttlMillis()   == ttl
    }

    def "encodeSet with empty value encodes successfully"() {
        when:
        def buf = BinaryProtocol.encodeSet("empty-val", new byte[0], 0)
        def req = BinaryProtocol.tryDecodeRequest(buf)

        then:
        req.commandType() == BinaryProtocol.CMD_SET
        req.value().length == 0
    }

    def "encodeSet with TTL=0 means no expiry"() {
        when:
        def buf = BinaryProtocol.encodeSet("permanent", "data".bytes, 0L)
        def req = BinaryProtocol.tryDecodeRequest(buf)

        then:
        req.ttlMillis() == 0L
    }

    // ── DEL encode / decode ──────────────────────────────────────────────────

    def "encodeDelete produces a CMD_DEL frame with the correct key"() {
        when:
        def buf = BinaryProtocol.encodeDelete("bye")
        def req = BinaryProtocol.tryDecodeRequest(buf)

        then:
        req.commandType() == BinaryProtocol.CMD_DEL
        req.key()         == "bye"
        req.value().length == 0
    }

    // ── STATS encode / decode ────────────────────────────────────────────────

    def "encodeStats produces a CMD_STATS frame with no key or value"() {
        when:
        def buf = BinaryProtocol.encodeStats()
        def req = BinaryProtocol.tryDecodeRequest(buf)

        then:
        req.commandType() == BinaryProtocol.CMD_STATS
        req.key()         == ""
        req.value().length == 0
    }

    // ── Response encode / decode ─────────────────────────────────────────────

    def "encodeOk produces a STATUS_OK response with no payload"() {
        when:
        def buf  = BinaryProtocol.encodeOk()
        def resp = BinaryProtocol.tryDecodeResponse(buf)

        then:
        resp.isOk()
        resp.payload().length == 0
    }

    def "encodeNotFound produces a STATUS_NOT_FOUND response"() {
        when:
        def buf  = BinaryProtocol.encodeNotFound()
        def resp = BinaryProtocol.tryDecodeResponse(buf)

        then:
        resp.isNotFound()
        !resp.isOk()
    }

    def "encodeError embeds the error message in the payload"() {
        when:
        def buf  = BinaryProtocol.encodeError("key too long")
        def resp = BinaryProtocol.tryDecodeResponse(buf)

        then:
        resp.status()          == BinaryProtocol.STATUS_ERROR
        resp.payloadAsString() == "key too long"
    }

    def "encodeResponse with custom payload round-trips the payload bytes"() {
        given:
        def payload = "hits=10,misses=2".bytes

        when:
        def buf  = BinaryProtocol.encodeResponse(BinaryProtocol.STATUS_OK, payload)
        def resp = BinaryProtocol.tryDecodeResponse(buf)

        then:
        resp.payloadAsString() == "hits=10,misses=2"
    }

    // ── Incomplete buffer handling ───────────────────────────────────────────

    def "tryDecodeRequest returns null when buffer is too small"() {
        given:
        def buf = ByteBuffer.allocate(4)
        buf.putInt(42)
        buf.flip()

        expect:
        BinaryProtocol.tryDecodeRequest(buf) == null
    }

    def "tryDecodeRequest returns null when value bytes are missing"() {
        given: "manually craft a partial SET frame — header present but value truncated"
        def key      = "partial".getBytes("UTF-8")
        def valueLen = 100
        def buf = ByteBuffer.allocate(1 + 4 + key.length + 4) // omit value + TTL
        buf.put(BinaryProtocol.CMD_SET)
        buf.putInt(key.length)
        buf.put(key)
        buf.putInt(valueLen)
        buf.flip()

        expect:
        BinaryProtocol.tryDecodeRequest(buf) == null
    }

    def "tryDecodeResponse returns null when buffer is too small"() {
        given:
        def buf = ByteBuffer.allocate(2)
        buf.put((byte) 0x00)
        buf.put((byte) 0x01)
        buf.flip()

        expect:
        BinaryProtocol.tryDecodeResponse(buf) == null
    }
}
