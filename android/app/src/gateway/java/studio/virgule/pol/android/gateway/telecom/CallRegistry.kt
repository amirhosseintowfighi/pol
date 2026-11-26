package studio.virgule.pol.android.gateway.telecom

import android.os.Build
import android.telecom.Call
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import studio.virgule.pol.core.domain.LiveCall
import studio.virgule.pol.core.domain.LiveCallState
import studio.virgule.pol.core.proto.Validate

/**
 * The calls Android's Telecom hands to Pol while it is the default phone app ("full call control").
 * With real [Call] objects the gateway knows exact states (dialing → active when the far end answers), and can
 * hold, resume, swap and send keypad tones, which the plain permission-based mode cannot.
 *
 * Main-thread only (InCallService callbacks and the listeners run on the main looper).
 */
object CallRegistry {
    fun interface Listener { fun onCallsChanged() }

    private val ids = LinkedHashMap<Call, String>()
    private val activeSince = HashMap<Call, Long>()
    private var counter = 0
    private val listeners = LinkedHashSet<Listener>()

    /** True while Telecom has bound our InCallService (we are the default dialer and a call exists). */
    var bound = false
        internal set

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it.onCallsChanged() }

    internal fun add(call: Call) {
        ids[call] = "t${++counter}"
        call.registerCallback(callback)
        if (stateOf(call) == Call.STATE_ACTIVE) activeSince[call] = System.currentTimeMillis()
        changed()
    }

    internal fun remove(call: Call) {
        call.unregisterCallback(callback)
        ids.remove(call); activeSince.remove(call)
        changed()
    }

    internal fun clear() { ids.keys.toList().forEach { it.unregisterCallback(callback) }; ids.clear(); activeSince.clear(); changed() }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            if (state == Call.STATE_ACTIVE && call !in activeSince) activeSince[call] = System.currentTimeMillis()
            changed()
        }
        override fun onDetailsChanged(call: Call, details: Call.Details) = changed()
    }

    fun calls(): List<Pair<Call, String>> = ids.entries.map { it.key to it.value }

    fun snapshot(subIdOf: (Call) -> Int?): List<LiveCall> = ids.entries.map { (call, id) ->
        val d = call.details
        LiveCall(
            callId = id,
            number = Validate.displayText(d.handle?.schemeSpecificPart, 40),
            name = d.callerDisplayName?.takeIf { it.isNotBlank() }?.let { Validate.displayText(it, 100) }
                ?: d.contactDisplayNameCompat(),
            state = when (stateOf(call)) {
                Call.STATE_RINGING -> LiveCallState.RINGING
                Call.STATE_ACTIVE -> LiveCallState.ACTIVE
                Call.STATE_HOLDING -> LiveCallState.HOLDING
                Call.STATE_DISCONNECTED, Call.STATE_DISCONNECTING -> LiveCallState.ENDED
                else -> LiveCallState.DIALING
            },
            incoming = if (Build.VERSION.SDK_INT >= 29) d.callDirection == Call.Details.DIRECTION_INCOMING else stateOf(call) == Call.STATE_RINGING,
            activeSince = activeSince[call],
            subId = subIdOf(call),
        )
    }

    private fun Call.Details.contactDisplayNameCompat(): String? =
        if (Build.VERSION.SDK_INT >= 30) contactDisplayName?.takeIf { it.isNotBlank() }?.let { Validate.displayText(it, 100) } else null

    @Suppress("DEPRECATION")
    fun stateOf(call: Call): Int = if (Build.VERSION.SDK_INT >= 31) call.details.state else call.state

    fun find(id: String): Call? = ids.entries.firstOrNull { it.value == id }?.key

    /** Returns true if Telecom accepted the request. */
    fun perform(id: String, action: String): Boolean {
        val call = find(id) ?: return false
        when (action) {
            "answer" -> call.answer(VideoProfile.STATE_AUDIO_ONLY)
            "reject" -> call.reject(false, null)
            "end" -> call.disconnect()
            "hold" -> call.hold()
            "unhold" -> {
                // Resuming one call holds the other, as on any phone.
                ids.keys.filter { it != call && stateOf(it) == Call.STATE_ACTIVE }.forEach { it.hold() }
                call.unhold()
            }
            else -> return false
        }
        return true
    }

    fun dtmf(id: String, digit: Char): Boolean {
        val call = find(id) ?: return false
        if (digit !in "0123456789*#") return false
        call.playDtmfTone(digit)
        call.stopDtmfTone()
        return true
    }

    fun isDefaultDialer(tm: TelecomManager, pkg: String) = runCatching { tm.defaultDialerPackage == pkg }.getOrDefault(false)
}
