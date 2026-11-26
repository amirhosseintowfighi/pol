package studio.virgule.pol.android.client

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.telecom.CallAudioState
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import studio.virgule.pol.android.common.SafeLog
import studio.virgule.pol.core.audio.AudioFrame
import studio.virgule.pol.core.audio.Codecs
import studio.virgule.pol.core.audio.JitterBuffer
import studio.virgule.pol.core.audio.VoiceDecoder
import studio.virgule.pol.core.audio.VoiceEncoder
import studio.virgule.pol.core.domain.LiveCall
import studio.virgule.pol.core.domain.LiveCallState
import studio.virgule.pol.core.proto.AudioStart
import studio.virgule.pol.core.proto.AudioState
import studio.virgule.pol.core.proto.AudioStop
import studio.virgule.pol.core.proto.CallActions
import studio.virgule.pol.core.proto.T
import studio.virgule.pol.core.proto.Wire
import kotlin.concurrent.thread

/**
 * "Take calls on this phone" for the Android client: gateway calls become self-managed Telecom calls here, so they
 * ring, use the earpiece / speaker / Bluetooth headset, and get Android's call audio handling, while the voice is
 * carried to and from the gateway (Opus wideband, see core Codecs). The Android counterpart of the iPhone's CallKit.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO and MANAGE_OWN_CALLS are checked in [enabled].
class ClientCalls(private val ctx: Context, private val handler: Handler, private val send: (String) -> Unit, private val sendAudio: (ByteArray) -> Unit) {
    private val telecom = ctx.getSystemService(TelecomManager::class.java)
    val account = PhoneAccountHandle(ComponentName(ctx, ClientConnectionService::class.java), "pol")
    private val connections = HashMap<String, BridgedConnection>()
    private var pendingOutgoing: BridgedConnection? = null
    private var audio: ClientAudio? = null
    private var audioFor: String? = null

    fun enabled(): Boolean = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun register() {
        val acct = PhoneAccount.builder(account, "Pol")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
            .build()
        runCatching { telecom.registerPhoneAccount(acct) }.onFailure { SafeLog.w("calls", "account not registered") }
    }

    fun unregister() { runCatching { telecom.unregisterPhoneAccount(account) } }

    // ------------------------------------------------------------ gateway → Telecom

    /** Called on every call.state from the gateway (link thread). */
    fun update(calls: List<LiveCall>, bridged: Boolean) {
        val ids = calls.map { it.callId }.toSet()
        for (id in connections.keys - ids) { connections.remove(id)?.ended(DisconnectCause.REMOTE); if (audioFor == id) stopAudio() }
        if (!bridged) return
        for (c in calls) {
            val conn = connections[c.callId]
            when {
                conn != null -> {
                    if (c.state == LiveCallState.ACTIVE && conn.state != Connection.STATE_ACTIVE) { conn.setActive(); requestAudio(c.callId) }
                    if (c.state == LiveCallState.HOLDING && conn.state != Connection.STATE_HOLDING) conn.setOnHold()
                }
                c.incoming && c.state == LiveCallState.RINGING -> {
                    val extras = Bundle().apply {
                        putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", c.number, null))
                        putString(EXTRA_CALL_ID, c.callId); putString(EXTRA_NAME, c.name)
                    }
                    runCatching { telecom.addNewIncomingCall(account, extras) }.onFailure { SafeLog.w("calls", "incoming call refused") }
                }
                !c.incoming && pendingOutgoing != null -> {
                    val p = pendingOutgoing!!
                    pendingOutgoing = null
                    p.callId = c.callId; connections[c.callId] = p
                    if (c.state == LiveCallState.ACTIVE) { p.setActive(); requestAudio(c.callId) }
                }
            }
        }
    }

    fun onAudioState(s: AudioState) {
        when (s.state) {
            "active" -> {
                val codec = s.codec ?: Codecs.PCMU
                val a = ClientAudio(codec, sendAudio)
                ClientService.setInCall(ctx, true) // microphone + phone-call foreground types while talking
                if (a.start()) audio = a else { send(Wire.encode(T.AUDIO_STOP, AudioStop.serializer(), AudioStop(s.callId))); audioFor = null }
            }
            "failed", "stopped" -> stopAudio()
        }
    }

    fun onAudioFrame(bytes: ByteArray) { AudioFrame.decode(bytes)?.let { audio?.receive(it) } }

    fun linkLost() {
        connections.values.toList().forEach { it.ended(DisconnectCause.ERROR) }
        connections.clear(); pendingOutgoing?.ended(DisconnectCause.ERROR); pendingOutgoing = null
        stopAudio()
    }

    /** Dial from this phone's Pol UI while bridged: goes through Telecom so audio routing works. */
    fun placeCall(number: String): Boolean = runCatching {
        telecom.placeCall(Uri.fromParts("tel", number, null), Bundle().apply { putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, account) })
        true
    }.getOrDefault(false)

    fun isBridged(callId: String) = connections.containsKey(callId)
    fun setMuted(on: Boolean) { audio?.muted = on }
    @Suppress("DEPRECATION")
    fun setSpeaker(callId: String, on: Boolean) { connections[callId]?.setAudioRoute(if (on) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_WIRED_OR_EARPIECE) }
    val audioActive get() = audio != null
    fun connection(callId: String) = connections[callId]

    private fun requestAudio(callId: String) {
        if (audioFor == callId) return
        audioFor = callId
        send(Wire.encode(T.AUDIO_START, AudioStart.serializer(), AudioStart(callId, Codecs.OPUS, Codecs.gatewayPreference)))
    }

    private fun stopAudio() {
        audio?.stop(); audio = null; audioFor = null
        ClientService.setInCall(ctx, false)
    }

    // ------------------------------------------------------------ Telecom → gateway (ConnectionService callbacks)

    internal fun incomingConnection(request: ConnectionRequest): Connection {
        val extras = request.extras
        val id = extras?.getString(EXTRA_CALL_ID) ?: return Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
        val c = BridgedConnection(id)
        @Suppress("DEPRECATION")
        val address: Uri? = extras.getParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS)
        c.setAddress(address, TelecomManager.PRESENTATION_ALLOWED)
        extras.getString(EXTRA_NAME)?.let { c.setCallerDisplayName(it, TelecomManager.PRESENTATION_ALLOWED) }
        c.setRinging()
        connections[id] = c
        return c
    }

    internal fun outgoingConnection(request: ConnectionRequest): Connection {
        val number = request.address?.schemeSpecificPart.orEmpty()
        val c = BridgedConnection(null)
        c.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
        c.setDialing()
        pendingOutgoing = c
        handler.post { ClientRepository.get(ctx).placeOnGateway(number) }
        return c
    }

    private fun action(id: String?, a: String) { if (id != null) handler.post { ClientRepository.get(ctx).model.callAction(id, a) } }

    inner class BridgedConnection(var callId: String?) : Connection() {
        init {
            connectionProperties = PROPERTY_SELF_MANAGED
            audioModeIsVoip = true
            connectionCapabilities = CAPABILITY_MUTE or CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        }

        override fun onShowIncomingCallUi() { ClientNotifications.incomingCall(ctx, callId ?: return, address?.schemeSpecificPart) }
        override fun onAnswer() { action(callId, CallActions.ANSWER); callId?.let { handler.post { requestAudio(it) } }; setActive() }
        override fun onReject() { action(callId, CallActions.REJECT); ended(DisconnectCause.REJECTED) }
        override fun onDisconnect() {
            action(callId, CallActions.END)
            callId?.let { id -> handler.post { send(Wire.encode(T.AUDIO_STOP, AudioStop.serializer(), AudioStop(id))); if (audioFor == id) stopAudio() } }
            ended(DisconnectCause.LOCAL)
        }
        override fun onHold() { action(callId, CallActions.HOLD); setOnHold() }
        override fun onUnhold() { action(callId, CallActions.UNHOLD); setActive() }
        override fun onPlayDtmfTone(c: Char) {
            val id = callId ?: return
            handler.post {
                if (ClientRepository.get(ctx).model.dtmf(id, c.toString()) != studio.virgule.pol.core.client.ClientModel.ActionResult.Ok) audio?.playDtmf(c)
            }
        }
        @Deprecated("Deprecated in Java")
        override fun onCallAudioStateChanged(state: CallAudioState) { audio?.muted = state.isMuted }

        fun ended(cause: Int) {
            ClientNotifications.clearCall(ctx)
            setDisconnected(DisconnectCause(cause)); destroy()
            callId?.let { connections.remove(it) }
        }
    }

    companion object {
        const val EXTRA_CALL_ID = "pol.callId"
        const val EXTRA_NAME = "pol.name"
        @Volatile var current: ClientCalls? = null
    }
}

