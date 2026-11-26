package studio.virgule.pol.core.link

import studio.virgule.pol.core.crypto.CryptoException
import studio.virgule.pol.core.crypto.Hash
import studio.virgule.pol.core.crypto.IdentityKey
import studio.virgule.pol.core.crypto.P256
import studio.virgule.pol.core.crypto.Random
import studio.virgule.pol.core.crypto.constantTimeEquals
import studio.virgule.pol.core.crypto.fingerprint
import java.security.KeyPair

/** A device this one has paired with. Only the public identity key is stored; it is not secret. */
data class TrustedPeer(val id: ByteArray, val publicKey: ByteArray, val name: String, val pairedAtMs: Long) {
    val idHex: String get() = id.joinToString("") { "%02x".format(it) }
    val fingerprint: String get() = fingerprint(publicKey)
    override fun equals(other: Any?) = other is TrustedPeer && other.id.contentEquals(id)
    override fun hashCode() = id.contentHashCode()
}

/** Byte sink for one transport connection (one BLE link). Implementations must not block. */
interface LinkTransport {
    fun write(bytes: ByteArray)
    fun close()
}

/** The gateway's list of paired clients. Implementations persist it in Keystore-protected storage. */
interface TrustStore {
    fun find(id: ByteArray): TrustedPeer?
    fun add(peer: TrustedPeer)
    fun count(): Int
}

/**
 * The gateway's pairing window: opened only by the person holding the gateway phone, short-lived, one attempt in
 * flight at a time, and closed for good after [maxFailures] mismatches or declines.
 */
class PairingWindow(private val now: () -> Long, private val durationMs: Long = 3 * 60_000L, private val maxFailures: Int = 3) {
    private var openUntil = 0L
    private var failures = 0
    private var busy = false

    fun open() { openUntil = now() + durationMs; failures = 0; busy = false }
    fun close() { openUntil = 0L; busy = false }
    val isOpen get() = now() < openUntil && failures < maxFailures
    val remainingMs get() = if (isOpen) openUntil - now() else 0L

    internal fun tryBegin(): CloseReason? = when {
        failures >= maxFailures -> CloseReason.TOO_MANY_ATTEMPTS
        !isOpen -> CloseReason.PAIRING_CLOSED
        busy -> CloseReason.BUSY
        else -> { busy = true; null }
    }
    internal fun fail() { busy = false; failures++; if (failures >= maxFailures) openUntil = 0L }
    /** One successful pairing closes the window. */
    internal fun succeed() { busy = false; openUntil = 0L }
}

abstract class LinkEndpoint(protected val transport: LinkTransport, protected val now: () -> Long) {
    private val reader = RecordReader()
    protected var channel: SecureChannel? = null
    var closed = false
        private set
    /** Absolute time after which [tick] closes an unfinished handshake. */
    var deadlineMs: Long = Long.MAX_VALUE
        protected set

    protected abstract fun onRecord(r: Record)
    protected abstract fun notifyClosed(reason: CloseReason, byPeer: Boolean)

    /** Feeds raw transport bytes. Never throws; protocol violations close the link. */
    fun onBytes(bytes: ByteArray) {
        if (closed) return
        try {
            for (r in reader.feed(bytes)) {
                if (closed) return
                if (r.type == RecordType.REJECT) {
                    val code = if (r.body.size == 1) r.body[0].toInt() and 0xFF else CloseReason.MALFORMED.code
                    finish(CloseReason.of(code), byPeer = true, sendReject = false)
                    return
                }
                onRecord(r)
            }
        } catch (e: ProtocolException) {
            finish(e.reason, byPeer = false, sendReject = e.reason != CloseReason.MALFORMED && e.reason != CloseReason.AUTH_FAILED)
        } catch (e: CryptoException) {
            finish(CloseReason.AUTH_FAILED, byPeer = false, sendReject = false)
        } catch (e: RuntimeException) {
            finish(CloseReason.MALFORMED, byPeer = false, sendReject = false)
        }
    }

