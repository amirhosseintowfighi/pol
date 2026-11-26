package studio.virgule.pol.core.audio

/**
 * Call audio over the link (protocol/PROTOCOL.md §6).
 *
 * Codecs (negotiated per call, see [Codecs]): Opus wideband 16 kHz at 32 kbit/s ("HD voice"), with G.711 µ-law
 * 8 kHz as the fallback. 20 ms frames. Frames travel in sealed records (channel 0x01), so they are encrypted and
 * authenticated like everything else.
 */
object AudioSpec {
    const val CODEC_OPUS: Int = 1
    const val CODEC_PCMU: Int = 2
    const val SAMPLE_RATE = 8000
    const val FRAME_SAMPLES = 160 // 20 ms
    const val FRAME_MS = 20
    const val CODEC_NAME = "pcmu"
}

/** G.711 µ-law (ITU-T G.711 §A, the standard bias-and-segment algorithm). */
object G711 {
    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encode(sample: Short): Byte {
        var s = sample.toInt()
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (exponent > 0 && (s and mask) == 0) { exponent--; mask = mask shr 1 }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun decode(code: Byte): Short {
        val u = code.toInt().inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (sign != 0) -magnitude else magnitude).toShort()
    }

    fun encode(pcm: ShortArray): ByteArray = ByteArray(pcm.size) { encode(pcm[it]) }
    fun decode(data: ByteArray): ShortArray = ShortArray(data.size) { decode(data[it]) }

    /** Little-endian 16-bit PCM bytes (what Android's AudioRecord/AudioTrack use) to µ-law. */
    fun encodeLe(pcm: ByteArray, len: Int = pcm.size): ByteArray =
        ByteArray(len / 2) { i -> encode(((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xFF)).toShort()) }

    fun decodeLe(data: ByteArray): ByteArray {
        val out = ByteArray(data.size * 2)
        for (i in data.indices) { val v = decode(data[i]).toInt(); out[2 * i] = v.toByte(); out[2 * i + 1] = (v shr 8).toByte() }
        return out
    }
}

/** `codec: u8 | seq: u16 | payload`. */
class AudioFrame(val codec: Int, val seq: Int, val payload: ByteArray) {
    fun encode(): ByteArray {
        val out = ByteArray(3 + payload.size)
        out[0] = codec.toByte(); out[1] = (seq shr 8).toByte(); out[2] = seq.toByte()
        System.arraycopy(payload, 0, out, 3, payload.size)
        return out
    }

    companion object {
        const val MAX_PAYLOAD = 960
        /** Null for anything malformed (wrong codec, empty or oversized payload). */
        fun decode(b: ByteArray): AudioFrame? {
            if (b.size < 4 || b.size - 3 > MAX_PAYLOAD) return null
            val codec = b[0].toInt() and 0xFF
            if (codec != AudioSpec.CODEC_PCMU && codec != AudioSpec.CODEC_OPUS) return null
            return AudioFrame(codec, ((b[1].toInt() and 0xFF) shl 8) or (b[2].toInt() and 0xFF), b.copyOfRange(3, b.size))
        }
    }
}

/**
 * Playout buffer. The sealed link already delivers frames in order without loss or duplicates (strict record
 * counters), so this only absorbs Bluetooth timing jitter: it starts after [startFrames] are queued and keeps at most
 * [maxFrames], dropping the oldest so latency cannot grow. Thread-safe: [push] from the link thread, [pop] from the
 * audio thread.
 */
class JitterBuffer(private val startFrames: Int = 3, private val maxFrames: Int = 10) {
    private val frames = ArrayDeque<ByteArray>()
    private var playing = false
    var dropped = 0L
        private set

    @Synchronized fun push(payload: ByteArray) {
        frames.addLast(payload)
        while (frames.size > maxFrames) { frames.removeFirst(); dropped++ }
        if (!playing && frames.size >= startFrames) playing = true
    }

    /** Next frame, or null while buffering or empty (the caller plays silence). */
    @Synchronized fun pop(): ByteArray? {
        if (!playing) return null
        val f = frames.removeFirstOrNull()
        if (f == null) playing = false
        return f
    }

    @Synchronized fun clear() { frames.clear(); playing = false }
    @Synchronized fun size() = frames.size
}
