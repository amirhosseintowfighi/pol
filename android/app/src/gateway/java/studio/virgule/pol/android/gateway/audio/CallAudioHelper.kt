package studio.virgule.pol.android.gateway.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.os.Binder
import android.os.Build
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.concurrent.thread

/**
 * The call-audio helper. Shizuku starts this class in a separate process running as the **shell** user (uid 2000),
 * which Android grants `CALL_AUDIO_INTERCEPTION` and `MODIFY_PHONE_STATE`. A regular app process never gets those.
 *
 * It does exactly one thing: while a cellular call is active, it reads the far party's voice (downlink) and writes
 * the client's voice into the call (uplink) as 16-bit mono PCM (16 kHz wideband, or 8 kHz where the phone only
 * offers narrowband) over a socket the Pol app hands it. It
 * runs no commands, opens no network sockets, stores nothing, and answers only the Pol app's own uid.
 *
 * Uses the Android 13 call-audio interception API (`AudioManager.getCallDownlinkExtractionAudioRecord` /
 * `getCallUplinkInjectionAudioTrack`, `@SystemApi`, reached by reflection). Whether a phone allows it is decided by
 * its audio HAL: [CODE_PROBE] reports `isPstnCallAudioInterceptable()`.
 */
class CallAudioHelper(private val context: Context?) : Binder() {
    /** Shizuku uses this constructor on older servers. */
    constructor() : this(null)

    private val audio: AudioManager? get() = context?.getSystemService(AudioManager::class.java)
    private val ownerUid: Int = context?.let { runCatching { it.packageManager.getPackageUid(it.packageName, 0) }.getOrNull() } ?: -1
    @Volatile private var session: Session? = null

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (code == CODE_DESTROY) { session?.stop(); System.exit(0) }
        // Only the Pol app may drive the helper (Shizuku binds it for that app alone; checked anyway).
        if (ownerUid >= 0 && getCallingUid() != ownerUid) return false
        if (code !in CODE_PROBE..CODE_STOP) return super.onTransact(code, data, reply, flags)
        data.enforceInterface(DESCRIPTOR)
        reply?.writeNoException()
        when (code) {
            CODE_PROBE -> {
                reply?.writeInt(Build.VERSION.SDK_INT)
                reply?.writeInt(if (interceptable()) 1 else 0)
                reply?.writeInt(Process.myUid())
            }
            CODE_START -> {
                val pfd = data.readFileDescriptor()
                val rate = data.readInt().takeIf { it == 8000 || it == 16000 || it == 48000 } ?: 16000
                val result = if (pfd == null) "no_socket" else start(pfd, rate)
                reply?.writeString(result)
            }
            CODE_STOP -> { session?.stop(); session = null }
        }
        return true
    }

    private fun interceptable(): Boolean = Build.VERSION.SDK_INT >= 33 && runCatching {
        AudioManager::class.java.getMethod("isPstnCallAudioInterceptable").invoke(audio) as Boolean
    }.getOrDefault(false)

    /** Returns "ok" or an error code. */
    private fun start(pfd: ParcelFileDescriptor, rate: Int): String {
        session?.stop()
        val am = audio ?: return "no_context"
        if (Build.VERSION.SDK_INT < 33) return "android_version"
        if (!interceptable()) return "not_interceptable"
        return try {
            val inFmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
            val outFmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
            val record = AudioManager::class.java.getMethod("getCallDownlinkExtractionAudioRecord", AudioFormat::class.java).invoke(am, inFmt) as AudioRecord
            val track = AudioManager::class.java.getMethod("getCallUplinkInjectionAudioTrack", AudioFormat::class.java).invoke(am, outFmt) as AudioTrack
            val s = Session(am, pfd, record, track, rate / 50 * 2)
            session = s
            s.run()
            "ok"
        } catch (e: Throwable) {
            runCatching { pfd.close() }
            "failed:" + (e.cause ?: e).javaClass.simpleName
        }
    }

    private class Session(val am: AudioManager, val pfd: ParcelFileDescriptor, val record: AudioRecord, val track: AudioTrack, val frameBytes: Int) {
        @Volatile var running = true
        private var previousMode = am.mode
        private var changedMode = false

        fun run() {
            // Take the gateway's own microphone and earpiece out of the call where the phone supports it, so the
            // person in the room isn't heard and doesn't hear the call. Not fatal if refused.
            runCatching { am.mode = MODE_CALL_REDIRECT; changedMode = am.mode == MODE_CALL_REDIRECT }
            record.startRecording()
            track.play()
            val out = FileOutputStream(pfd.fileDescriptor)
            val input = DataInputStream(FileInputStream(pfd.fileDescriptor))
            thread(name = "tb-downlink", isDaemon = true) {
                val buf = ByteArray(frameBytes)
                try {
                    while (running) {
                        var n = 0
                        while (n < frameBytes && running) { val r = record.read(buf, n, frameBytes - n); if (r <= 0) throw IllegalStateException("read $r"); n += r }
                        out.write(buf)
                    }
                } catch (_: Throwable) {} finally { stop() }
            }
            thread(name = "tb-uplink", isDaemon = true) {
                val buf = ByteArray(frameBytes)
                try { while (running) { input.readFully(buf); track.write(buf, 0, frameBytes) } } catch (_: Throwable) {} finally { stop() }
            }
        }

        @Synchronized fun stop() {
            if (!running) return
            running = false
            runCatching { record.stop() }; runCatching { record.release() }
            runCatching { track.stop() }; runCatching { track.release() }
            if (changedMode) runCatching { if (am.mode == MODE_CALL_REDIRECT) am.mode = previousMode }
            runCatching { pfd.close() }
        }
    }

    companion object {
        const val DESCRIPTOR = "studio.virgule.pol.gateway.CallAudioHelper"
        const val CODE_PROBE = 1
        const val CODE_START = 2
        const val CODE_STOP = 3
        /** Shizuku's "destroy user service" transaction. */
        const val CODE_DESTROY = 16777114
        private const val MODE_CALL_REDIRECT = 5
    }
}
