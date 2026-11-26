package studio.virgule.pol.android.gateway

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.provider.CallLog
import android.telephony.TelephonyManager
import studio.virgule.pol.android.common.KeystoreIdentity
import studio.virgule.pol.android.common.SafeLog
import studio.virgule.pol.android.common.SecureStore
import studio.virgule.pol.android.gateway.audio.CallAudioBridge
import studio.virgule.pol.core.audio.AudioFrame
import studio.virgule.pol.core.audio.Codecs
import studio.virgule.pol.core.audio.Resample
import studio.virgule.pol.core.audio.VoiceDecoder
import studio.virgule.pol.core.audio.VoiceEncoder
import studio.virgule.pol.core.client.numberKey
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.domain.GatewayStatus
import studio.virgule.pol.core.domain.LiveCall
import studio.virgule.pol.core.domain.LiveCallState
import studio.virgule.pol.core.link.CloseReason
import studio.virgule.pol.core.link.GatewayLink
import studio.virgule.pol.core.link.PairingWindow
import studio.virgule.pol.core.link.TrustedPeer
import studio.virgule.pol.core.proto.AudioReasons
import studio.virgule.pol.core.proto.AudioStart
import studio.virgule.pol.core.proto.AudioState
import studio.virgule.pol.core.proto.AudioStop
import studio.virgule.pol.core.proto.CallAck
import studio.virgule.pol.core.proto.CallAction
import studio.virgule.pol.core.proto.CallActions
import studio.virgule.pol.core.proto.CallDial
import studio.virgule.pol.core.proto.CallStates
import studio.virgule.pol.core.proto.CallsNew
import studio.virgule.pol.core.proto.CallsPage
import studio.virgule.pol.core.proto.ContactsPage
import studio.virgule.pol.core.proto.Envelope
import studio.virgule.pol.core.proto.Err
import studio.virgule.pol.core.proto.ErrorBody
import studio.virgule.pol.core.proto.Ping
import studio.virgule.pol.core.proto.RecentIds
import studio.virgule.pol.core.proto.SmsAck
import studio.virgule.pol.core.proto.SmsNew
import studio.virgule.pol.core.proto.SmsPage
import studio.virgule.pol.core.proto.SmsSend
import studio.virgule.pol.core.proto.SyncReq
import studio.virgule.pol.core.proto.T
import studio.virgule.pol.core.proto.UssdResult
import studio.virgule.pol.core.proto.UssdSend
import studio.virgule.pol.core.proto.Validate
import studio.virgule.pol.core.proto.Wire
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class PairingRequest(val sas: String, val clientName: String, val fingerprint: String)

/** What the gateway screen shows. Contains no message, call or contact content. */
data class GatewayUi(
    val running: Boolean = false,
    val bluetoothOn: Boolean = true,
    val advertisingError: Int? = null,
    val pairingOpenUntil: Long = 0,
    val pairingRequest: PairingRequest? = null,
    val pairingResult: String? = null,
    val connected: List<String> = emptyList(),
    val paired: List<TrustedPeer> = emptyList(),
    val identityFingerprint: String = "",
    /** Call-audio setup step (CallAudioBridge.Setup name) and whether audio is flowing now. */
    val audioSetup: String = "",
    val audioActive: Boolean = false,
)

object GatewayState {
    internal val mutable = MutableStateFlow(GatewayUi())
    val ui: StateFlow<GatewayUi> = mutable
}

/**
 * The running gateway: Bluetooth server, one [GatewayLink] per connected device, and the translation between
 * protocol requests and [Telephony]. Link work runs on [handler]'s thread; provider queries run on [io] and post
 * their results back, so a slow query never stalls Bluetooth.
 */