/** Telecom's entry point for our self-managed calls. Bound only by Telecom (BIND_TELECOM_CONNECTION_SERVICE). */
class ClientConnectionService : ConnectionService() {
    override fun onCreateIncomingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        ClientCalls.current?.incomingConnection(request) ?: Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))

    override fun onCreateOutgoingConnection(account: PhoneAccountHandle?, request: ConnectionRequest): Connection =
        ClientCalls.current?.outgoingConnection(request) ?: Connection.createFailedConnection(DisconnectCause(DisconnectCause.ERROR))
}

/**
 * Microphone and earpiece/speaker for one bridged call, 16 kHz with the platform's voice-communication processing
 * (echo cancellation, noise suppression). Frames are encoded on the capture thread and decoded on the playback
 * thread; nothing is recorded.
 */
@SuppressLint("MissingPermission")
class ClientAudio(private val codec: String, private val sendAudio: (ByteArray) -> Unit) {
    private val rate = if (codec == Codecs.OPUS) 16_000 else 8_000
    private val frame = rate / 50
    private val encoder: VoiceEncoder = Codecs.encoder(codec, rate)
    private val decoder: VoiceDecoder = Codecs.decoder(codec, rate)
    private val jitter = JitterBuffer(startFrames = 3, maxFrames = 10)
    @Volatile private var running = false
    @Volatile var muted = false
    @Volatile private var tone: Pair<Char, Int>? = null
    private var seq = 0
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null

