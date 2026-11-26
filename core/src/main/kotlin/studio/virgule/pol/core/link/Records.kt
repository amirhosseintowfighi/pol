package studio.virgule.pol.core.link

import java.nio.ByteBuffer

/**
 * Record layer: `len: u16 | type: u8 | body`, `len` counting type + body. The byte stream may be cut anywhere by
 * the transport (BLE GATT writes / notifications); [RecordReader] reassembles it with a hard size bound.
 */
object RecordType {
    const val PAIR_COMMIT: Byte = 0x01
    const val PAIR_REVEAL_GATEWAY: Byte = 0x02
    const val PAIR_REVEAL_CLIENT: Byte = 0x03
    const val HELLO: Byte = 0x04
    const val HELLO_ACK: Byte = 0x05
    const val REJECT: Byte = 0x06
    const val SEALED: Byte = 0x10

    val known = setOf(PAIR_COMMIT, PAIR_REVEAL_GATEWAY, PAIR_REVEAL_CLIENT, HELLO, HELLO_ACK, REJECT, SEALED)
}

object Limits {
    /** Largest record body accepted on the wire. */
    const val MAX_BODY = 8192
    /** Largest sealed plaintext (channel byte + payload). */
    const val MAX_PLAINTEXT = MAX_BODY - 8 - 16
    /** Largest JSON control message. Senders page lists to stay below it. */
    const val MAX_MESSAGE = MAX_PLAINTEXT - 1
    const val MAX_NAME_BYTES = 64
}

class Record(val type: Byte, val body: ByteArray)

class ProtocolException(val reason: CloseReason, msg: String) : Exception(msg)

fun encodeRecord(type: Byte, body: ByteArray): ByteArray {
    require(body.size <= Limits.MAX_BODY) { "record too large" }
    val len = body.size + 1
    val out = ByteArray(2 + len)
    out[0] = (len ushr 8).toByte()
    out[1] = len.toByte()
    out[2] = type
    System.arraycopy(body, 0, out, 3, body.size)
    return out
}

class RecordReader {
    private val buf = ByteBuffer.allocate(2 + 1 + Limits.MAX_BODY + 4096)

    /** Feeds transport bytes; returns complete records. Throws [ProtocolException] on malformed input. */
    fun feed(bytes: ByteArray): List<Record> {
        if (bytes.size > buf.remaining()) throw ProtocolException(CloseReason.MALFORMED, "input overflow")
        buf.put(bytes)
        val out = ArrayList<Record>()
        buf.flip()
        while (buf.remaining() >= 3) {
            buf.mark()
            val len = ((buf.get().toInt() and 0xFF) shl 8) or (buf.get().toInt() and 0xFF)
            if (len < 1 || len - 1 > Limits.MAX_BODY) throw ProtocolException(CloseReason.MALFORMED, "bad record length $len")
            if (buf.remaining() < len) { buf.reset(); break }
            val type = buf.get()
            if (type !in RecordType.known) throw ProtocolException(CloseReason.MALFORMED, "unknown record type")
            val body = ByteArray(len - 1)
            buf.get(body)
            out += Record(type, body)
        }
        buf.compact()
        return out
    }

    val hasPartialRecord: Boolean get() = buf.position() > 0
}

enum class CloseReason(val code: Int) {
    NORMAL(0),
    BAD_VERSION(1),
    UNKNOWN_DEVICE(2),
    PAIRING_CLOSED(3),
    BUSY(4),
    DECLINED(5),
    AUTH_FAILED(6),
    TOO_MANY_ATTEMPTS(7),
    TIMEOUT(8),
    MALFORMED(9),
    REVOKED(10),
    TRANSPORT(11);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: MALFORMED
    }
}

/** Minimal bounded reader for fixed-layout handshake bodies. */
internal class Cursor(private val b: ByteArray) {
    private var p = 0
    fun u8(): Int = if (p < b.size) b[p++].toInt() and 0xFF else fail()
    fun bytes(n: Int): ByteArray {
        if (n < 0 || p + n > b.size) fail()
        return b.copyOfRange(p, p + n).also { p += n }
    }
    fun u64(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or u8().toLong() }
        return v
    }
    fun end() { if (p != b.size) fail() }
    private fun fail(): Nothing = throw ProtocolException(CloseReason.MALFORMED, "truncated or oversized handshake field")
}

internal fun u64(v: Long) = ByteArray(8) { i -> (v ushr (56 - 8 * i)).toByte() }
