package studio.virgule.pol.core.audio

import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusBandwidth
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import io.github.jaredmdobson.concentus.OpusSignal

/**
 * A voice codec for one direction of one call. 20 ms frames of 16-bit mono PCM at [sampleRate].
 * Instances keep state (Opus is adaptive) and are not thread-safe: one encoder per sending thread.
 */
interface VoiceEncoder {
    val sampleRate: Int
    val frameSamples get() = sampleRate / 50
    fun encode(pcm: ShortArray): ByteArray
}

interface VoiceDecoder {
    val sampleRate: Int
    val frameSamples get() = sampleRate / 50
    /** Decodes a frame; [frame] null means it was lost/late, and the decoder conceals it. */
    fun decode(frame: ByteArray?): ShortArray
}

object Codecs {
    const val OPUS = "opus"
    const val PCMU = "pcmu"
    const val ID_OPUS = 1
    const val ID_PCMU = 2

    /** What the gateway supports, best first. */
    val gatewayPreference = listOf(OPUS, PCMU)

    /** Picks the first codec in the client's list the gateway also supports; G.711 if the client lists none. */
    fun negotiate(client: List<String>): String = client.firstOrNull { it in gatewayPreference } ?: PCMU

    fun id(name: String) = if (name == OPUS) ID_OPUS else ID_PCMU

    fun encoder(name: String, sampleRate: Int): VoiceEncoder = if (name == OPUS) OpusVoiceEncoder(sampleRate) else PcmuEncoder()
    fun decoder(name: String, sampleRate: Int): VoiceDecoder = if (name == OPUS) OpusVoiceDecoder(sampleRate) else PcmuDecoder()
}

/**
 * Opus for voice: wideband at 16 kHz ("HD voice", 50–7000 Hz), 32 kbit/s, in-band FEC so a lost frame can be
 * recovered from the next one. Opus packets are independent of the sample rate on either side, so this
 * interoperates with libopus on the iPhone running at 48 kHz.
 */
class OpusVoiceEncoder(override val sampleRate: Int = 16_000, bitrate: Int = 32_000) : VoiceEncoder {
    private val enc = OpusEncoder(sampleRate, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        setBitrate(bitrate)
        setComplexity(5)
        setSignalType(OpusSignal.OPUS_SIGNAL_VOICE)
        setUseInbandFEC(true)
        setPacketLossPercent(10)
        setUseDTX(false)
        setMaxBandwidth(OpusBandwidth.OPUS_BANDWIDTH_WIDEBAND)
    }
    private val out = ByteArray(1275)
    override fun encode(pcm: ShortArray): ByteArray {
        require(pcm.size == frameSamples) { "frame must be 20 ms" }
        val n = enc.encode(pcm, 0, frameSamples, out, 0, out.size)
        return out.copyOf(n)
    }
}

/**
 * Decodes at 48 kHz and averages down to [sampleRate]. Decoding straight to 16 kHz returns silence for fullband
 * packets in Concentus 1.0.2 (found by the libopus interop test, see docs/BUILD.md). Decoding at 48 kHz is
 * correct for every packet type.
 */
class OpusVoiceDecoder(override val sampleRate: Int = 16_000) : VoiceDecoder {
    private val dec = OpusDecoder(48_000, 1)
    private val pcm48 = ShortArray(960)
    private val factor = 48_000 / sampleRate
    override fun decode(frame: ByteArray?): ShortArray {
        val n = try {
            if (frame == null) dec.decode(null, 0, 0, pcm48, 0, 960, false)
            else dec.decode(frame, 0, frame.size, pcm48, 0, 960, false)
        } catch (e: Exception) { pcm48.fill(0); 960 }
        return ShortArray(n / factor) { i ->
            var sum = 0
            for (k in 0 until factor) sum += pcm48[i * factor + k]
            (sum / factor).toShort()
        }
    }
}

/** G.711 µ-law at 8 kHz: the fallback for clients or phones that can't do Opus. */
class PcmuEncoder : VoiceEncoder {
    override val sampleRate = 8000
    override fun encode(pcm: ShortArray) = G711.encode(pcm)
}

class PcmuDecoder : VoiceDecoder {
    override val sampleRate = 8000
    private var last = ShortArray(160)
    override fun decode(frame: ByteArray?): ShortArray {
        if (frame == null) return ShortArray(160) { (last[it] / 2).toShort() } // fade the previous frame
        last = G711.decode(frame); return last
    }
}

/** Linear resampling between 8 and 16 kHz for when the helper's rate and the codec's differ. */
object Resample {
    fun convert(pcm: ShortArray, from: Int, to: Int): ShortArray {
        if (from == to) return pcm
        val n = pcm.size * to / from
        return ShortArray(n) { i ->
            val pos = i.toDouble() * from / to
            val a = pos.toInt().coerceAtMost(pcm.size - 1); val b = (a + 1).coerceAtMost(pcm.size - 1)
            val f = pos - a
            (pcm[a] * (1 - f) + pcm[b] * f).toInt().toShort()
        }
    }
}

object Pcm {
    fun fromLe(b: ByteArray): ShortArray = ShortArray(b.size / 2) { ((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 0xFF)).toShort() }
    fun toLe(s: ShortArray): ByteArray { val b = ByteArray(s.size * 2); for (i in s.indices) { b[2 * i] = s[i].toByte(); b[2 * i + 1] = (s[i].toInt() shr 8).toByte() }; return b }
}