class GatewayRuntime(private val ctx: Context, private val handler: Handler) {
    private val settings = GatewaySettings(ctx)
    private val store = SecureStore(ctx)
    val paired = PairedDevices(store)
    private val identity = KeystoreIdentity.load()
    private val telephony = Telephony(ctx, handler)
    private val pairing = PairingWindow(System::currentTimeMillis)
    private val io = Executors.newSingleThreadExecutor()
    private val links = HashMap<String, GatewayLink>()
    private val devices = HashMap<String, BluetoothDevice>()
    private val gatt: GattServer = GattServer(ctx, handler, gattListener())
    private val requestIds = RecentIds()
    private val smsStates = HashMap<String, DeliveryState>()
    private val limiter = RateLimiter()
    private var lastSmsId = 0L
    private var lastCallId = 0L
    private val outgoing = ArrayList<Triple<String, String, Long>>() // cmid, number key + body hash, time
    private val liveCalls = LinkedHashMap<String, LiveCall>()
    private var callCounter = 0
    private var lastDialed: Pair<String, Long>? = null
    private var lastPhoneState: String? = null
    /** Full call control: Telecom's Call objects (default phone app) replace the phone-state broadcast. */
    private val registryListener = studio.virgule.pol.android.gateway.telecom.CallRegistry.Listener { handler.post { onRegistryChanged() } }
    private val pairingAddresses = HashSet<String>()
    private val transports = HashMap<String, GattServer.Connection>()

    // Call audio: the client that asked for it, for which call, and whether frames are flowing.
    private val audio = CallAudioBridge(ctx, handler) { handler.post { publish(); broadcastStatus() } }
    private var audioWanted: Pair<String, String>? = null // callId, link address
    private var audioSeq = 0
    private var audioCodec = Codecs.PCMU
    // Downlink encoder runs on the bridge's capture thread; uplink decoder on the link thread.
    @Volatile private var downEncoder: VoiceEncoder? = null
    private var upDecoder: VoiceDecoder? = null

    fun start(): Boolean {
        if (!gatt.start()) { publish(); return false }
        gatt.advertise(pairingOpen = false)
        io.execute {
            val sms = telephony.readSms(0, limit = 1).maxOfOrNull { it.id } ?: 0
            val calls = telephony.readCalls(0, limit = 1).maxOfOrNull { it.id } ?: 0
            handler.post { lastSmsId = sms; lastCallId = calls }
        }
        registerObservers()
        studio.virgule.pol.android.gateway.telecom.CallRegistry.addListener(registryListener)
        if (settings.callAudio) audio.start()
        handler.post(tickTask)
        publish(running = true)
        return true
    }

    fun stop() {
        handler.removeCallbacks(tickTask)
        studio.virgule.pol.android.gateway.telecom.CallRegistry.removeListener(registryListener)
        audio.shutdown()
        links.values.toList().forEach { it.close() }
        links.clear()
        gatt.stop()
        unregisterObservers()
        io.shutdownNow()
        GatewayState.mutable.update { it.copy(running = false, connected = emptyList(), pairingRequest = null, pairingOpenUntil = 0) }
    }

    // ------------------------------------------------------------------ owner actions (any thread)

    fun openPairing() = handler.post {
        pairing.open()
        gatt.advertise(pairingOpen = true)
        GatewayState.mutable.update { it.copy(pairingResult = null) }
        publish()
    }

    fun closePairing() = handler.post { pairing.close(); gatt.advertise(false); publish() }

    fun confirmPairing() = handler.post { links.values.forEach { it.confirmSas() } }

    fun declinePairing() = handler.post {
        links.values.filter { !it.isEstablished && it.client == null }.forEach { it.declineSas() }
        GatewayState.mutable.update { it.copy(pairingRequest = null) }
    }

    /** Removes a paired device: it can no longer connect, and an open session is closed now. */
    fun revoke(id: ByteArray) = handler.post {
        paired.remove(id)
        links.entries.filter { it.value.client?.id?.contentEquals(id) == true }.forEach { (_, link) ->
            runCatching { link.send(Wire.encodeEmpty(T.UNPAIR)) }
            link.close(CloseReason.REVOKED)
        }
        publish()
    }

