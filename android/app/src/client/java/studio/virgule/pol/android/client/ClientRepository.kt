package studio.virgule.pol.android.client

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import studio.virgule.pol.android.common.KeystoreIdentity
import studio.virgule.pol.android.common.SafeLog
import studio.virgule.pol.android.common.SecureStore
import studio.virgule.pol.core.client.ClientModel
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.LinkState
import studio.virgule.pol.core.demo.DemoGateway
import studio.virgule.pol.core.link.ClientLink
import studio.virgule.pol.core.link.CloseReason
import studio.virgule.pol.core.link.LinkTransport
import studio.virgule.pol.core.link.TrustedPeer
import studio.virgule.pol.core.proto.T
import studio.virgule.pol.core.proto.Wire
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/** Pairing as the user walks through it. */
sealed interface PairingUi {
    data object Idle : PairingUi
    data class Searching(val found: List<String>) : PairingUi
    data object Connecting : PairingUi
    data class Compare(val sas: String, val gatewayName: String, val fingerprint: String, val waitingForGateway: Boolean) : PairingUi
    data class Done(val gatewayName: String) : PairingUi
    data class Failed(val reason: CloseReason) : PairingUi
}

/**
 * The Android client's single source of truth. Owns the Bluetooth connection (or the demo gateway), the link
 * engine and [ClientModel], and exposes [state] for the UI. Data lives in memory only.
 */
class ClientRepository private constructor(private val ctx: Context) {
    private val thread = HandlerThread("pol-client").apply { start() }
    val handler = Handler(thread.looper)
    private val store = SecureStore(ctx)
    private val prefs = ctx.getSharedPreferences("client_settings", Context.MODE_PRIVATE)
    private val identity by lazy { KeystoreIdentity.load() }
    private val ble = BleCentral(ctx, handler)

    private val _state = MutableStateFlow(ClientSnapshot())
    val state: StateFlow<ClientSnapshot> = _state
    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    val pairing: StateFlow<PairingUi> = _pairing

    private var link: ClientLink? = null
    private var connection: BleCentral.Connection? = null
    private var demo: DemoGateway? = null
    private var running = false
    private var attempt = 0
    private val notOurs = HashMap<String, Long>() // address -> until
    private val pairingCandidates = LinkedHashMap<String, BleCentral.Found>()

    val model = ClientModel(send = ::sendToLink, onChange = { snap ->
        _state.value = snap
        ClientNotifications.onSnapshot(ctx, snap, notificationsEnabled, showPreviews)
    }, clientName = deviceName())

    /** Gateway calls as Telecom calls on this phone, with their audio ("Take calls on this phone"). */
    val calls = ClientCalls(ctx, handler, send = { json -> sendToLink(json) }, sendAudio = { frame ->
        handler.post { link?.takeIf { it.isEstablished }?.let { l -> runCatching { l.sendAudio(frame) } } }
    }).also { ClientCalls.current = it }

    var callsOnThisPhone: Boolean
        get() = prefs.getBoolean("callsHere", false) && calls.enabled()
        set(v) { prefs.edit().putBoolean("callsHere", v).apply(); if (v) calls.register() else calls.unregister() }

    /** Calls ring and are talked through here: needs the setting, the gateway's audio helper, and a live link. */
    val bridged: Boolean get() = callsOnThisPhone && !demoMode && _state.value.link == LinkState.CONNECTED && _state.value.status?.caps?.callAudio == true

    var gateway: TrustedPeer? = loadGateway()
        private set

    var demoMode: Boolean
        get() = prefs.getBoolean("demo", false)
        private set(v) { prefs.edit().putBoolean("demo", v).apply() }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean("notify", false)
        set(v) { prefs.edit().putBoolean("notify", v).apply() }

    var showPreviews: Boolean
        get() = prefs.getBoolean("previews", false)
        set(v) { prefs.edit().putBoolean("previews", v).apply() }

    init {
        if (prefs.getBoolean("callsHere", false) && calls.enabled()) calls.register()
        handler.post {
            if (demoMode) enterDemo() else model.setLink(if (gateway == null) LinkState.NOT_PAIRED else LinkState.SEARCHING, demo = false)
        }
    }

