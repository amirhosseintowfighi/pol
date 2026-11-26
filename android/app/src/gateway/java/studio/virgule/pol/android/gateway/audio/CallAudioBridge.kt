package studio.virgule.pol.android.gateway.audio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import studio.virgule.pol.android.common.SafeLog
import studio.virgule.pol.core.audio.Pcm
import java.io.DataInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import rikka.shizuku.Shizuku

/**
 * App-side half of call audio. Talks to [CallAudioHelper] (shell user, started by Shizuku) over Binder, and
 * exchanges 20 ms PCM frames with it over a Unix socket pair. The runtime encodes them (Opus or G.711) for the link.
 *
 * Setup the owner does once per gateway reboot (Android 13+): install Shizuku, start it with Wireless debugging,
 * allow Pol in Shizuku. Nothing here uses the network; Wireless debugging needs a Wi-Fi network to start but
 * Pol itself never connects to it.
 */
class CallAudioBridge(private val ctx: Context, private val handler: Handler, private val onChange: () -> Unit) {

    enum class Setup { ANDROID_VERSION, SHIZUKU_MISSING, SHIZUKU_STOPPED, PERMISSION_NEEDED, CONNECTING, NOT_INTERCEPTABLE, READY }

    @Volatile var setup: Setup = Setup.SHIZUKU_STOPPED
        private set
    @Volatile private var helper: IBinder? = null
    private var socket: ParcelFileDescriptor? = null
    private var out: FileOutputStream? = null
    private val writer = Executors.newSingleThreadExecutor()
    @Volatile private var active = false

    private val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, CallAudioHelper::class.java.name))
        .daemon(false).processNameSuffix("audio").tag("pol-audio").version(1)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            helper = binder
            handler.post { probe() }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            helper = null
            handler.post { stop(); refresh() }
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { handler.post { refresh() } }
    private val binderDead = Shizuku.OnBinderDeadListener { handler.post { helper = null; stop(); refresh() } }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> handler.post { refresh() } }

    fun start() {
        if (Build.VERSION.SDK_INT < 33) { setup = Setup.ANDROID_VERSION; onChange(); return }
        Shizuku.addBinderReceivedListenerSticky(binderReceived, handler)
        Shizuku.addBinderDeadListener(binderDead, handler)
        Shizuku.addRequestPermissionResultListener(permissionResult, handler)
        refresh()
    }

    fun shutdown() {
        stop()
        runCatching { if (helper != null) Shizuku.unbindUserService(args, connection, true) }
        Shizuku.removeBinderReceivedListener(binderReceived)
        Shizuku.removeBinderDeadListener(binderDead)
        Shizuku.removeRequestPermissionResultListener(permissionResult)
        writer.shutdownNow()
    }

    /** Re-evaluates Shizuku and binds the helper when allowed. Only the owner's tap requests the permission. */
    fun refresh() {
        val before = setup
        setup = when {
            Build.VERSION.SDK_INT < 33 -> Setup.ANDROID_VERSION
            !shizukuInstalled() -> Setup.SHIZUKU_MISSING
            !Shizuku.pingBinder() -> Setup.SHIZUKU_STOPPED
            Shizuku.isPreV11() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> Setup.PERMISSION_NEEDED
            helper == null -> { runCatching { Shizuku.bindUserService(args, connection) }; Setup.CONNECTING }
            else -> if (setup == Setup.NOT_INTERCEPTABLE || setup == Setup.READY) setup else Setup.CONNECTING
        }
        if (setup != before) onChange()
    }

    /** Owner tapped "Allow in Shizuku". */
    fun requestPermission() { if (Shizuku.pingBinder() && !Shizuku.isPreV11()) Shizuku.requestPermission(REQUEST_CODE) }

    private fun shizukuInstalled() = runCatching { ctx.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true }.getOrDefault(false) || Shizuku.pingBinder()

    private fun probe() {
        val b = helper ?: return
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(CallAudioHelper.DESCRIPTOR)
            b.transact(CallAudioHelper.CODE_PROBE, data, reply, 0)
            reply.readException()
            reply.readInt() // sdk
            val interceptable = reply.readInt() == 1
            val uid = reply.readInt()
            setup = if (!interceptable) Setup.NOT_INTERCEPTABLE else Setup.READY
            if (uid != SHELL_UID && uid != 0) SafeLog.w("audio", "helper not running as shell")
        } catch (e: Exception) {
            SafeLog.w("audio", "probe failed: ${e.javaClass.simpleName}")
            setup = Setup.CONNECTING
        } finally { data.recycle(); reply.recycle() }
        onChange()
    }

    val ready get() = setup == Setup.READY && helper != null

    /** Sample rate the helper is running at (16000 wideband, or 8000). */
    @Volatile var rate = 16000
        private set

    /**
     * Starts carrying audio for the active call, at 16 kHz if the phone allows it, else 8 kHz. [onDownlink] receives
     * 20 ms PCM frames on a background thread. Returns null on success, else an error code.
     */
    fun begin(onDownlink: (ShortArray) -> Unit): String? {
        if (active) return null
        var err: String? = null
        for (r in intArrayOf(16000, 8000)) {
            err = beginAt(r, onDownlink)
            if (err == null || err.startsWith("not_interceptable") || err == "not_set_up") break
        }
        return err
    }

    private fun beginAt(sampleRate: Int, onDownlink: (ShortArray) -> Unit): String? {
        val b = helper ?: return "not_set_up"
        val pair = ParcelFileDescriptor.createSocketPair()
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        val result = try {
            data.writeInterfaceToken(CallAudioHelper.DESCRIPTOR)
            data.writeFileDescriptor(pair[1].fileDescriptor)
            data.writeInt(sampleRate)
            b.transact(CallAudioHelper.CODE_START, data, reply, 0)
            reply.readException()
            reply.readString() ?: "failed"
        } catch (e: Exception) { "failed:" + e.javaClass.simpleName } finally { data.recycle(); reply.recycle(); runCatching { pair[1].close() } }
        if (result != "ok") { runCatching { pair[0].close() }; return result }
        socket = pair[0]
        out = FileOutputStream(pair[0].fileDescriptor)
        rate = sampleRate
        active = true
        val input = DataInputStream(FileInputStream(pair[0].fileDescriptor))
        thread(name = "tb-audio-in", isDaemon = true) {
            val pcm = ByteArray(sampleRate / 50 * 2)
            try { while (active) { input.readFully(pcm); onDownlink(Pcm.fromLe(pcm)) } }
            catch (_: Exception) {} finally { handler.post { stop() } }
        }
        return null
    }

    /** Client's voice: one 20 ms PCM frame at [rate]. Never blocks the caller. */
    fun uplink(pcm: ShortArray) {
        if (!active || pcm.size != rate / 50) return
        val bytes = Pcm.toLe(pcm)
        writer.execute { runCatching { out?.write(bytes) } }
    }

    fun stop() {
        if (!active && socket == null) return
        active = false
        helper?.let { b ->
            val data = Parcel.obtain()
            runCatching { data.writeInterfaceToken(CallAudioHelper.DESCRIPTOR); b.transact(CallAudioHelper.CODE_STOP, data, null, IBinder.FLAG_ONEWAY) }
            data.recycle()
        }
        runCatching { socket?.close() }
        socket = null; out = null
    }

    val isActive get() = active

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        private const val REQUEST_CODE = 41
        private const val SHELL_UID = 2000
    }
}