    fun start(): Boolean = try {
        val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minIn, frame * 8))
        if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(r.audioSessionId)?.enabled = true
        if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(r.audioSessionId)?.enabled = true
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(frame * 2 * 6)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        record = r; track = t; running = true
        r.startRecording(); t.play()
        thread(name = "tb-mic", isDaemon = true) {
            val pcm = ShortArray(frame)
            while (running) {
                var n = 0
                while (n < frame && running) { val k = r.read(pcm, n, frame - n); if (k <= 0) { running = false; break }; n += k }
                if (!running) break
                val out = if (muted) ShortArray(frame) else pcm.copyOf()
                mixTone(out)
                sendAudio(AudioFrame(Codecs.id(codec), seq, encoder.encode(out)).encode())
                seq = (seq + 1) and 0xFFFF
            }
        }
        thread(name = "tb-speaker", isDaemon = true) {
            var wasPlaying = false
            while (running) {
                val f = jitter.pop()
                val pcm = when {
                    f != null -> { wasPlaying = true; decoder.decode(f) }
                    wasPlaying -> { wasPlaying = false; decoder.decode(null) } // conceal one gap, then wait
                    else -> ShortArray(frame)
                }
                t.write(pcm, 0, pcm.size) // blocking: paces this loop at the playback rate
            }
        }
        true
    } catch (e: Exception) {
        SafeLog.w("audio", "client audio failed: ${e.javaClass.simpleName}")
        stop(); false
    }

    fun receive(f: AudioFrame) { if (running && f.codec == Codecs.id(codec)) jitter.push(f.payload) }

    fun playDtmf(c: Char) { tone = c to (rate * 16 / 100) }

    private fun mixTone(pcm: ShortArray) {
        val (c, left) = tone ?: return
        val rows = mapOf('1' to 697, '2' to 697, '3' to 697, '4' to 770, '5' to 770, '6' to 770, '7' to 852, '8' to 852, '9' to 852, '*' to 941, '0' to 941, '#' to 941)
        val cols = mapOf('1' to 1209, '4' to 1209, '7' to 1209, '*' to 1209, '2' to 1336, '5' to 1336, '8' to 1336, '0' to 1336, '3' to 1477, '6' to 1477, '9' to 1477, '#' to 1477)
        val f1 = rows[c] ?: return; val f2 = cols[c] ?: return
        val start = rate * 16 / 100 - left
        val n = minOf(pcm.size, left)
        for (i in 0 until n) {
            val x = (start + i).toDouble() / rate
            pcm[i] = (8000 * (Math.sin(2 * Math.PI * f1 * x) + Math.sin(2 * Math.PI * f2 * x))).toInt().coerceIn(-32768, 32767).toShort()
        }
        tone = if (left - n > 0) c to (left - n) else null
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }; runCatching { record?.release() }
        runCatching { track?.stop() }; runCatching { track?.release() }
        record = null; track = null
        jitter.clear()
    }
}