    fun hasBluetoothPermissions(): Boolean = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun deviceName() = (Build.MODEL ?: "Android phone").take(40)

    private fun sendToLink(json: String): Boolean {
        demo?.let { d -> handler.post { d.receive(json) }; return true }
        val l = link ?: return false
        if (!l.isEstablished) return false
        return runCatching { l.send(json) }.isSuccess
    }

    // ---------------------------------------------------------------- demo

    /** Demo mode: the UI talks to an in-memory simulated gateway. No Bluetooth, no telephony, no network. */
    fun enterDemo() = handler.post {
        stopLink()
        demoMode = true
        model.reset(LinkState.CONNECTED)
        demo = DemoGateway({ json -> handler.post { model.onMessage(json) } })
        model.setLink(LinkState.CONNECTED, demo = true)
        model.onSessionUp()
    }

    fun exitDemo() = handler.post {
        demo = null
        demoMode = false
        model.reset(if (gateway == null) LinkState.NOT_PAIRED else LinkState.SEARCHING)
        if (gateway != null) startLink()
    }

    fun simulateIncomingCall() = handler.post { demo?.simulateIncomingCall() }
    fun simulateIncomingSms() = handler.post { demo?.simulateIncomingSms() }

    // ---------------------------------------------------------------- connection

    /** Starts (or keeps) the connection to the paired gateway, reconnecting with backoff. */
    fun startLink() = handler.post {
        if (demo != null || gateway == null || running) return@post
        if (!hasBluetoothPermissions()) { model.setLink(LinkState.SEARCHING); return@post }
        running = true
        attempt = 0
        scanForGateway()
    }

    fun stopLink() {
        running = false
        handler.removeCallbacks(retry)
        ble.stopScan()
        link?.close(); link = null
        connection?.close(); connection = null
    }

    private val retry = Runnable { if (running) scanForGateway() }