    fun refresh() = handler.post { if (settings.callAudio) audio.refresh(); publish(); broadcastStatus() }

    /** Owner tapped "Allow in Shizuku". */
    fun requestAudioPermission() = handler.post { audio.requestPermission() }

    // ------------------------------------------------------------------ Bluetooth

    private fun gattListener(): GattServer.Listener = object : GattServer.Listener {
        override fun onConnected(device: BluetoothDevice, transport: GattServer.Connection) {
            devices[device.address] = device
            transports[device.address] = transport
            links[device.address] = GatewayLink(identity, transport, settings.name, paired, pairing, linkListener(device.address))
        }

        override fun onBytes(device: BluetoothDevice, bytes: ByteArray) { links[device.address]?.onBytes(bytes) }

        override fun onDisconnected(device: BluetoothDevice) {
            links.remove(device.address)?.onTransportClosed()
            devices.remove(device.address)
            transports.remove(device.address)
            publish()
        }

        override fun onAdvertisingFailed(code: Int) { GatewayState.mutable.update { it.copy(advertisingError = code) } }
    }

    private fun linkListener(address: String): GatewayLink.Listener = object : GatewayLink.Listener {
        override fun onPairingRequest(sas: String, clientName: String, clientFingerprint: String) {
            pairingAddresses += address
            GatewayState.mutable.update { it.copy(pairingRequest = PairingRequest(sas, clientName, clientFingerprint)) }
            GatewayNotifications.pairingRequest(ctx)
        }

        override fun onPaired(client: TrustedPeer) {
            pairingAddresses -= address
            gatt.advertise(pairingOpen = false)
            GatewayState.mutable.update { it.copy(pairingRequest = null, pairingResult = "paired:${client.name}") }
            GatewayNotifications.clearPairing(ctx)
        }

        override fun onEstablished(client: TrustedPeer) {
            SafeLog.d("link", "session established")
            publish()
        }

        override fun onMessage(client: TrustedPeer, json: String) {
            val link = links[address] ?: return
            val env = Wire.decode(json) ?: return
            handle(link, client, env)
        }

        override fun onAudio(client: TrustedPeer, frame: ByteArray) {
            if (audioWanted?.second != address || !audio.isActive) return
            val f = AudioFrame.decode(frame) ?: return
            if (f.codec != Codecs.id(audioCodec)) return
            val dec = upDecoder ?: return
            audio.uplink(Resample.convert(dec.decode(f.payload), dec.sampleRate, audio.rate))
        }

        override fun onClosed(client: TrustedPeer?, reason: CloseReason, byPeer: Boolean) {
            if (pairingAddresses.remove(address)) {
                GatewayState.mutable.update { it.copy(pairingRequest = null, pairingResult = "failed:${reason.name}") }
                GatewayNotifications.clearPairing(ctx)
            }
            if (!pairing.isOpen) gatt.advertise(false)
            if (audioWanted?.second == address) stopAudio(notify = false)
            devices[address]?.let { gatt.disconnect(it) }
            publish()
        }
    }

    private val tickTask = object : Runnable {
        private var n = 0
        override fun run() {
            links.values.toList().forEach { it.tick() }
            if (GatewayState.ui.value.pairingOpenUntil > 0 && !pairing.isOpen) { gatt.advertise(false); publish() }
            if (++n % 30 == 0) broadcastStatus() // every ~60 s while running
            handler.postDelayed(this, 2000)
        }
    }

    // ------------------------------------------------------------------ requests

    private fun reply(link: GatewayLink, msg: String) { if (link.isEstablished) runCatching { link.send(msg) } }

    private fun error(link: GatewayLink, env: Envelope, code: String) {
        if (env.id != null) reply(link, Wire.encode(T.ERROR, ErrorBody.serializer(), ErrorBody(code), re = env.id))
    }

