package studio.virgule.pol.core.proto

import studio.virgule.pol.core.domain.CallEntry
import studio.virgule.pol.core.domain.Contact
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.GatewayStatus
import studio.virgule.pol.core.domain.LiveCall
import studio.virgule.pol.core.domain.Sms
import studio.virgule.pol.core.link.Limits
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Control messages carried inside sealed records (channel 0x00). See protocol/PROTOCOL.md §5.
 *
 * Envelope: `{"t": type, "id": n?, "re": n?, "b": {...}}`. Unknown types and unknown fields are ignored so that
 * newer gateways and older clients keep working together.
 */
@Serializable
data class Envelope(val t: String, val id: Long? = null, val re: Long? = null, val b: JsonElement = JsonObject(emptyMap()))

object T {
    const val STATUS = "status"
    const val SYNC = "sync"
    const val SMS_PAGE = "sms.page"
    const val SMS_NEW = "sms.new"
    const val SMS_SEND = "sms.send"
    const val SMS_ACK = "sms.ack"
    const val SMS_READ = "sms.read"
    const val CALLS_PAGE = "calls.page"
    const val CALLS_NEW = "calls.new"
    const val CONTACTS_PAGE = "contacts.page"
    const val CALL_DIAL = "call.dial"
    const val CALL_ACK = "call.ack"
    const val CALL_STATE = "call.state"
    const val CALL_ACTION = "call.action"
    const val CALL_DTMF = "call.dtmf"
    const val USSD_SEND = "ussd.send"
    const val USSD_RESULT = "ussd.result"
    const val PING = "ping"
    const val PONG = "pong"
    const val UNPAIR = "unpair"
    const val AUDIO_START = "audio.start"
    const val AUDIO_STOP = "audio.stop"
    const val AUDIO_STATE = "audio.state"
    const val ERROR = "error"
}

@Serializable data class SyncReq(val smsSince: Long = 0, val callsSince: Long = 0, val contacts: Boolean = true, val client: String = "")
@Serializable data class SmsPage(val items: List<Sms>, val more: Boolean)
@Serializable data class SmsNew(val item: Sms)
@Serializable data class SmsSend(val cmid: String, val to: String, val body: String, val subId: Int? = null)
@Serializable data class SmsAck(val cmid: String, val state: DeliveryState, val err: String? = null)
@Serializable data class SmsRead(val thread: Long)
@Serializable data class CallsPage(val items: List<CallEntry>, val more: Boolean)
@Serializable data class CallsNew(val item: CallEntry)
@Serializable data class ContactsPage(val items: List<Contact>, val more: Boolean, val first: Boolean)
@Serializable data class CallDial(val cmid: String, val number: String, val subId: Int? = null)
@Serializable data class CallAck(val cmid: String, val ok: Boolean, val err: String? = null)
@Serializable data class CallStates(val calls: List<LiveCall>)
@Serializable data class CallAction(val callId: String, val action: String)
@Serializable data class CallDtmf(val callId: String, val digits: String)
@Serializable data class UssdSend(val cmid: String, val code: String, val subId: Int? = null)
@Serializable data class UssdResult(val cmid: String, val ok: Boolean, val text: String? = null, val err: String? = null)
@Serializable data class Ping(val ts: Long)
/** Client asks to carry [callId]'s audio (once it is active). */
@Serializable data class AudioStart(val callId: String, val codec: String = "pcmu", val codecs: List<String> = emptyList())
@Serializable data class AudioStop(val callId: String)
/** Gateway's bridge state: "starting" | "active" | "stopped" | "failed"; [reason] from [AudioReasons]. */
@Serializable data class AudioState(val callId: String, val state: String, val reason: String? = null, val codec: String? = null, val rate: Int? = null)
@Serializable data class ErrorBody(val code: String, val msg: String = "")
@Serializable class Empty

object CallActions {
    const val ANSWER = "answer"
    const val REJECT = "reject"
    const val END = "end"
    const val HOLD = "hold"
    const val UNHOLD = "unhold"
    val all = setOf(ANSWER, REJECT, END, HOLD, UNHOLD)
}

object AudioReasons {
    const val NOT_SET_UP = "not_set_up"            // helper (Shizuku) not running or not allowed
    const val ANDROID_VERSION = "android_version"  // gateway older than Android 13
    const val NOT_INTERCEPTABLE = "not_interceptable" // this phone's audio system refuses call-audio interception
    const val NO_CALL = "no_call"
    const val CODEC = "codec"
    const val HELPER_FAILED = "helper_failed"
}

/** Stable error codes; clients map them to localized, actionable text. */
object Err {
    const val UNSUPPORTED = "unsupported"
    const val PERMISSION = "permission"
    const val INVALID = "invalid"
    const val NO_SERVICE = "no_service"
    const val FAILED = "failed"
    const val BUSY = "busy"
}

object Wire {
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        coerceInputValues = true
    }

    fun <B> encode(type: String, ser: KSerializer<B>, body: B, id: Long? = null, re: Long? = null): String {
        val s = json.encodeToString(Envelope.serializer(), Envelope(type, id, re, json.encodeToJsonElement(ser, body)))
        require(s.toByteArray(Charsets.UTF_8).size <= Limits.MAX_MESSAGE) { "message too large: $type" }
        return s
    }

    fun encodeEmpty(type: String, id: Long? = null, re: Long? = null) = encode(type, Empty.serializer(), Empty(), id, re)

    /** Returns null for anything that is not a well-formed, size-bounded envelope. */
    fun decode(text: String): Envelope? {
        if (text.length > Limits.MAX_MESSAGE) return null
        return runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()
            ?.takeIf { it.t.length in 1..32 }
    }

    /** Decodes a body; null when it does not match the schema. Callers must still run [Validate] checks. */
    fun <B> body(env: Envelope, ser: KSerializer<B>): B? = runCatching { json.decodeFromJsonElement(ser, env.b) }.getOrNull()

    /**
     * Splits [items] into pages whose encoded message stays below the size limit. Items that cannot fit alone are
     * dropped (they cannot be represented) rather than breaking the session.
     */
    fun <I> pages(items: List<I>, maxItems: Int, encodePage: (List<I>, Boolean) -> String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        var lastMore = false
        while (i < items.size) {
            var n = minOf(maxItems, items.size - i)
            var msg: String? = null
            while (n > 0) {
                msg = runCatching { encodePage(items.subList(i, i + n), i + n < items.size) }.getOrNull()
                if (msg != null) break
                n /= 2
            }
            if (msg == null) { i++; continue } // a single item too large to send: skip it
            out += msg
            i += n
            lastMore = i < items.size
        }
        if (out.isEmpty() || lastMore) out += encodePage(emptyList(), false)
        return out
    }
}