    fun tick() {
        if (!closed && now() >= deadlineMs) finish(CloseReason.TIMEOUT, byPeer = false, sendReject = true)
    }

    /** The transport went away (BLE disconnect, Bluetooth off). */
    fun onTransportClosed() { if (!closed) finish(CloseReason.TRANSPORT, byPeer = true, sendReject = false) }

    fun close(reason: CloseReason = CloseReason.NORMAL) { if (!closed) finish(reason, byPeer = false, sendReject = true) }

    protected fun finish(reason: CloseReason, byPeer: Boolean, sendReject: Boolean) {
        if (closed) return
        closed = true
        if (sendReject) runCatching { transport.write(encodeRecord(RecordType.REJECT, byteArrayOf(reason.code.toByte()))) }
        channel = null
        runCatching { transport.close() }
        notifyClosed(reason, byPeer)
    }

    protected fun fail(reason: CloseReason, msg: String): Nothing = throw ProtocolException(reason, msg)

    protected fun sendSealed(ch: Byte, payload: ByteArray) {
        val c = channel ?: fail(CloseReason.MALFORMED, "no channel")
        transport.write(c.seal(ch, payload))
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Client (iPhone / Android client side, BLE central)
// ---------------------------------------------------------------------------------------------------------------

class ClientLink private constructor(
    private val identity: IdentityKey,
    transport: LinkTransport,
    now: () -> Long,
    private val listener: Listener,
    private val clientName: String,
    private val gateway: TrustedPeer?,
) : LinkEndpoint(transport, now) {

    interface Listener {
        /** Pairing only: show [sas] and ask the user to compare it with the gateway's screen. */
        fun onSasReady(sas: String, gatewayName: String, gatewayFingerprint: String) {}
        /** Pairing finished on both phones: persist [gateway]. */
        fun onPaired(gateway: TrustedPeer) {}
        fun onEstablished() {}
        fun onMessage(json: String) {}
        /** Call audio frame (see [studio.virgule.pol.core.audio.AudioFrame]). */
        fun onAudio(frame: ByteArray) {}
        fun onClosed(reason: CloseReason, byPeer: Boolean) {}
    }

    private enum class State { IDLE, AWAIT_REVEAL, AWAIT_CONFIRMS, AWAIT_HELLO_ACK, AWAIT_FINISH, ESTABLISHED }

    private var state = State.IDLE
    private val ephemeral: KeyPair = P256.generateKeyPair()
    private val ePub = P256.encode(ephemeral.public)
    private val nonce = Random.bytes(Handshake.NONCE_SIZE)
    private val nameBytes = Handshake.encodeName(clientName)
    private var commitBody = ByteArray(0)
    private var revealGBody = ByteArray(0)
    private var transcript = ByteArray(0)
    private var pendingGateway: TrustedPeer? = null
    private var localConfirmed = false
    private var peerConfirmed = false

    val isEstablished get() = state == State.ESTABLISHED && !closed

    fun start() {
        check(state == State.IDLE)
        if (gateway == null) {
            commitBody = byteArrayOf(Handshake.VERSION.toByte()) +
                Handshake.commitment(ePub, identity.publicKey, nonce, nameBytes)
            transport.write(encodeRecord(RecordType.PAIR_COMMIT, commitBody))
            state = State.AWAIT_REVEAL
            deadlineMs = now() + HANDSHAKE_TIMEOUT_MS
        } else {
            val hello = byteArrayOf(Handshake.VERSION.toByte()) + ePub + nonce + Handshake.deviceId(identity.publicKey)
            commitBody = hello
            transport.write(encodeRecord(RecordType.HELLO, hello))
            state = State.AWAIT_HELLO_ACK
            deadlineMs = now() + HANDSHAKE_TIMEOUT_MS
        }
    }

    /** The user confirmed that both phones show the same code. */
    fun confirmSas() {
        if (closed || state != State.AWAIT_CONFIRMS || localConfirmed) return
        localConfirmed = true
        sendSealed(Channel.PAIR_CONFIRM, identity.sign(Handshake.signedData(Handshake.LABEL_SIG_CLIENT, transcript)))
        maybeFinishPairing()
    }

    /** The user said the codes differ, or cancelled. */
    fun declineSas() = close(CloseReason.DECLINED)

    fun send(json: String) {
        check(isEstablished) { "not connected" }
        sendSealed(Channel.MESSAGE, json.toByteArray(Charsets.UTF_8))
    }

    /** Sends one encoded call-audio frame. Never call before [isEstablished]. */
    fun sendAudio(frame: ByteArray) {
        check(isEstablished) { "not connected" }
        sendSealed(Channel.AUDIO, frame)
    }

    override fun onRecord(r: Record) {
        when (state) {
            State.AWAIT_REVEAL -> {
                if (r.type != RecordType.PAIR_REVEAL_GATEWAY) fail(CloseReason.MALFORMED, "expected reveal")
                val c = Cursor(r.body)
                if (c.u8() != Handshake.VERSION) fail(CloseReason.BAD_VERSION, "version")
                val eG = c.bytes(P256.PUBLIC_KEY_SIZE)
                val sG = c.bytes(P256.PUBLIC_KEY_SIZE)
                c.bytes(Handshake.NONCE_SIZE)
                val nameLen = c.u8()
                if (nameLen > Limits.MAX_NAME_BYTES) fail(CloseReason.MALFORMED, "name")
                val name = Handshake.decodeName(c.bytes(nameLen))
                c.end()
                Handshake.checkKey(sG)
                revealGBody = r.body
                val revealC = ePub + identity.publicKey + nonce + byteArrayOf(nameBytes.size.toByte()) + nameBytes
                transport.write(encodeRecord(RecordType.PAIR_REVEAL_CLIENT, revealC))
                transcript = Hash.sha256(Handshake.LABEL_PAIR, commitBody, revealGBody, revealC)
                val keys = Handshake.keys(P256.ecdh(ephemeral.private, P256.decode(eG)), transcript)
                channel = SecureChannel(keys.clientToGateway, keys.gatewayToClient)
                pendingGateway = TrustedPeer(Handshake.deviceId(sG), sG, name, now())
                state = State.AWAIT_CONFIRMS
                deadlineMs = now() + CONFIRM_TIMEOUT_MS
                listener.onSasReady(Handshake.sas(transcript), name, fingerprint(sG))
            }
            State.AWAIT_CONFIRMS -> {
                if (r.type != RecordType.SEALED) fail(CloseReason.MALFORMED, "expected confirm")
                val (ch, payload) = channel!!.open(r.body)
                if (ch != Channel.PAIR_CONFIRM || peerConfirmed) fail(CloseReason.MALFORMED, "unexpected record")
                val g = pendingGateway!!
                if (!P256.verify(g.publicKey, Handshake.signedData(Handshake.LABEL_SIG_GATEWAY, transcript), payload)) {
                    fail(CloseReason.AUTH_FAILED, "gateway signature")
                }
                peerConfirmed = true
                maybeFinishPairing()
            }
            State.AWAIT_HELLO_ACK -> {
                if (r.type != RecordType.HELLO_ACK) fail(CloseReason.MALFORMED, "expected hello ack")
                val c = Cursor(r.body)
                if (c.u8() != Handshake.VERSION) fail(CloseReason.BAD_VERSION, "version")
                val eG = c.bytes(P256.PUBLIC_KEY_SIZE)
                val nG = c.bytes(Handshake.NONCE_SIZE)
                val sigLen = c.u8()
                val sig = c.bytes(sigLen)
                c.end()
                transcript = Hash.sha256(Handshake.LABEL_RESUME, commitBody, byteArrayOf(Handshake.VERSION.toByte()), eG, nG)
                if (!P256.verify(gateway!!.publicKey, Handshake.signedData(Handshake.LABEL_SIG_GATEWAY, transcript), sig)) {
                    fail(CloseReason.AUTH_FAILED, "gateway signature")
                }
                val keys = Handshake.keys(P256.ecdh(ephemeral.private, P256.decode(eG)), transcript)
                channel = SecureChannel(keys.clientToGateway, keys.gatewayToClient)
                sendSealed(Channel.FINISH, identity.sign(Handshake.signedData(Handshake.LABEL_SIG_CLIENT, transcript)))
                state = State.AWAIT_FINISH
            }
            State.AWAIT_FINISH -> {
                if (r.type != RecordType.SEALED) fail(CloseReason.MALFORMED, "expected finish")
                val (ch, payload) = channel!!.open(r.body)
                if (ch != Channel.FINISH || payload.isNotEmpty()) fail(CloseReason.MALFORMED, "bad finish")
                state = State.ESTABLISHED
                deadlineMs = Long.MAX_VALUE
                listener.onEstablished()
            }
            State.ESTABLISHED -> {
                if (r.type != RecordType.SEALED) fail(CloseReason.MALFORMED, "plaintext after handshake")
                val (ch, payload) = channel!!.open(r.body)
                when (ch) {
                    Channel.MESSAGE -> listener.onMessage(String(payload, Charsets.UTF_8))
                    Channel.AUDIO -> listener.onAudio(payload)
                    else -> fail(CloseReason.MALFORMED, "unexpected channel")
                }
            }
            State.IDLE -> fail(CloseReason.MALFORMED, "not started")
        }
    }

    private fun maybeFinishPairing() {
        if (localConfirmed && peerConfirmed && state == State.AWAIT_CONFIRMS) {
            state = State.ESTABLISHED
            deadlineMs = Long.MAX_VALUE
            listener.onPaired(pendingGateway!!)
            listener.onEstablished()
        }
    }

    override fun notifyClosed(reason: CloseReason, byPeer: Boolean) = listener.onClosed(reason, byPeer)

    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 15_000L
        const val CONFIRM_TIMEOUT_MS = 120_000L

        fun pair(identity: IdentityKey, transport: LinkTransport, clientName: String, listener: Listener, now: () -> Long = System::currentTimeMillis) =
            ClientLink(identity, transport, now, listener, clientName, null)

        fun resume(identity: IdentityKey, transport: LinkTransport, gateway: TrustedPeer, listener: Listener, now: () -> Long = System::currentTimeMillis) =
            ClientLink(identity, transport, now, listener, "", gateway)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Gateway (Android phone holding the SIMs, BLE peripheral)
// ---------------------------------------------------------------------------------------------------------------

class GatewayLink(
    private val identity: IdentityKey,
    transport: LinkTransport,
    private val gatewayName: String,
    private val trust: TrustStore,
    private val pairing: PairingWindow,
    private val listener: Listener,
    now: () -> Long = System::currentTimeMillis,
    private val maxPairedClients: Int = 4,
) : LinkEndpoint(transport, now) {

    interface Listener {
        /** Show [sas] with the client's name and ask the gateway's owner to confirm. */
        fun onPairingRequest(sas: String, clientName: String, clientFingerprint: String) {}
        fun onPaired(client: TrustedPeer) {}
        fun onEstablished(client: TrustedPeer) {}
        fun onMessage(client: TrustedPeer, json: String) {}
        fun onAudio(client: TrustedPeer, frame: ByteArray) {}
        fun onClosed(client: TrustedPeer?, reason: CloseReason, byPeer: Boolean) {}
    }

    private enum class State { AWAIT_FIRST, AWAIT_REVEAL, AWAIT_CONFIRMS, AWAIT_FINISH, ESTABLISHED }

    private var state = State.AWAIT_FIRST
    private val ephemeral: KeyPair = P256.generateKeyPair()
    private val ePub = P256.encode(ephemeral.public)
    private val nonce = Random.bytes(Handshake.NONCE_SIZE)
    private var commitBody = ByteArray(0)
    private var revealGBody = ByteArray(0)
    private var transcript = ByteArray(0)
    private var pendingClient: TrustedPeer? = null
    private var pairingActive = false
    private var localConfirmed = false
    private var peerConfirmed = false

    var client: TrustedPeer? = null
        private set
    val isEstablished get() = state == State.ESTABLISHED && !closed

    init { deadlineMs = now() + ClientLink.HANDSHAKE_TIMEOUT_MS }

    fun confirmSas() {
        if (closed || state != State.AWAIT_CONFIRMS || localConfirmed) return
        localConfirmed = true
        sendSealed(Channel.PAIR_CONFIRM, identity.sign(Handshake.signedData(Handshake.LABEL_SIG_GATEWAY, transcript)))
        maybeFinishPairing()
    }

    fun declineSas() = close(CloseReason.DECLINED)

    fun send(json: String) {
        check(isEstablished) { "not connected" }
        sendSealed(Channel.MESSAGE, json.toByteArray(Charsets.UTF_8))
    }

    fun sendAudio(frame: ByteArray) {
        check(isEstablished) { "not connected" }
        sendSealed(Channel.AUDIO, frame)
    }

    override fun onRecord(r: Record) {
        when (state) {
            State.AWAIT_FIRST -> when (r.type) {
                RecordType.PAIR_COMMIT -> {
                    val c = Cursor(r.body)
                    if (c.u8() != Handshake.VERSION) fail(CloseReason.BAD_VERSION, "version")
                    c.bytes(32); c.end()
                    if (trust.count() >= maxPairedClients) fail(CloseReason.BUSY, "too many paired devices")
                    pairing.tryBegin()?.let { fail(it, "pairing not possible") }
                    pairingActive = true
                    commitBody = r.body
                    val name = Handshake.encodeName(gatewayName)
                    revealGBody = byteArrayOf(Handshake.VERSION.toByte()) + ePub + identity.publicKey + nonce +
                        byteArrayOf(name.size.toByte()) + name
                    transport.write(encodeRecord(RecordType.PAIR_REVEAL_GATEWAY, revealGBody))
                    state = State.AWAIT_REVEAL
                }
                RecordType.HELLO -> {
                    val c = Cursor(r.body)
                    if (c.u8() != Handshake.VERSION) fail(CloseReason.BAD_VERSION, "version")
                    val eC = c.bytes(P256.PUBLIC_KEY_SIZE)
                    c.bytes(Handshake.NONCE_SIZE)
                    val id = c.bytes(Handshake.DEVICE_ID_SIZE)
                    c.end()
                    val peer = trust.find(id) ?: fail(CloseReason.UNKNOWN_DEVICE, "unknown client")
                    transcript = Hash.sha256(Handshake.LABEL_RESUME, r.body, byteArrayOf(Handshake.VERSION.toByte()), ePub, nonce)
                    val keys = Handshake.keys(P256.ecdh(ephemeral.private, P256.decode(eC)), transcript)
                    val sig = identity.sign(Handshake.signedData(Handshake.LABEL_SIG_GATEWAY, transcript))
                    transport.write(encodeRecord(RecordType.HELLO_ACK,
                        byteArrayOf(Handshake.VERSION.toByte()) + ePub + nonce + byteArrayOf(sig.size.toByte()) + sig))
                    channel = SecureChannel(keys.gatewayToClient, keys.clientToGateway)
                    pendingClient = peer
                    state = State.AWAIT_FINISH
                }
                else -> fail(CloseReason.MALFORMED, "unexpected first record")
            }
            State.AWAIT_REVEAL -> {
                if (r.type != RecordType.PAIR_REVEAL_CLIENT) fail(CloseReason.MALFORMED, "expected reveal")
                val c = Cursor(r.body)
                val eC = c.bytes(P256.PUBLIC_KEY_SIZE)
                val sC = c.bytes(P256.PUBLIC_KEY_SIZE)
                val nC = c.bytes(Handshake.NONCE_SIZE)
                val nameLen = c.u8()
                if (nameLen > Limits.MAX_NAME_BYTES) fail(CloseReason.MALFORMED, "name")
                val nameRaw = c.bytes(nameLen)
                c.end()
                val expected = Handshake.commitment(eC, sC, nC, nameRaw)
                if (!constantTimeEquals(expected, commitBody.copyOfRange(1, 33))) fail(CloseReason.AUTH_FAILED, "commitment mismatch")
                Handshake.checkKey(sC)
                transcript = Hash.sha256(Handshake.LABEL_PAIR, commitBody, revealGBody, r.body)
                val keys = Handshake.keys(P256.ecdh(ephemeral.private, P256.decode(eC)), transcript)
                channel = SecureChannel(keys.gatewayToClient, keys.clientToGateway)
                val name = Handshake.decodeName(nameRaw)
                pendingClient = TrustedPeer(Handshake.deviceId(sC), sC, name, now())
                state = State.AWAIT_CONFIRMS
                deadlineMs = now() + ClientLink.CONFIRM_TIMEOUT_MS
                listener.onPairingRequest(Handshake.sas(transcript), name, fingerprint(sC))
            }
            State.AWAIT_CONFIRMS -> {
                if (r.type != RecordType.SEALED) fail(CloseReason.MALFORMED, "expected confirm")
                val (ch, payload) = channel!!.open(r.body)
                if (ch != Channel.PAIR_CONFIRM || peerConfirmed) fail(CloseReason.MALFORMED, "unexpected record")
                if (!P256.verify(pendingClient!!.publicKey, Handshake.signedData(Handshake.LABEL_SIG_CLIENT, transcript), payload)) {
                    fail(CloseReason.AUTH_FAILED, "client signature")
                }
                peerConfirmed = true
                maybeFinishPairing()
            }
            State.AWAIT_FINISH -> {
                if (r.type != RecordType.SEALED) fail(CloseReason.MALFORMED, "expected finish")
                val (ch, payload) = channel!!.open(r.body)
                val peer = pendingClient!!
                if (ch != Channel.FINISH) fail(CloseReason.MALFORMED, "expected finish")
                if (!P256.verify(peer.publicKey, Handshake.signedData(Handshake.LABEL_SIG_CLIENT, transcript), payload)) {
                    fail(CloseReason.AUTH_FAILED, "client signature")
                }
                // Re-check: the device may have been revoked while the handshake ran.
                if (trust.find(peer.id) == null) fail(CloseReason.UNKNOWN_DEVICE, "revoked")
                sendSealed(Channel.FINISH, ByteArray(0))
                client = peer
                state = State.ESTABLISHED
                deadlineMs = Long.MAX_VALUE
                listener.onEstablished(peer)
            }
            State.ESTABLISHED -> {
                if (r.type != RecordType.SEALED) fail(CloseReason.MALFORMED, "plaintext after handshake")
                val (ch, payload) = channel!!.open(r.body)
                when (ch) {
                    Channel.MESSAGE -> listener.onMessage(client!!, String(payload, Charsets.UTF_8))
                    Channel.AUDIO -> listener.onAudio(client!!, payload)
                    else -> fail(CloseReason.MALFORMED, "unexpected channel")
                }
            }
        }
    }

    private fun maybeFinishPairing() {
        if (localConfirmed && peerConfirmed && state == State.AWAIT_CONFIRMS) {
            val peer = pendingClient!!
            trust.add(peer)
            pairing.succeed()
            pairingActive = false
            client = peer
            state = State.ESTABLISHED
            deadlineMs = Long.MAX_VALUE
            listener.onPaired(peer)
            listener.onEstablished(peer)
        }
    }

    override fun notifyClosed(reason: CloseReason, byPeer: Boolean) {
        if (pairingActive) {
            pairingActive = false
            // Every attempt that got past the commitment counts, a dropped link included: a man in the middle knows
            // both codes before anyone confirms and could otherwise abort mismatching attempts for free and retry.
            pairing.fail()
        }
        listener.onClosed(client ?: pendingClient, reason, byPeer)
    }
}
