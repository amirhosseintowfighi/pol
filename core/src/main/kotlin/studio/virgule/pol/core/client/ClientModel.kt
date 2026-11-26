package studio.virgule.pol.core.client

import studio.virgule.pol.core.domain.CallEntry
import studio.virgule.pol.core.domain.Contact
import studio.virgule.pol.core.domain.Conversation
import studio.virgule.pol.core.domain.Conversations
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.domain.GatewayStatus
import studio.virgule.pol.core.domain.LiveCall
import studio.virgule.pol.core.domain.LiveCallState
import studio.virgule.pol.core.domain.Sms
import studio.virgule.pol.core.proto.CallAck
import studio.virgule.pol.core.proto.CallAction
import studio.virgule.pol.core.proto.CallActions
import studio.virgule.pol.core.proto.CallDial
import studio.virgule.pol.core.proto.CallStates
import studio.virgule.pol.core.proto.CallsNew
import studio.virgule.pol.core.proto.CallsPage
import studio.virgule.pol.core.proto.ContactsPage
import studio.virgule.pol.core.proto.Envelope
import studio.virgule.pol.core.proto.ErrorBody
import studio.virgule.pol.core.proto.Ping
import studio.virgule.pol.core.proto.SmsAck
import studio.virgule.pol.core.proto.SmsNew
import studio.virgule.pol.core.proto.SmsPage
import studio.virgule.pol.core.proto.SmsRead
import studio.virgule.pol.core.proto.SmsSend
import studio.virgule.pol.core.proto.SyncReq
import studio.virgule.pol.core.proto.T
import studio.virgule.pol.core.proto.UssdResult
import studio.virgule.pol.core.proto.UssdSend
import studio.virgule.pol.core.proto.Validate
import studio.virgule.pol.core.proto.Wire
import java.util.UUID

/** Connection as the user sees it. */
enum class LinkState { NOT_PAIRED, BLUETOOTH_OFF, SEARCHING, CONNECTING, CONNECTED, RECONNECTING }

data class UssdReply(val cmid: String, val code: String, val pending: Boolean, val ok: Boolean = false, val text: String? = null, val err: String? = null)

/** Immutable snapshot rendered by the UI. All data is held in memory only and is gone when the app stops. */
data class ClientSnapshot(
    val link: LinkState = LinkState.NOT_PAIRED,
    val demo: Boolean = false,
    val status: GatewayStatus? = null,
    val messages: Map<String, Sms> = emptyMap(),
    val calls: List<CallEntry> = emptyList(),
    val contacts: List<Contact> = emptyList(),
    val liveCalls: List<LiveCall> = emptyList(),
    val syncing: Boolean = false,
    val ussd: UssdReply? = null,
    val lastError: String? = null,
    val pingMs: Long? = null,
) {
    val conversations: List<Conversation> get() = Conversations.build(messages.values)
    fun thread(thread: Long): List<Sms> = messages.values.filter { it.thread == thread }.sortedBy { it.ts }
    fun nameFor(number: String): String? {
        val key = numberKey(number)
        if (key.isEmpty()) return null
        return contacts.firstOrNull { c -> c.phones.any { numberKey(it.number) == key } }?.name
    }
    val missedCalls: Int get() = calls.count { it.type == studio.virgule.pol.core.domain.CallType.MISSED }
}

/** Last 9 digits: matches "+98 912 …", "0912 …" and "912 …" as the same person without a full numbering plan. */
fun numberKey(number: String): String = number.filter { it.isDigit() }.takeLast(9)

/**
 * Client-side protocol logic shared by the Android client (and mirrored by the Swift client). It turns gateway
 * messages into [ClientSnapshot]s and user actions into validated requests. It has no I/O of its own: [send]
 * delivers a JSON message to the encrypted link (or to the in-memory demo gateway).
 */
