package studio.virgule.pol.core

import studio.virgule.pol.core.audio.Codecs
import studio.virgule.pol.core.audio.OpusVoiceDecoder
import studio.virgule.pol.core.audio.OpusVoiceEncoder
import studio.virgule.pol.core.audio.PcmuDecoder
import studio.virgule.pol.core.audio.PcmuEncoder
import studio.virgule.pol.core.audio.Resample
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodecTest {
    /** Speech-band tones plus a 5 kHz component, which only a wideband codec can carry. */
    private fun signal(rate: Int, frames: Int): ShortArray = ShortArray(rate / 50 * frames) { i ->
        val t = i.toDouble() / rate
        (6000 * sin(2 * PI * 440 * t) + 4000 * sin(2 * PI * 1300 * t) + 3000 * sin(2 * PI * 5000 * t)).toInt().toShort()
    }

    /** Magnitude of one frequency in a signal (single-bin DFT). */
    private fun level(x: ShortArray, rate: Int, f: Double): Double {
        var re = 0.0; var im = 0.0
        for (i in x.indices) { val a = 2 * PI * f * i / rate; re += x[i] * cos(a); im -= x[i] * sin(a) }
        return 2 * sqrt(re * re + im * im) / x.size
    }

    private fun roundTrip(enc: (ShortArray) -> ByteArray, dec: (ByteArray?) -> ShortArray, input: ShortArray, frame: Int, dropEvery: Int = 0): Pair<ShortArray, Int> {
        val out = ArrayList<Short>(); var bytes = 0
        var i = 0; var n = 0
        while (i + frame <= input.size) {
            val packet = enc(input.copyOfRange(i, i + frame)); bytes += packet.size
            val lost = dropEvery > 0 && ++n % dropEvery == 0
            out.addAll(dec(if (lost) null else packet).toList())
            i += frame
        }
        return out.toShortArray() to bytes
    }

    @Test fun opusIsWidebandAndCompact() {
        val rate = 16_000
        val input = signal(rate, 100) // 2 s
        val enc = OpusVoiceEncoder(rate); val dec = OpusVoiceDecoder(rate)
        val (out, bytes) = roundTrip(enc::encode, dec::decode, input, 320)
        val steady = out.copyOfRange(rate / 2, out.size) // skip codec start-up
        val ref = input.copyOfRange(rate / 2, out.size)
        assertTrue(level(steady, rate, 440.0) > 0.7 * level(ref, rate, 440.0), "speech band kept")
        assertTrue(level(steady, rate, 5000.0) > 0.4 * level(ref, rate, 5000.0), "5 kHz kept: wideband / HD")
        val kbps = bytes * 8.0 / 2.0 / 1000
        assertTrue(kbps in 20.0..40.0, "bitrate $kbps kbit/s")
    }

    @Test fun g711CannotCarryWideband() {
        val input8k = Resample.convert(signal(16_000, 100), 16_000, 8_000)
        val (out, _) = roundTrip(PcmuEncoder()::encode, PcmuDecoder()::decode, input8k, 160)
        assertTrue(level(out, 8000, 440.0) > 1000)
        assertEquals(160 * 100, out.size)
    }

    @Test fun opusConcealsLostFrames() {
        val rate = 16_000
        val input = signal(rate, 100)
        val (out, _) = roundTrip(OpusVoiceEncoder(rate)::encode, OpusVoiceDecoder(rate)::decode, input, 320, dropEvery = 10)
        assertEquals(input.size, out.size, "every frame produces audio, lost ones concealed")
        val steady = out.copyOfRange(rate / 2, out.size)
        assertTrue(level(steady, rate, 440.0) > 0.5 * level(input.copyOfRange(rate / 2, out.size), rate, 440.0))
    }

    @Test fun negotiation() {
        assertEquals("opus", Codecs.negotiate(listOf("opus", "pcmu")))
        assertEquals("pcmu", Codecs.negotiate(listOf("pcmu")))
        assertEquals("pcmu", Codecs.negotiate(listOf("amr-wb")))
        assertEquals("pcmu", Codecs.negotiate(emptyList()))
    }
}
