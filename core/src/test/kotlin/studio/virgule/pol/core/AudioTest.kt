package studio.virgule.pol.core

import studio.virgule.pol.core.audio.AudioFrame
import studio.virgule.pol.core.audio.AudioSpec
import studio.virgule.pol.core.audio.G711
import studio.virgule.pol.core.audio.JitterBuffer
import studio.virgule.pol.core.crypto.SoftwareIdentityKey
import studio.virgule.pol.core.link.ClientLink
import studio.virgule.pol.core.link.GatewayLink
import studio.virgule.pol.core.link.PairingWindow
import studio.virgule.pol.core.link.TrustedPeer
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioTest {
    @Test fun g711AnchorValues() {
        // Values from the standard µ-law tables (ITU-T G.711; Sun's g711.c ulaw2linear).
        assertEquals(0, G711.decode(0xFF.toByte()).toInt())
        assertEquals(0, G711.decode(0x7F.toByte()).toInt())
        assertEquals(-32124, G711.decode(0x00.toByte()).toInt())
        assertEquals(32124, G711.decode(0x80.toByte()).toInt())
        assertEquals(0xFF.toByte(), G711.encode(0))
        assertEquals(0x80.toByte(), G711.encode(Short.MAX_VALUE))
        assertEquals(0x00.toByte(), G711.encode(Short.MIN_VALUE))
    }

    @Test fun g711MatchesIndependentVectors() {
        // protocol/vectors.json › g711, from tools/vectors.py (classic g711.c segment-table formulation).
        val samples = intArrayOf(-32768, -20000, -1000, -100, -1, 0, 1, 100, 1000, 8159, 20000, 32767)
        val ulaw = intArrayOf(0, 12, 78, 114, 127, 255, 255, 242, 206, 159, 140, 128)
        samples.forEachIndexed { i, v -> assertEquals(ulaw[i], G711.encode(v.toShort()).toInt() and 0xFF, "sample $v") }
    }

    @Test fun g711RoundTripErrorIsBounded() {
        for (v in -32000..32000 step 7) {
            val back = G711.decode(G711.encode(v.toShort())).toInt()
            // µ-law step size grows with amplitude: error stays within ~1/16 of the value (+ small floor).
            assertTrue(abs(back - v) <= abs(v) / 16 + 8, "v=$v back=$back")
        }
        val pcm = ShortArray(160) { (it * 200 - 16000).toShort() }
        val le = ByteArray(320).also { b -> pcm.forEachIndexed { i, s -> b[2 * i] = s.toByte(); b[2 * i + 1] = (s.toInt() shr 8).toByte() } }
        assertContentEquals(G711.encode(pcm), G711.encodeLe(le))
        assertEquals(320, G711.decodeLe(G711.encode(pcm)).size)
    }

    @Test fun frameEncodingAndBounds() {
        val f = AudioFrame(AudioSpec.CODEC_PCMU, 65535, ByteArray(160) { it.toByte() })
        val back = AudioFrame.decode(f.encode())!!
        assertEquals(65535, back.seq)
        assertContentEquals(f.payload, back.payload)
        assertNull(AudioFrame.decode(byteArrayOf(9, 0, 0, 1)), "unknown codec")
        assertNull(AudioFrame.decode(byteArrayOf(2, 0, 0)), "empty")
        assertNull(AudioFrame.decode(ByteArray(4 + AudioFrame.MAX_PAYLOAD) { 2 }), "oversized")
    }

    @Test fun jitterBufferBuffersThenBoundsLatency() {
        val j = JitterBuffer(startFrames = 3, maxFrames = 5)
        j.push(byteArrayOf(1)); j.push(byteArrayOf(2))
        assertNull(j.pop(), "still buffering")
        j.push(byteArrayOf(3))
        assertEquals(1, j.pop()!![0].toInt())
        repeat(10) { j.push(byteArrayOf((10 + it).toByte())) }
        assertEquals(5, j.size())
        assertEquals(15, j.pop()!![0].toInt(), "oldest frames dropped")
        repeat(4) { j.pop() }
        assertNull(j.pop()); assertNull(j.pop(), "re-buffers after running dry")
    }

    @Test fun audioTravelsBothWaysOverTheEncryptedLink() {
        var clock = 0L
        val now = { clock }
        val gwId = SoftwareIdentityKey(); val clId = SoftwareIdentityKey()
        val trust = MemoryTrust(); val window = PairingWindow(now).also { it.open() }
        val pipe = Pipe()
        val gwFrames = mutableListOf<ByteArray>(); val clFrames = mutableListOf<ByteArray>()
        var gatewayPeer: TrustedPeer? = null
        lateinit var gw: GatewayLink
        val gl = object : GatewayLink.Listener {
            override fun onPairingRequest(sas: String, clientName: String, clientFingerprint: String) { gw.confirmSas() }
            override fun onAudio(client: TrustedPeer, frame: ByteArray) { gwFrames += frame }
        }
        gw = GatewayLink(gwId, pipe.gatewaySide, "Pixel", trust, window, gl, now)
        lateinit var cl: ClientLink
        cl = ClientLink.pair(clId, pipe.clientSide, "iPhone", object : ClientLink.Listener {
            override fun onSasReady(sas: String, gatewayName: String, gatewayFingerprint: String) { cl.confirmSas() }
            override fun onPaired(gateway: TrustedPeer) { gatewayPeer = gateway }
            override fun onAudio(frame: ByteArray) { clFrames += frame }
        }, now)
        cl.start(); pipe.pump(cl, gw)
        assertTrue(cl.isEstablished && gw.isEstablished && gatewayPeer != null)
        repeat(50) { i ->
            cl.sendAudio(AudioFrame(AudioSpec.CODEC_PCMU, i, ByteArray(160) { 0x7F }).encode())
            gw.sendAudio(AudioFrame(AudioSpec.CODEC_PCMU, i, ByteArray(160) { 0x55 }).encode())
        }
        pipe.pump(cl, gw)
        assertEquals(50, gwFrames.size); assertEquals(50, clFrames.size)
        assertEquals(49, AudioFrame.decode(clFrames.last())!!.seq)
    }
}
