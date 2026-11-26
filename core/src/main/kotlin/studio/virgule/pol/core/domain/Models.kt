package studio.virgule.pol.core.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data the gateway shares with its paired clients. Everything here travels only over the encrypted local link;
 * clients keep it in memory (see docs/PRIVACY.md) and never send it anywhere else.
 */

@Serializable
data class SimInfo(
    val slot: Int,
    val subId: Int,
    val label: String,
    val carrier: String = "",
    /** 0–4 bars, null when unknown. */
    val signal: Int? = null,
    /** "5G", "LTE", "3G", "2G" or null. */
    val network: String? = null,
    /** "in", "none", "emergency", "off". */
    val service: String = "in",
    val roaming: Boolean = false,
)

/**
 * What this gateway can really do. Clients show a feature as available only when its flag is true; everything
 * else is shown as unavailable with the reason from docs/COMPATIBILITY.md.
 */
@Serializable
data class Capabilities(
    val smsSend: Boolean = false,
    val smsRead: Boolean = false,
    val smsReceive: Boolean = false,
    val callPlace: Boolean = false,
    /** Answer / reject / end through ANSWER_PHONE_CALLS. */
    val callControl: Boolean = false,
    val callLog: Boolean = false,
    val contacts: Boolean = false,
    val ussd: Boolean = false,
    /** More than one SIM can be chosen per call / message. */
    val simChoice: Boolean = false,
    /**
     * Call audio carried to the client. Needs Android 13+ and the gateway's shell-privileged audio helper (started
     * through Shizuku / Wireless debugging), on phones whose audio system allows call-audio interception.
     */
    val callAudio: Boolean = false,
    /** Hold / resume / swap and exact call states: gateway is the default phone app ("full call control"). */
    val hold: Boolean = false,
    /** Keypad tones sent by the gateway's phone app into the call. */
    val dtmf: Boolean = false,
    val mute: Boolean = false,
    val speaker: Boolean = false,
)

@Serializable
data class GatewayStatus(
    val name: String,
    val appVersion: String = "",
    val battery: Int? = null,
    val charging: Boolean = false,
    val sims: List<SimInfo> = emptyList(),
    val defaultVoiceSub: Int? = null,
    val defaultSmsSub: Int? = null,
    val caps: Capabilities = Capabilities(),
    /** Android SDK level of the gateway, for compatibility messages. */
    val sdk: Int = 0,
    /** Call-audio bridge: "ready" | "needs_setup" | "android_version" | "not_interceptable" | "off". */
    val audio: String = "off",
)

@Serializable
enum class Direction { @SerialName("in") IN, @SerialName("out") OUT }

@Serializable
enum class DeliveryState {
    @SerialName("pending") PENDING,
    @SerialName("sent") SENT,
    @SerialName("delivered") DELIVERED,
    @SerialName("failed") FAILED,
    @SerialName("received") RECEIVED,
}

@Serializable
data class Sms(
    val id: Long,
    val thread: Long,
    val address: String,
    val body: String,
    val ts: Long,
    val dir: Direction,
    val read: Boolean = true,
    val subId: Int? = null,
    val state: DeliveryState = DeliveryState.RECEIVED,
    /** Client message id of an outgoing message sent from a client, to match its local "sending" bubble. */
    val cmid: String? = null,
)

@Serializable
enum class CallType {
    @SerialName("incoming") INCOMING,
    @SerialName("outgoing") OUTGOING,
    @SerialName("missed") MISSED,
    @SerialName("rejected") REJECTED,
    @SerialName("blocked") BLOCKED,
    @SerialName("other") OTHER,
}

@Serializable
data class CallEntry(
    val id: Long,
    val number: String,
    val ts: Long,
    val durationS: Long = 0,
    val type: CallType,
    val subId: Int? = null,
    val name: String? = null,
)

@Serializable
data class PhoneNumber(val number: String, val label: String? = null)

@Serializable
data class Contact(val id: Long, val name: String, val phones: List<PhoneNumber>, val starred: Boolean = false)

@Serializable
enum class LiveCallState {
    @SerialName("dialing") DIALING,
    @SerialName("ringing") RINGING,
    @SerialName("active") ACTIVE,
    @SerialName("holding") HOLDING,
    @SerialName("ended") ENDED,
}

@Serializable
data class LiveCall(
    val callId: String,
    val number: String,
    val name: String? = null,
    val state: LiveCallState,
    val incoming: Boolean,
    /** When the call became active (Unix ms), for the duration timer. */
    val activeSince: Long? = null,
    val subId: Int? = null,
)

/** A conversation as shown in the list, built on the client from [Sms] items. */
data class Conversation(
    val thread: Long,
    val address: String,
    val last: Sms,
    val unread: Int,
)

object Conversations {
    fun build(messages: Collection<Sms>): List<Conversation> =
        messages.groupBy { it.thread }.map { (thread, items) ->
            val last = items.maxBy { it.ts }
            Conversation(thread, last.address, last, items.count { it.dir == Direction.IN && !it.read })
        }.sortedByDescending { it.last.ts }

    fun search(messages: Collection<Sms>, query: String, names: (String) -> String?): List<Conversation> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return build(messages)
        val hits = messages.filter { m ->
            m.body.lowercase().contains(q) || m.address.contains(q) || (names(m.address)?.lowercase()?.contains(q) == true)
        }.map { it.thread }.toSet()
        return build(messages.filter { it.thread in hits })
    }
}