    private fun fullControl() = settings.calls && studio.virgule.pol.android.gateway.telecom.CallRegistry.isDefaultDialer(
        ctx.getSystemService(android.telecom.TelecomManager::class.java), ctx.packageName)

    private fun status(): GatewayStatus = telephony.status(settings.name, settings, appVersion()).let { raw ->
        val full = fullControl()
        val st = if (full) raw.copy(caps = raw.caps.copy(callControl = true, hold = true, dtmf = true)) else raw
        val audioOn = settings.calls && settings.callAudio && st.caps.callControl
        st.copy(
            caps = st.caps.copy(callAudio = audioOn && audio.ready),
            audio = when {
                !audioOn -> "off"
                audio.setup == CallAudioBridge.Setup.ANDROID_VERSION -> "android_version"
                audio.setup == CallAudioBridge.Setup.NOT_INTERCEPTABLE -> "not_interceptable"
                audio.ready -> "ready"
                else -> "needs_setup"
            },
        )
    }

    // ------------------------------------------------------------------ call audio

    private fun sendAudioState(address: String, callId: String, state: String, reason: String? = null, codec: String? = null, rate: Int? = null) {
        links[address]?.let { reply(it, Wire.encode(T.AUDIO_STATE, AudioState.serializer(), AudioState(callId, state, reason, codec, rate))) }
    }

    /** Starts the bridge once the wanted call is active on the phone. */
    private fun maybeStartAudio() {
        val (callId, address) = audioWanted ?: return
        if (audio.isActive) return
        val call = liveCalls[callId] ?: return
        if (call.state != LiveCallState.ACTIVE) return
        audioSeq = 0
        val codecRate = if (audioCodec == Codecs.OPUS) 16_000 else 8_000
        downEncoder = Codecs.encoder(audioCodec, codecRate)
        upDecoder = Codecs.decoder(audioCodec, codecRate)
        val err = audio.begin { pcm ->
            // Encode on the capture thread (Opus costs ~1 ms per frame), then hand the packet to the link thread.
            val enc = downEncoder ?: return@begin
            val packet = enc.encode(Resample.convert(pcm, audio.rate, enc.sampleRate))
            handler.post { sendDownlink(packet) }
        }
        if (err == null) {
            sendAudioState(address, callId, "active", codec = audioCodec, rate = audio.rate)
            GatewayState.mutable.update { it.copy(audioActive = true) }
        } else {
            sendAudioState(address, callId, "failed", if (err.startsWith("not_interceptable")) AudioReasons.NOT_INTERCEPTABLE else AudioReasons.HELPER_FAILED)
            audioWanted = null; downEncoder = null; upDecoder = null
        }
    }

    private fun sendDownlink(packet: ByteArray) {
        val address = audioWanted?.second ?: return
        val link = links[address]?.takeIf { it.isEstablished } ?: return
        // Drop-late: if Bluetooth is behind (> ~6 frames queued), skip this frame *before* sealing, so record
        // counters stay contiguous and latency stays bounded.
        if ((transports[address]?.backlog ?: 0) > 1200) return
        runCatching { link.sendAudio(AudioFrame(Codecs.id(audioCodec), audioSeq, packet).encode()) }
        audioSeq = (audioSeq + 1) and 0xFFFF
    }

    private fun stopAudio(notify: Boolean = true) {
        val wanted = audioWanted
        audioWanted = null
        audio.stop()
        downEncoder = null; upDecoder = null
        GatewayState.mutable.update { it.copy(audioActive = false) }
        if (notify && wanted != null) sendAudioState(wanted.second, wanted.first, "stopped")
    }

    private fun appVersion() = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()

