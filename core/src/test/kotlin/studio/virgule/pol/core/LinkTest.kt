package studio.virgule.pol.core

import studio.virgule.pol.core.crypto.IdentityKey
import studio.virgule.pol.core.crypto.SoftwareIdentityKey
import studio.virgule.pol.core.link.ClientLink
import studio.virgule.pol.core.link.CloseReason
import studio.virgule.pol.core.link.GatewayLink
import studio.virgule.pol.core.link.LinkTransport
import studio.virgule.pol.core.link.PairingWindow
import studio.virgule.pol.core.link.RecordType
import studio.virgule.pol.core.link.TrustStore
import studio.virgule.pol.core.link.TrustedPeer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryTrust : TrustStore {
    val peers = mutableListOf<TrustedPeer>()
    override fun find(id: ByteArray) = peers.firstOrNull { it.id.contentEquals(id) }
    override fun add(peer: TrustedPeer) { peers.removeAll { it.id.contentEquals(peer.id) }; peers += peer }
    override fun count() = peers.size
}

/** Two in-memory pipes; bytes are delivered in random-sized chunks like BLE writes/notifications. */
class Pipe(private val rnd: Random = Random(7)) {
    val toGateway = ArrayDeque<ByteArray>()
    val toClient = ArrayDeque<ByteArray>()
    var clientClosed = false
    var gatewayClosed = false
    /** Optional man-in-the-middle hook: may modify or drop bytes going to the gateway. */
    var tapToGateway: ((ByteArray) -> ByteArray?)? = null

    val clientSide = object : LinkTransport {
        override fun write(bytes: ByteArray) { chunk(bytes).forEach { c -> (tapToGateway?.invoke(c) ?: if (tapToGateway == null) c else null)?.let(toGateway::addLast) } }
        override fun close() { clientClosed = true }
    }
    val gatewaySide = object : LinkTransport {
        override fun write(bytes: ByteArray) { chunk(bytes).forEach(toClient::addLast) }
        override fun close() { gatewayClosed = true }
    }

    private fun chunk(b: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var i = 0
        while (i < b.size) { val n = minOf(b.size - i, 1 + rnd.nextInt(180)); out += b.copyOfRange(i, i + n); i += n }
        return out
    }

    fun pump(client: ClientLink, gateway: GatewayLink) {
        var guard = 0
        while ((toGateway.isNotEmpty() || toClient.isNotEmpty()) && guard++ < 10_000) {
            toGateway.removeFirstOrNull()?.let(gateway::onBytes)
            toClient.removeFirstOrNull()?.let(client::onBytes)
        }
    }
}

class ClientEvents : ClientLink.Listener {
    var sas: String? = null
    var paired: TrustedPeer? = null
    var established = false
    val messages = mutableListOf<String>()
    var closed: CloseReason? = null
    override fun onSasReady(sas: String, gatewayName: String, gatewayFingerprint: String) { this.sas = sas }
    override fun onPaired(gateway: TrustedPeer) { paired = gateway }
    override fun onEstablished() { established = true }
    override fun onMessage(json: String) { messages += json }
    override fun onClosed(reason: CloseReason, byPeer: Boolean) { closed = reason }
}

class GatewayEvents : GatewayLink.Listener {
    var sas: String? = null
    var clientName: String? = null
    var established: TrustedPeer? = null
    val messages = mutableListOf<String>()
    var closed: CloseReason? = null
    override fun onPairingRequest(sas: String, clientName: String, clientFingerprint: String) { this.sas = sas; this.clientName = clientName }
    override fun onEstablished(client: TrustedPeer) { established = client }
    override fun onMessage(client: TrustedPeer, json: String) { messages += json }
    override fun onClosed(client: TrustedPeer?, reason: CloseReason, byPeer: Boolean) { closed = reason }
}

class LinkTest {
    private var clock = 1_000_000L
    private val now = { clock }
    private val gwId: IdentityKey = SoftwareIdentityKey()
    private val clId: IdentityKey = SoftwareIdentityKey()