class ClientModel(
    private val send: (String) -> Boolean,
    private val onChange: (ClientSnapshot) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val clientName: String = "",
) {
    @Volatile var snapshot = ClientSnapshot()
        private set
    private var nextId = 1L
    private val pendingSends = HashMap<String, Pair<SmsSend, Long>>()
    private var pendingLocalId = -1L
    private var contactsBuffer = ArrayList<Contact>()

    @Synchronized
    private fun update(f: (ClientSnapshot) -> ClientSnapshot) {
        snapshot = f(snapshot)
        onChange(snapshot)
    }

    fun setLink(state: LinkState, demo: Boolean = snapshot.demo) = update { it.copy(link = state, demo = demo) }

    /** Call after each (re)established session. Asks only for what is newer than what is already in memory. */
    fun onSessionUp() {
        val s = snapshot
        val smsSince = s.messages.values.filter { it.id > 0 }.maxOfOrNull { it.id } ?: 0
        val callsSince = s.calls.maxOfOrNull { it.id } ?: 0
        update { it.copy(link = LinkState.CONNECTED, syncing = true, lastError = null) }
        send(Wire.encode(T.SYNC, SyncReq.serializer(), SyncReq(smsSince, callsSince, contacts = true, client = clientName), id = nextId++))
        // Re-send messages that never got an answer; the gateway drops duplicates by cmid, so nothing is sent twice.
        for ((req, _) in pendingSends.values.toList()) send(Wire.encode(T.SMS_SEND, SmsSend.serializer(), req, id = nextId++))
    }

    fun onMessage(json: String) {
        val env = Wire.decode(json) ?: return
        handle(env)
    }

    private fun handle(env: Envelope) {
        when (env.t) {
            T.STATUS -> Wire.body(env, GatewayStatus.serializer())?.let { st -> update { it.copy(status = st) } }
            T.SMS_PAGE -> Wire.body(env, SmsPage.serializer())?.let { p ->
                update { s -> s.copy(messages = s.messages + p.items.map(::clean).associateBy(::key), syncing = p.more) }
            }
            T.SMS_NEW -> Wire.body(env, SmsNew.serializer())?.let { n ->
                val item = clean(n.item)
                update { s ->
                    // Replace the local "sending" bubble with the gateway's copy.
                    val withoutPending = if (item.cmid != null) s.messages - "p:${item.cmid}" else s.messages
                    s.copy(messages = withoutPending + (key(item) to item))
                }
                item.cmid?.let { pendingSends.remove(it) }
            }
            T.SMS_ACK -> Wire.body(env, SmsAck.serializer())?.let { a ->
                if (a.state != DeliveryState.PENDING) pendingSends.remove(a.cmid)
                update { s ->
                    val k = "p:${a.cmid}"
                    val m = s.messages[k]
                    val delivered = s.messages.entries.firstOrNull { it.value.cmid == a.cmid }
                    when {
                        m != null -> s.copy(messages = s.messages + (k to m.copy(state = a.state)), lastError = a.err?.let { "sms:$it" } ?: s.lastError)
                        delivered != null -> s.copy(messages = s.messages + (delivered.key to delivered.value.copy(state = a.state)))
                        else -> s
                    }
                }
            }
            T.CALLS_PAGE -> Wire.body(env, CallsPage.serializer())?.let { p ->
                update { s ->
                    val merged = (s.calls + p.items.map { it.copy(number = Validate.displayText(it.number, 40), name = it.name?.let { n -> Validate.displayText(n, 100) }) })
                        .associateBy { it.id }.values.sortedByDescending { it.ts }
                    s.copy(calls = merged.take(MAX_CALLS), syncing = p.more)
                }
            }
            T.CALLS_NEW -> Wire.body(env, CallsNew.serializer())?.let { n ->
                update { s -> s.copy(calls = (listOf(n.item) + s.calls.filter { it.id != n.item.id }).take(MAX_CALLS)) }
            }
            T.CONTACTS_PAGE -> Wire.body(env, ContactsPage.serializer())?.let { p ->
                if (p.first) contactsBuffer = ArrayList()
                contactsBuffer.addAll(p.items.take(MAX_CONTACTS - contactsBuffer.size).map { c ->
                    c.copy(name = Validate.displayText(c.name, 100), phones = c.phones.take(10))
                })
                if (!p.more) {
                    val list = contactsBuffer.sortedBy { it.name.lowercase() }
                    update { it.copy(contacts = list, syncing = false) }
                }
            }
            T.CALL_STATE -> Wire.body(env, CallStates.serializer())?.let { c ->
                update { it.copy(liveCalls = c.calls.filter { call -> call.state != LiveCallState.ENDED }.take(4)) }
            }
            T.CALL_ACK -> Wire.body(env, CallAck.serializer())?.let { a ->
                if (!a.ok) update { it.copy(lastError = "call:${a.err ?: "failed"}") }
            }
            T.USSD_RESULT -> Wire.body(env, UssdResult.serializer())?.let { r ->
                update { s ->
                    if (s.ussd?.cmid != r.cmid) s
                    else s.copy(ussd = s.ussd.copy(pending = false, ok = r.ok, text = r.text?.let { Validate.displayText(it, 1000) }, err = r.err))
                }
            }
            T.PING -> Wire.body(env, Ping.serializer())?.let { send(Wire.encode(T.PONG, Ping.serializer(), it, re = env.id)) }
            T.PONG -> Wire.body(env, Ping.serializer())?.let { p -> update { it.copy(pingMs = (now() - p.ts).coerceAtLeast(0)) } }
            T.ERROR -> Wire.body(env, ErrorBody.serializer())?.let { e -> update { it.copy(lastError = e.code) } }
            else -> {} // unknown types are ignored for forward compatibility
        }
    }

    sealed interface ActionResult {
        data object Ok : ActionResult
        data object NotConnected : ActionResult
        data object Invalid : ActionResult
        data object Unsupported : ActionResult
    }

    private fun connected() = snapshot.link == LinkState.CONNECTED

    fun sendSms(to: String, body: String, subId: Int?): ActionResult {
        val number = Validate.smsRecipient(to) ?: return ActionResult.Invalid
        val text = Validate.smsBody(body) ?: return ActionResult.Invalid
        if (snapshot.status?.caps?.smsSend == false) return ActionResult.Unsupported
        val cmid = UUID.randomUUID().toString()
        val req = SmsSend(cmid, number, text, subId)
        val thread = snapshot.messages.values.firstOrNull { numberKey(it.address) == numberKey(number) }?.thread ?: (pendingLocalId--)
        val local = Sms(id = 0, thread = thread, address = number, body = text, ts = now(), dir = Direction.OUT,
            subId = subId, state = DeliveryState.PENDING, cmid = cmid)
        pendingSends[cmid] = req to now()
        update { s -> s.copy(messages = s.messages + ("p:$cmid" to local)) }
        if (connected()) send(Wire.encode(T.SMS_SEND, SmsSend.serializer(), req, id = nextId++))
        return if (connected()) ActionResult.Ok else ActionResult.NotConnected
    }

    /** Marks unanswered sends as failed after [timeoutMs] so the user can retry. */
    fun expirePending(timeoutMs: Long = 90_000) {
        val t = now()
        val expired = pendingSends.filterValues { t - it.second > timeoutMs }.keys
        if (expired.isEmpty()) return
        expired.forEach { pendingSends.remove(it) }
        update { s ->
            s.copy(messages = s.messages.mapValues { (k, v) ->
                if (k.removePrefix("p:") in expired && v.state == DeliveryState.PENDING) v.copy(state = DeliveryState.FAILED) else v
            })
        }
    }

    /** Re-sends a failed message with the same cmid (the gateway sends it at most once). */
    fun retry(cmid: String): ActionResult {
        val m = snapshot.messages["p:$cmid"] ?: return ActionResult.Invalid
        val req = SmsSend(cmid, m.address, m.body, m.subId)
        pendingSends[cmid] = req to now()
        update { s -> s.copy(messages = s.messages + ("p:$cmid" to m.copy(state = DeliveryState.PENDING))) }
        if (!connected()) return ActionResult.NotConnected
        send(Wire.encode(T.SMS_SEND, SmsSend.serializer(), req, id = nextId++))
        return ActionResult.Ok
    }

    fun markRead(thread: Long) {
        update { s -> s.copy(messages = s.messages.mapValues { (_, v) -> if (v.thread == thread && !v.read) v.copy(read = true) else v }) }
        if (connected() && thread > 0) send(Wire.encode(T.SMS_READ, SmsRead.serializer(), SmsRead(thread), id = nextId++))
    }

    fun dial(number: String, subId: Int?): ActionResult {
        val n = Validate.dialNumber(number) ?: return ActionResult.Invalid
        if (!connected()) return ActionResult.NotConnected
        if (snapshot.status?.caps?.callPlace != true) return ActionResult.Unsupported
        send(Wire.encode(T.CALL_DIAL, CallDial.serializer(), CallDial(UUID.randomUUID().toString(), n, subId), id = nextId++))
        return ActionResult.Ok
    }

    fun callAction(callId: String, action: String): ActionResult {
        if (action !in CallActions.all) return ActionResult.Invalid
        if (!connected()) return ActionResult.NotConnected
        val caps = snapshot.status?.caps
        if (caps?.callControl != true) return ActionResult.Unsupported
        if ((action == CallActions.HOLD || action == CallActions.UNHOLD) && !caps.hold) return ActionResult.Unsupported
        send(Wire.encode(T.CALL_ACTION, CallAction.serializer(), CallAction(callId, action), id = nextId++))
        return ActionResult.Ok
    }

    /** Keypad tones during a call, played by the gateway's phone app. */
    fun dtmf(callId: String, digits: String): ActionResult {
        if (digits.isEmpty() || digits.length > 20 || digits.any { it !in "0123456789*#" }) return ActionResult.Invalid
        if (!connected()) return ActionResult.NotConnected
        if (snapshot.status?.caps?.dtmf != true) return ActionResult.Unsupported
        send(Wire.encode(T.CALL_DTMF, studio.virgule.pol.core.proto.CallDtmf.serializer(), studio.virgule.pol.core.proto.CallDtmf(callId, digits), id = nextId++))
        return ActionResult.Ok
    }

    fun ussd(code: String, subId: Int?): ActionResult {
        val c = Validate.ussdCode(code) ?: return ActionResult.Invalid
        if (!connected()) return ActionResult.NotConnected
        if (snapshot.status?.caps?.ussd != true) return ActionResult.Unsupported
        val cmid = UUID.randomUUID().toString()
        update { it.copy(ussd = UssdReply(cmid, c, pending = true)) }
        send(Wire.encode(T.USSD_SEND, UssdSend.serializer(), UssdSend(cmid, c, subId), id = nextId++))
        return ActionResult.Ok
    }

    fun ping() { if (connected()) send(Wire.encode(T.PING, Ping.serializer(), Ping(now()), id = nextId++)) }

    fun clearError() = update { it.copy(lastError = null) }

    /** Forget everything held in memory (on unpair, "clear data" or leaving demo mode). */
    fun reset(link: LinkState = LinkState.NOT_PAIRED) {
        pendingSends.clear()
        contactsBuffer = ArrayList()
        update { ClientSnapshot(link = link) }
    }

    private fun key(m: Sms) = if (m.id > 0) "m:${m.id}" else "p:${m.cmid ?: m.ts}"

    private fun clean(m: Sms) = m.copy(address = Validate.displayText(m.address, 40), body = Validate.displayText(m.body, Validate.MAX_SMS_CHARS * 2))

    companion object {
        const val MAX_CALLS = 1000
        const val MAX_CONTACTS = 5000
    }
}