    private fun handle(link: GatewayLink, client: TrustedPeer, env: Envelope) {
        when (env.t) {
            T.SYNC -> {
                val req = Wire.body(env, SyncReq.serializer()) ?: return error(link, env, Err.INVALID)
                io.execute {
                    val st = status()
                    val sms = if (st.caps.smsRead) telephony.readSms(req.smsSince.coerceAtLeast(0)) else emptyList()
                    val calls = if (st.caps.callLog) telephony.readCalls(req.callsSince.coerceAtLeast(0)) else emptyList()
                    val contacts = if (st.caps.contacts && req.contacts) telephony.readContacts() else null
                    val msgs = ArrayList<String>()
                    msgs += Wire.encode(T.STATUS, GatewayStatus.serializer(), st)
                    msgs += Wire.pages(sms, 50) { l, more -> Wire.encode(T.SMS_PAGE, SmsPage.serializer(), SmsPage(l, more)) }
                    msgs += Wire.pages(calls, 100) { l, more -> Wire.encode(T.CALLS_PAGE, CallsPage.serializer(), CallsPage(l, more)) }
                    if (contacts != null) {
                        var first = true
                        msgs += Wire.pages(contacts, 100) { l, more -> Wire.encode(T.CONTACTS_PAGE, ContactsPage.serializer(), ContactsPage(l, more, first)).also { first = false } }
                    }
                    handler.post {
                        msgs.forEach { reply(link, it) }
                        reply(link, Wire.encode(T.CALL_STATE, CallStates.serializer(), CallStates(liveCalls.values.toList())))
                    }
                }
            }
            T.SMS_SEND -> {
                val req = Wire.body(env, SmsSend.serializer())
                if (req == null || !Validate.clientMessageId(req.cmid)) return error(link, env, Err.INVALID)
                val ack = { s: DeliveryState, e: String? -> reply(link, Wire.encode(T.SMS_ACK, SmsAck.serializer(), SmsAck(req.cmid, s, e))) }
                if (!requestIds.firstSeen("sms:${req.cmid}", System.currentTimeMillis())) {
                    return ack(smsStates[req.cmid] ?: DeliveryState.PENDING, null) // retried after reconnect: never send twice
                }
                val to = Validate.smsRecipient(req.to); val body = Validate.smsBody(req.body)
                if (to == null || body == null || !validSub(req.subId)) return ack(DeliveryState.FAILED, Err.INVALID)
                if (!status().caps.smsSend) return ack(DeliveryState.FAILED, Err.UNSUPPORTED)
                if (!limiter.allow("sms", 20)) return ack(DeliveryState.FAILED, Err.BUSY)
                smsStates[req.cmid] = DeliveryState.PENDING
                outgoing += Triple(req.cmid, numberKey(to) + "|" + body.hashCode(), System.currentTimeMillis())
                ack(DeliveryState.PENDING, null)
                telephony.sendSms(to, body, req.subId) { state, err ->
                    handler.post { smsStates[req.cmid] = state; broadcast(Wire.encode(T.SMS_ACK, SmsAck.serializer(), SmsAck(req.cmid, state, err))) }
                }
            }
            T.CALL_DIAL -> {
                val req = Wire.body(env, CallDial.serializer())
                if (req == null || !Validate.clientMessageId(req.cmid)) return error(link, env, Err.INVALID)
                val ack = { ok: Boolean, e: String? -> reply(link, Wire.encode(T.CALL_ACK, CallAck.serializer(), CallAck(req.cmid, ok, e))) }
                if (!requestIds.firstSeen("dial:${req.cmid}", System.currentTimeMillis())) return
                val number = Validate.dialNumber(req.number)
                if (number == null || !validSub(req.subId)) return ack(false, Err.INVALID)
                if (!status().caps.callPlace) return ack(false, Err.UNSUPPORTED)
                if (!limiter.allow("dial", 10)) return ack(false, Err.BUSY)
                val err = telephony.dial(number, req.subId)
                if (err == null) lastDialed = number to System.currentTimeMillis()
                ack(err == null, err)
            }
            T.CALL_ACTION -> {
                val req = Wire.body(env, CallAction.serializer())
                if (req == null || req.action !in CallActions.all) return error(link, env, Err.INVALID)
                val call = liveCalls[req.callId] ?: return error(link, env, Err.INVALID)
                if (!status().caps.callControl) return error(link, env, Err.UNSUPPORTED)
                if (studio.virgule.pol.android.gateway.telecom.CallRegistry.bound) {
                    if (!studio.virgule.pol.android.gateway.telecom.CallRegistry.perform(req.callId, req.action)) error(link, env, Err.FAILED)
                    return
                }
                if (req.action == CallActions.HOLD || req.action == CallActions.UNHOLD) return error(link, env, Err.UNSUPPORTED)
                val ok = when (req.action) {
                    CallActions.ANSWER -> call.state == LiveCallState.RINGING && telephony.answer()
                    else -> telephony.endCall()
                }
                if (!ok) error(link, env, Err.FAILED)
            }
            T.CALL_DTMF -> {
                val req = Wire.body(env, studio.virgule.pol.core.proto.CallDtmf.serializer()) ?: return error(link, env, Err.INVALID)
                if (!studio.virgule.pol.android.gateway.telecom.CallRegistry.bound) return error(link, env, Err.UNSUPPORTED)
                if (req.digits.length > 20 || req.digits.any { it !in "0123456789*#" }) return error(link, env, Err.INVALID)
                req.digits.forEachIndexed { i, d ->
                    handler.postDelayed({ studio.virgule.pol.android.gateway.telecom.CallRegistry.dtmf(req.callId, d) }, i * 250L)
                }
            }
            T.USSD_SEND -> {
                val req = Wire.body(env, UssdSend.serializer())
                if (req == null || !Validate.clientMessageId(req.cmid)) return error(link, env, Err.INVALID)
                val done = { ok: Boolean, text: String? ->
                    reply(link, Wire.encode(T.USSD_RESULT, UssdResult.serializer(), if (ok) UssdResult(req.cmid, true, text) else UssdResult(req.cmid, false, err = text)))
                }
                if (!requestIds.firstSeen("ussd:${req.cmid}", System.currentTimeMillis())) return
                val code = Validate.ussdCode(req.code)
                if (code == null || !validSub(req.subId)) return done(false, Err.INVALID)
                if (!status().caps.ussd) return done(false, Err.UNSUPPORTED)
                if (!limiter.allow("ussd", 10)) return done(false, Err.BUSY)
                telephony.ussd(code, req.subId) { ok, text -> handler.post { done(ok, text) } }
            }
            T.SMS_READ -> {
                // Marking read in Android's SMS store needs the default-SMS-app role, which this app does not
                // take. Read state is kept on the client only.
            }
            T.PING -> Wire.body(env, Ping.serializer())?.let { reply(link, Wire.encode(T.PONG, Ping.serializer(), it, re = env.id)) }
            T.PONG -> {}
            T.AUDIO_START -> {
                val req = Wire.body(env, AudioStart.serializer()) ?: return error(link, env, Err.INVALID)
                val address = links.entries.firstOrNull { it.value === link }?.key ?: return
                audioCodec = Codecs.negotiate(req.codecs.ifEmpty { listOf(req.codec) })
                if (!status().caps.callAudio) {
                    val reason = if (android.os.Build.VERSION.SDK_INT < 33) AudioReasons.ANDROID_VERSION else AudioReasons.NOT_SET_UP
                    return sendAudioState(address, req.callId, "failed", reason)
                }
                if (liveCalls[req.callId] == null) return sendAudioState(address, req.callId, "failed", AudioReasons.NO_CALL)
                // One audio client at a time: the newest request takes the call's audio.
                if (audioWanted != null && audioWanted?.second != address) stopAudio()
                audioWanted = req.callId to address
                sendAudioState(address, req.callId, "starting")
                maybeStartAudio()
            }
            T.AUDIO_STOP -> {
                val req = Wire.body(env, AudioStop.serializer()) ?: return
                if (audioWanted?.first == req.callId) stopAudio()
            }
            T.UNPAIR -> {
                paired.remove(client.id)
                link.close(CloseReason.NORMAL)
                publish()
            }
            else -> error(link, env, Err.UNSUPPORTED)
        }
    }