    private fun pairOnce(trust: MemoryTrust, window: PairingWindow): TrustedPeer {
        val pipe = Pipe()
        val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, ge, now)
        val cl = ClientLink.pair(clId, pipe.clientSide, "My iPhone", ce, now)
        cl.start(); pipe.pump(cl, gw)
        assertNotNull(ce.sas); assertEquals(ce.sas, ge.sas, "both phones must show the same code")
        assertEquals("My iPhone", ge.clientName)
        cl.confirmSas(); pipe.pump(cl, gw)
        assertFalse(ce.established, "client alone must not complete pairing")
        assertEquals(0, trust.count())
        gw.confirmSas(); pipe.pump(cl, gw)
        assertTrue(ce.established); assertNotNull(ge.established)
        assertEquals(1, trust.count())
        cl.send("""{"t":"ping","b":{"ts":1}}"""); gw.send("""{"t":"pong","b":{"ts":1}}"""); pipe.pump(cl, gw)
        assertEquals(1, ge.messages.size); assertEquals(1, ce.messages.size)
        assertFalse(window.isOpen, "a successful pairing closes the window")
        return ce.paired!!
    }

    @Test fun pairThenResume() {
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        val gatewayPeer = pairOnce(trust, window)
        assertTrue(gatewayPeer.publicKey.contentEquals(gwId.publicKey))

        val pipe = Pipe(Random(99))
        val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, ge, now)
        val cl = ClientLink.resume(clId, pipe.clientSide, gatewayPeer, ce, now)
        cl.start(); pipe.pump(cl, gw)
        assertTrue(ce.established); assertNotNull(ge.established)
        repeat(50) { i -> cl.send("""{"t":"x","id":$i}""") }
        pipe.pump(cl, gw)
        assertEquals(50, ge.messages.size)
    }

    @Test fun unknownClientIsRejected() {
        val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", MemoryTrust(), PairingWindow(now), ge, now)
        val fakeGateway = TrustedPeer(ByteArray(16), gwId.publicKey, "Pixel", 0)
        val cl = ClientLink.resume(clId, pipe.clientSide, fakeGateway, ce, now)
        cl.start(); pipe.pump(cl, gw)
        assertEquals(CloseReason.UNKNOWN_DEVICE, ce.closed)
        assertFalse(ce.established)
    }

    @Test fun impostorGatewayFailsResume() {
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        val realGateway = pairOnce(trust, window)
        // An attacker that knows the client's id (it is sent in clear) but not the gateway's private key.
        val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
        val impostor = GatewayLink(SoftwareIdentityKey(), pipe.gatewaySide, "Pixel", trust, window, ge, now)
        val cl = ClientLink.resume(clId, pipe.clientSide, realGateway, ce, now)
        cl.start(); pipe.pump(cl, impostor)
        assertEquals(CloseReason.AUTH_FAILED, ce.closed)
        assertFalse(ce.established)
    }

    @Test fun impostorClientFailsResume() {
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        val gatewayPeer = pairOnce(trust, window)
        // Attacker replays the paired client's id with its own key: it cannot sign the transcript.
        val attackerKey = object : IdentityKey {
            val other = SoftwareIdentityKey()
            override val publicKey = clId.publicKey
            override fun sign(data: ByteArray) = other.sign(data)
        }
        val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, ge, now)
        val cl = ClientLink.resume(attackerKey, pipe.clientSide, gatewayPeer, ce, now)
        cl.start(); pipe.pump(cl, gw)
        assertNull(ge.established)
        assertEquals(CloseReason.AUTH_FAILED, ge.closed)
    }

    @Test fun revokedClientCannotResume() {
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        val gatewayPeer = pairOnce(trust, window)
        trust.peers.clear() // owner removed the device on the gateway
        val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, ge, now)
        val cl = ClientLink.resume(clId, pipe.clientSide, gatewayPeer, ce, now)
        cl.start(); pipe.pump(cl, gw)
        assertEquals(CloseReason.UNKNOWN_DEVICE, ce.closed)
    }

    @Test fun pairingRequiresOpenWindow() {
        val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", MemoryTrust(), PairingWindow(now), ge, now)
        val cl = ClientLink.pair(clId, pipe.clientSide, "Phone", ce, now)
        cl.start(); pipe.pump(cl, gw)
        assertEquals(CloseReason.PAIRING_CLOSED, ce.closed)
        assertNull(ce.sas)
    }

    @Test fun declinedCodeFailsAndCountsAgainstWindow() {
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        repeat(3) {
            val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
            val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, ge, now)
            val cl = ClientLink.pair(clId, pipe.clientSide, "Phone", ce, now)
            cl.start(); pipe.pump(cl, gw)
            assertNotNull(ge.sas)
            // A man in the middle aborting after seeing mismatching codes: the link just drops.
            if (it % 2 == 0) { gw.onTransportClosed(); cl.onTransportClosed() } else { gw.declineSas(); pipe.pump(cl, gw) }
            assertFalse(ce.established)
        }
        assertEquals(0, trust.count())
        assertFalse(window.isOpen, "three failed attempts close the window")
        val pipe = Pipe(); val ce = ClientEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, GatewayEvents(), now)
        val cl = ClientLink.pair(clId, pipe.clientSide, "Phone", ce, now)
        cl.start(); pipe.pump(cl, gw)
        assertEquals(CloseReason.TOO_MANY_ATTEMPTS, ce.closed)
    }

    @Test fun pairingWindowExpires() {
        val window = PairingWindow(now, durationMs = 1000).also { it.open() }
        assertTrue(window.isOpen)
        clock += 1001
        assertFalse(window.isOpen)
    }

    @Test fun tamperedRevealBreaksCommitment() {
        val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", MemoryTrust(), PairingWindow(now).also { it.open() }, ge, now)
        val cl = ClientLink.pair(clId, pipe.clientSide, "Phone", ce, now)
        var seen = 0
        // Flip one byte inside the client's reveal (the 2nd record the client sends).
        pipe.tapToGateway = { c -> seen += c.size; if (seen > 40 && seen - c.size < 60) c.also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() } else c }
        cl.start(); pipe.pump(cl, gw)
        assertNull(ge.sas)
        assertEquals(CloseReason.AUTH_FAILED, ge.closed)
    }

    @Test fun tamperedOrReplayedSealedRecordClosesSession() {
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        val gatewayPeer = pairOnce(trust, window)
        for (mode in listOf("flip", "replay")) {
            val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
            val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, ge, now)
            val cl = ClientLink.resume(clId, pipe.clientSide, gatewayPeer, ce, now)
            cl.start(); pipe.pump(cl, gw)
            assertTrue(ce.established)
            val captured = ArrayList<ByteArray>()
            pipe.tapToGateway = { c -> captured += c; c }
            cl.send("""{"t":"a"}"""); pipe.pump(cl, gw)
            assertEquals(1, ge.messages.size)
            val record = captured.reduce { a, b -> a + b }
            if (mode == "flip") record[record.size - 3] = (record[record.size - 3].toInt() xor 0x40).toByte()
            gw.onBytes(record)
            assertEquals(CloseReason.AUTH_FAILED, ge.closed, mode)
            assertEquals(1, ge.messages.size, mode)
        }
    }

    @Test fun wrongVersionIsRefused() {
        val pipe = Pipe(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", MemoryTrust(), PairingWindow(now).also { it.open() }, ge, now)
        gw.onBytes(byteArrayOf(0, 34, RecordType.PAIR_COMMIT, 2) + ByteArray(32))
        assertEquals(CloseReason.BAD_VERSION, ge.closed)
    }

    @Test fun plaintextAfterHandshakeIsRefused() {
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        val gatewayPeer = pairOnce(trust, window)
        val pipe = Pipe(); val ce = ClientEvents(); val ge = GatewayEvents()
        val gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, ge, now)
        val cl = ClientLink.resume(clId, pipe.clientSide, gatewayPeer, ce, now)
        cl.start(); pipe.pump(cl, gw)
        gw.onBytes(byteArrayOf(0, 2, RecordType.HELLO, 1))
        assertEquals(CloseReason.MALFORMED, ge.closed)
    }

    @Test fun handshakeTimesOut() {
        val pipe = Pipe(); val ce = ClientEvents()
        val cl = ClientLink.pair(clId, pipe.clientSide, "Phone", ce, now)
        cl.start()
        clock += ClientLink.HANDSHAKE_TIMEOUT_MS + 1
        cl.tick()
        assertEquals(CloseReason.TIMEOUT, ce.closed)
    }

    @Test fun garbageInputNeverThrows() {
        val r = Random(3)
        repeat(300) {
            val ge = GatewayEvents()
            val gw = GatewayLink(gwId, Pipe().gatewaySide, "Pixel", MemoryTrust(), PairingWindow(now).also { it.open() }, ge, now)
            gw.onBytes(r.nextBytes(r.nextInt(1, 400)))
        }
    }
}