    private fun scheduleRetry() {
        val delay = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)[attempt.coerceAtMost(4)]
        attempt++
        handler.removeCallbacks(retry)
        handler.postDelayed(retry, delay)
    }

    private fun scanForGateway() {
        if (!running || connection != null) return
        if (!ble.bluetoothOn) { model.setLink(LinkState.BLUETOOTH_OFF); scheduleRetry(); return }
        model.setLink(if (_state.value.status == null) LinkState.SEARCHING else LinkState.RECONNECTING)
        val started = ble.startScan(lowPower = attempt > 3) { found ->
            val now = System.currentTimeMillis()
            if (connection != null || (notOurs[found.device.address] ?: 0) > now) return@startScan
            ble.stopScan()
            connectResume(found)
        }
        if (!started) scheduleRetry()
        // Restart the scan periodically: Android throttles long-running scans.
        handler.postDelayed({ if (running && connection == null) { ble.stopScan(); scheduleRetry() } }, 25_000)
    }

    private fun connectResume(found: BleCentral.Found) {
        val gw = gateway ?: return
        model.setLink(LinkState.CONNECTING)
        val conn = ble.Connection(found.device, object : BleCentral.ConnectionListener {
            override fun onReady(transport: LinkTransport) {
                val l = ClientLink.resume(identity, transport, gw, object : ClientLink.Listener {
                    override fun onEstablished() { handler.post { attempt = 0; model.onSessionUp() } }
                    override fun onMessage(json: String) { handler.post { onLinkMessage(json) } }
                    override fun onAudio(frame: ByteArray) { calls.onAudioFrame(frame) }
                    override fun onClosed(reason: CloseReason, byPeer: Boolean) {
                        handler.post {
                            calls.linkLost()
                            if (reason == CloseReason.UNKNOWN_DEVICE || reason == CloseReason.AUTH_FAILED) {
                                // Another person's gateway, or ours after it removed this phone.
                                notOurs[found.device.address] = System.currentTimeMillis() + 10 * 60_000L
                            }
                        }
                    }
                })
                link = l
                l.start()
                handler.post(tick)
            }
            override fun onBytes(bytes: ByteArray) { link?.onBytes(bytes) }
            override fun onDisconnected() {
                link?.onTransportClosed(); link = null; connection = null
                calls.linkLost()
                if (running) { model.setLink(LinkState.RECONNECTING); scheduleRetry() }
            }
        })
        connection = conn
        conn.connect()
    }

    private val tick = object : Runnable {
        private var n = 0
        override fun run() {
            val l = link ?: return
            l.tick()
            model.expirePending()
            if (++n % 12 == 0) model.ping() // ~ every 60 s
            handler.postDelayed(this, 5_000)
        }
    }

    private fun onLinkMessage(json: String) {
        val env = Wire.decode(json) ?: return
        if (env.t == T.UNPAIR) { forgetGateway(notifyGateway = false); return }
        if (env.t == T.AUDIO_STATE) { Wire.body(env, studio.virgule.pol.core.proto.AudioState.serializer())?.let(calls::onAudioState); return }
        model.onMessage(json)
        if (env.t == T.CALL_STATE || env.t == T.STATUS) calls.update(model.snapshot.liveCalls, bridged)
    }

    // ---------------------------------------------------------------- calls

    /** Dial from the UI: through Telecom when bridged (so the call has audio here), else just on the gateway. */
    fun dial(number: String, subId: Int?): ClientModel.ActionResult {
        val n = studio.virgule.pol.core.proto.Validate.dialNumber(number) ?: return ClientModel.ActionResult.Invalid
        if (bridged) { pendingSub = subId; return if (calls.placeCall(n)) ClientModel.ActionResult.Ok else model.dial(n, subId) }
        return model.dial(n, subId)
    }

    private var pendingSub: Int? = null

    /** Telecom created the outgoing connection: now ask the gateway to place the call. */
    fun placeOnGateway(number: String) { model.dial(number, pendingSub ?: _state.value.status?.defaultVoiceSub) }

    fun answer(callId: String): ClientModel.ActionResult {
        calls.connection(callId)?.let { it.onAnswer(); return ClientModel.ActionResult.Ok }
        return model.callAction(callId, studio.virgule.pol.core.proto.CallActions.ANSWER)
    }

    fun end(callId: String, ringing: Boolean): ClientModel.ActionResult {
        calls.connection(callId)?.let { if (ringing) it.onReject() else it.onDisconnect(); return ClientModel.ActionResult.Ok }
        return model.callAction(callId, if (ringing) studio.virgule.pol.core.proto.CallActions.REJECT else studio.virgule.pol.core.proto.CallActions.END)
    }

    // ---------------------------------------------------------------- pairing

    fun startPairing() = handler.post {
        stopLink()
        pairingCandidates.clear()
        _pairing.value = PairingUi.Searching(emptyList())
        if (!ble.startScan(lowPower = false) { f ->
                if (f.pairing) {
                    pairingCandidates[f.device.address] = f
                    _pairing.value = PairingUi.Searching(pairingCandidates.keys.toList())
                    // Exactly one gateway in pairing mode nearby: connect to it. More than one: the code
                    // comparison still binds the right one, but ask the owner to close the other window.
                    handler.postDelayed({ if (_pairing.value is PairingUi.Searching && pairingCandidates.size == 1) connectPair(pairingCandidates.values.first()) }, 1500)
                }
            }) _pairing.value = PairingUi.Failed(CloseReason.TRANSPORT)
        handler.postDelayed({ if (_pairing.value is PairingUi.Searching) { ble.stopScan(); _pairing.value = PairingUi.Failed(CloseReason.TIMEOUT) } }, 60_000)
    }

    private fun connectPair(found: BleCentral.Found) {
        ble.stopScan()
        _pairing.value = PairingUi.Connecting
        val conn = ble.Connection(found.device, object : BleCentral.ConnectionListener {
            override fun onReady(transport: LinkTransport) {
                val l = ClientLink.pair(identity, transport, deviceName(), object : ClientLink.Listener {
                    override fun onSasReady(sas: String, gatewayName: String, gatewayFingerprint: String) {
                        _pairing.value = PairingUi.Compare(sas, gatewayName, gatewayFingerprint, waitingForGateway = false)
                    }
                    override fun onPaired(gateway: TrustedPeer) { handler.post { saveGateway(gateway); _pairing.value = PairingUi.Done(gateway.name) } }
                    override fun onEstablished() { handler.post { running = true; attempt = 0; model.reset(LinkState.CONNECTED); model.onSessionUp(); handler.post(tick) } }
                    override fun onMessage(json: String) { handler.post { onLinkMessage(json) } }
                    override fun onAudio(frame: ByteArray) { calls.onAudioFrame(frame) }
                    override fun onClosed(reason: CloseReason, byPeer: Boolean) {
                        handler.post { calls.linkLost(); if (_pairing.value !is PairingUi.Done) _pairing.value = PairingUi.Failed(reason) }
                    }
                })
                link = l
                l.start()
                handler.post(tick)
            }
            override fun onBytes(bytes: ByteArray) { link?.onBytes(bytes) }
            override fun onDisconnected() {
                link?.onTransportClosed(); link = null; connection = null
                if (running && gateway != null) { model.setLink(LinkState.RECONNECTING); scheduleRetry() }
            }
        })
        connection = conn
        conn.connect()
    }

    /** Debug screenshots only: show the code-comparison step without a gateway. */
    fun debugPairingPreview() { _pairing.value = PairingUi.Compare("974705", "Pixel 8", "6BCD-A99D-58E5", waitingForGateway = false) }

    fun confirmSas() = handler.post {
        link?.confirmSas()
        (_pairing.value as? PairingUi.Compare)?.let { if (_pairing.value !is PairingUi.Done) _pairing.value = it.copy(waitingForGateway = true) }
    }

    fun declineSas() = handler.post { link?.declineSas() }

    fun cancelPairing() = handler.post {
        ble.stopScan()
        if (_pairing.value !is PairingUi.Done) link?.close()
        _pairing.value = PairingUi.Idle
        if (gateway != null) startLink()
    }

    // ---------------------------------------------------------------- storage

    @Serializable private data class StoredGateway(val id: String, val key: String, val name: String, val pairedAt: Long)

    private fun saveGateway(peer: TrustedPeer) {
        val e = Base64.getEncoder()
        store.put("gateway", Json.encodeToString(StoredGateway.serializer(), StoredGateway(e.encodeToString(peer.id), e.encodeToString(peer.publicKey), peer.name, peer.pairedAtMs)).toByteArray())
        gateway = peer
    }

    private fun loadGateway(): TrustedPeer? = store.get("gateway")?.let { raw ->
        runCatching {
            val g = Json.decodeFromString(StoredGateway.serializer(), String(raw))
            val d = Base64.getDecoder()
            TrustedPeer(d.decode(g.id), d.decode(g.key), g.name, g.pairedAt)
        }.getOrNull()
    }

    /** Unpairs: tells the gateway (if connected) to drop this phone, then deletes the pairing locally. */
    fun forgetGateway(notifyGateway: Boolean = true) = handler.post {
        if (notifyGateway) link?.takeIf { it.isEstablished }?.let { l -> runCatching { l.send(Wire.encodeEmpty(T.UNPAIR)) } }
        stopLink()
        store.remove("gateway")
        gateway = null
        model.reset(LinkState.NOT_PAIRED)
        SafeLog.d("client", "pairing removed")
    }

    /** Deletes everything this app stored: pairing, identity key, settings, and in-memory data. */
    fun eraseAll() = handler.post {
        forgetGateway()
        handler.post {
            store.wipe()
            KeystoreIdentity.delete()
            prefs.edit().clear().apply()
            demo = null
            model.reset(LinkState.NOT_PAIRED)
        }
    }

    companion object {
        @Volatile private var instance: ClientRepository? = null
        fun get(ctx: Context): ClientRepository = instance ?: synchronized(this) {
            instance ?: ClientRepository(ctx.applicationContext).also { instance = it }
        }
    }
}
