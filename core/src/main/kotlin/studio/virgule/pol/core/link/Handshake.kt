package studio.virgule.pol.core.link

import studio.virgule.pol.core.crypto.AesGcm
import studio.virgule.pol.core.crypto.Hash
import studio.virgule.pol.core.crypto.Hkdf
import studio.virgule.pol.core.crypto.P256

/** Constants and derivations shared by both ends. See protocol/PROTOCOL.md §3. */
object Handshake {
    /** The only version this build speaks. A peer offering anything else is refused (no silent downgrade). */
    const val VERSION = 1
    const val NONCE_SIZE = 16
    const val DEVICE_ID_SIZE = 16

    val LABEL_COMMIT = "POL1-commit".toByteArray()
    val LABEL_PAIR = "POL1-pair".toByteArray()
    val LABEL_RESUME = "POL1-resume".toByteArray()
    val LABEL_SAS = "POL1-sas".toByteArray()
    val LABEL_KEYS = "POL1 keys".toByteArray()
    val LABEL_SIG_GATEWAY = "POL1-sig-gateway".toByteArray()
    val LABEL_SIG_CLIENT = "POL1-sig-client".toByteArray()

    /** Stable device id: first 16 bytes of SHA-256 of the identity public key. */
    fun deviceId(identityPublicKey: ByteArray): ByteArray = Hash.sha256(identityPublicKey).copyOfRange(0, DEVICE_ID_SIZE)

    fun commitment(ephemeral: ByteArray, identity: ByteArray, nonce: ByteArray, name: ByteArray): ByteArray =
        Hash.sha256(LABEL_COMMIT, ephemeral, identity, nonce, name)

    /**
     * Six-digit short authentication string shown on both phones during pairing. Because the client commits to
     * its keys before seeing the gateway's, an active attacker gets a single 1-in-10^6 guess per attempt, and the
     * gateway allows only a few attempts per pairing window.
     */
    fun sas(transcript: ByteArray): String {
        val h = Hash.sha256(LABEL_SAS, transcript)
        val v = ((h[0].toLong() and 0xFF) shl 24) or ((h[1].toLong() and 0xFF) shl 16) or
            ((h[2].toLong() and 0xFF) shl 8) or (h[3].toLong() and 0xFF)
        return "%06d".format(v % 1_000_000)
    }

    class Keys(val clientToGateway: ByteArray, val gatewayToClient: ByteArray)

    fun keys(sharedSecret: ByteArray, transcript: ByteArray): Keys {
        val okm = Hkdf.derive(sharedSecret, transcript, LABEL_KEYS, 64)
        return Keys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
    }

    fun signedData(label: ByteArray, transcript: ByteArray) = label + transcript

    fun encodeName(name: String): ByteArray {
        val clean = name.filter { !it.isISOControl() }.trim()
        var bytes = clean.toByteArray(Charsets.UTF_8)
        var s = clean
        while (bytes.size > Limits.MAX_NAME_BYTES) { s = s.dropLast(1); bytes = s.toByteArray(Charsets.UTF_8) }
        return bytes
    }

    fun decodeName(bytes: ByteArray): String =
        String(bytes, Charsets.UTF_8).filter { !it.isISOControl() }.trim().ifEmpty { "Unnamed device" }

    internal fun checkKey(bytes: ByteArray) { P256.decode(bytes) }
}

/** Sealed-record channels (first plaintext byte). */
object Channel {
    const val MESSAGE: Byte = 0x00
    const val AUDIO: Byte = 0x01
    const val PAIR_CONFIRM: Byte = 0xFE.toByte()
    const val FINISH: Byte = 0xFF.toByte()
}

/**
 * AES-256-GCM record protection with one key and one strictly increasing counter per direction. The counter is the
 * nonce, so a record can never be replayed, dropped, reordered or duplicated without the session closing.
 */
class SecureChannel(private val sendKey: ByteArray, private val receiveKey: ByteArray) {
    private var sendCounter = 0L
    private var receiveCounter = 0L

    val exhausted get() = sendCounter >= MAX_RECORDS || receiveCounter >= MAX_RECORDS

    fun seal(channel: Byte, payload: ByteArray): ByteArray {
        if (payload.size + 1 > Limits.MAX_PLAINTEXT) throw IllegalArgumentException("payload too large")
        if (sendCounter >= MAX_RECORDS) throw ProtocolException(CloseReason.NORMAL, "rekey required")
        val ctr = sendCounter++
        val ct = AesGcm.seal(sendKey, nonce(ctr), aad(ctr), byteArrayOf(channel) + payload)
        return encodeRecord(RecordType.SEALED, u64(ctr) + ct)
    }

    /** Returns (channel, payload). Throws on any authentication or ordering failure. */
    fun open(body: ByteArray): Pair<Byte, ByteArray> {
        if (body.size < 8 + AesGcm.TAG_SIZE + 1) throw ProtocolException(CloseReason.MALFORMED, "short sealed record")
        val c = Cursor(body)
        val ctr = c.u64()
        if (ctr != receiveCounter) throw ProtocolException(CloseReason.AUTH_FAILED, "unexpected counter")
        val pt = AesGcm.open(receiveKey, nonce(ctr), aad(ctr), body.copyOfRange(8, body.size))
            ?: throw ProtocolException(CloseReason.AUTH_FAILED, "record authentication failed")
        receiveCounter++
        return pt[0] to pt.copyOfRange(1, pt.size)
    }

    private fun nonce(ctr: Long) = ByteArray(4) + u64(ctr)
    private fun aad(ctr: Long) = byteArrayOf(RecordType.SEALED) + u64(ctr)

    companion object {
        /** Sessions are re-established (fresh ephemeral keys) long before GCM nonce or usage limits. */
        const val MAX_RECORDS = 1L shl 31
    }
}