    private fun validSub(subId: Int?) = subId == null || telephony.sims().any { it.subId == subId }

    private fun broadcast(msg: String) = links.values.filter { it.isEstablished }.forEach { runCatching { it.send(msg) } }

    private fun broadcastStatus() {
        if (links.values.none { it.isEstablished }) return
        io.execute { val st = status(); handler.post { broadcast(Wire.encode(T.STATUS, GatewayStatus.serializer(), st)) } }
    }

    // ------------------------------------------------------------------ live updates

    private val smsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            io.execute {
                val items = telephony.readSms(lastSmsId, limit = 200)
                handler.post {
                    for (m in items) {
                        if (m.id <= lastSmsId) continue
                        lastSmsId = m.id
                        val cmid = if (m.dir == Direction.OUT) matchOutgoing(m.address, m.body) else null
                        broadcast(Wire.encode(T.SMS_NEW, SmsNew.serializer(), SmsNew(m.copy(cmid = cmid))))
                    }
                }
            }
        }
    }

    private fun matchOutgoing(address: String, body: String): String? {
        val now = System.currentTimeMillis()
        outgoing.removeAll { now - it.third > 5 * 60_000L }
        val key = numberKey(address) + "|" + body.hashCode()
        val hit = outgoing.firstOrNull { it.second == key } ?: return null
        outgoing.remove(hit)
        return hit.first
    }

    private val callLogObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            io.execute {
                val items = telephony.readCalls(lastCallId, limit = 50)
                handler.post {
                    for (c in items) {
                        if (c.id <= lastCallId) continue
                        lastCallId = c.id
                        broadcast(Wire.encode(T.CALLS_NEW, CallsNew.serializer(), CallsNew(c)))
                    }
                }
            }
        }
    }

    /** Call state from the system broadcast (needs READ_PHONE_STATE; the number also needs READ_CALL_LOG). */
    private val phoneStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
            val state = i.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val number = i.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            onPhoneState(state, number)
        }
    }

    private fun onRegistryChanged() {
        val reg = studio.virgule.pol.android.gateway.telecom.CallRegistry
        liveCalls.clear()
        if (reg.bound) for (c in reg.snapshot { call -> telephony.subIdFor(call.details.accountHandle) }) {
            if (c.state != LiveCallState.ENDED) liveCalls[c.callId] = c
        }
        if (audioWanted != null && liveCalls[audioWanted!!.first] == null) stopAudio()
        broadcast(Wire.encode(T.CALL_STATE, CallStates.serializer(), CallStates(liveCalls.values.toList())))
        maybeStartAudio()
    }

    private fun onPhoneState(state: String, number: String?) {
        // As the default phone app, Telecom's Call objects are exact; the broadcast is only the fallback.
        if (studio.virgule.pol.android.gateway.telecom.CallRegistry.bound) return
        val now = System.currentTimeMillis()
        // The broadcast arrives twice when the app may read the number; only the copy with the number adds info.
        if (state == lastPhoneState && number == null) return
        lastPhoneState = state
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                val existing = liveCalls.values.firstOrNull { it.state == LiveCallState.RINGING }
                if (existing != null) {
                    if (number != null && existing.number.isEmpty()) liveCalls[existing.callId] = existing.copy(number = Validate.displayText(number, 40))
                } else {
                    val id = "c${++callCounter}"
                    liveCalls[id] = LiveCall(id, Validate.displayText(number, 40), null, LiveCallState.RINGING, incoming = true)
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                val ringing = liveCalls.values.firstOrNull { it.state == LiveCallState.RINGING }
                if (ringing != null) {
                    // Answered (a waiting call: the previous one is now held by the system).
                    liveCalls.values.filter { it.state == LiveCallState.ACTIVE }.forEach { liveCalls[it.callId] = it.copy(state = LiveCallState.HOLDING) }
                    liveCalls[ringing.callId] = ringing.copy(state = LiveCallState.ACTIVE, activeSince = now)
                } else if (liveCalls.values.none { it.state == LiveCallState.ACTIVE }) {
                    val dialed = lastDialed?.takeIf { now - it.second < 30_000 }?.first.orEmpty()
                    val id = "c${++callCounter}"
                    // Without the default-dialer role the app cannot see when the far end answers: the timer
                    // starts when the phone goes off-hook, and the UI labels it "Call in progress".
                    liveCalls[id] = LiveCall(id, dialed, null, LiveCallState.ACTIVE, incoming = false, activeSince = now)
                }
            }
            TelephonyManager.EXTRA_STATE_IDLE -> { liveCalls.clear(); if (audioWanted != null) stopAudio() }
        }
        broadcast(Wire.encode(T.CALL_STATE, CallStates.serializer(), CallStates(liveCalls.values.toList())))
        maybeStartAudio()
    }

    private var observersRegistered = false

    private fun registerObservers() {
        if (observersRegistered) return
        observersRegistered = true
        val cr = ctx.contentResolver
        if (settings.messages && telephony.has(Manifest.permission.READ_SMS)) {
            cr.registerContentObserver(android.provider.Telephony.Sms.CONTENT_URI, true, smsObserver)
        }
        if (settings.recents && telephony.has(Manifest.permission.READ_CALL_LOG)) {
            cr.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        }
        if (settings.calls && telephony.has(Manifest.permission.READ_PHONE_STATE)) {
            val f = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            // Protected system broadcast: delivered to a non-exported receiver, and no other app can spoof it.
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(phoneStateReceiver, f, null, handler, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(phoneStateReceiver, f, null, handler)
        }
    }

    private fun unregisterObservers() {
        if (!observersRegistered) return
        observersRegistered = false
        runCatching { ctx.contentResolver.unregisterContentObserver(smsObserver) }
        runCatching { ctx.contentResolver.unregisterContentObserver(callLogObserver) }
        runCatching { ctx.unregisterReceiver(phoneStateReceiver) }
    }

    /** Settings changed in the UI: re-evaluate observers and tell clients the new capabilities. */
    fun onSettingsChanged() = handler.post {
        if (settings.callAudio) audio.start() else { stopAudio(); audio.shutdown() }
        unregisterObservers()
        registerObservers()
        broadcastStatus()
    }

    private fun publish(running: Boolean = GatewayState.ui.value.running) {
        val names = links.values.filter { it.isEstablished }.mapNotNull { it.client?.name }
        GatewayState.mutable.update {
            it.copy(
                running = running,
                bluetoothOn = gatt.bluetoothOn,
                pairingOpenUntil = if (pairing.isOpen) System.currentTimeMillis() + pairing.remainingMs else 0,
                connected = names,
                paired = paired.list(),
                identityFingerprint = studio.virgule.pol.core.crypto.fingerprint(identity.publicKey),
                audioSetup = if (settings.callAudio) audio.setup.name else "",
            )
        }
    }
}

/** Fixed-window per-minute limits, as defence in depth against a misbehaving paired client. */
class RateLimiter {
    private val windows = HashMap<String, Pair<Long, Int>>()
    fun allow(key: String, perMinute: Int, now: Long = System.currentTimeMillis()): Boolean {
        val (start, n) = windows[key] ?: (now to 0)
        val (s, c) = if (now - start > 60_000) now to 0 else start to n
        if (c >= perMinute) return false
        windows[key] = s to c + 1
        return true
    }
}
