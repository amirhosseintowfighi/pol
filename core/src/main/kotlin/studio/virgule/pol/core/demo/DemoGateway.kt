package studio.virgule.pol.core.demo

import studio.virgule.pol.core.domain.CallEntry
import studio.virgule.pol.core.domain.CallType
import studio.virgule.pol.core.domain.Capabilities
import studio.virgule.pol.core.domain.Contact
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.domain.GatewayStatus
import studio.virgule.pol.core.domain.LiveCall
import studio.virgule.pol.core.domain.LiveCallState
import studio.virgule.pol.core.domain.PhoneNumber
import studio.virgule.pol.core.domain.SimInfo
import studio.virgule.pol.core.domain.Sms
import studio.virgule.pol.core.proto.CallAck
import studio.virgule.pol.core.proto.CallAction
import studio.virgule.pol.core.proto.CallActions
import studio.virgule.pol.core.proto.CallDial
import studio.virgule.pol.core.proto.CallStates
import studio.virgule.pol.core.proto.CallsPage
import studio.virgule.pol.core.proto.ContactsPage
import studio.virgule.pol.core.proto.Ping
import studio.virgule.pol.core.proto.SmsAck
import studio.virgule.pol.core.proto.SmsNew
import studio.virgule.pol.core.proto.SmsPage
import studio.virgule.pol.core.proto.SmsSend
import studio.virgule.pol.core.proto.T
import studio.virgule.pol.core.proto.UssdResult
import studio.virgule.pol.core.proto.UssdSend
import studio.virgule.pol.core.proto.Validate
import studio.virgule.pol.core.proto.Wire

/**
 * A simulated gateway that speaks the real control protocol in memory. Demo mode wires the client UI to this
 * instead of a Bluetooth link, so every screen behaves as it would with a real phone, but this class has no
 * access to Bluetooth, telephony or the network: nothing it "sends" or "dials" can leave the device.
 *
 * Numbers use the 555-01xx range reserved for fiction; names are invented.
 */
class DemoGateway(private val reply: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    private var nextSmsId = 1000L
    private val t0 = now()
    private val min = 60_000L
    private val day = 24 * 60 * min

    val status = GatewayStatus(
        name = "Demo gateway",
        appVersion = "demo",
        battery = 76,
        charging = false,
        sims = listOf(
            SimInfo(slot = 0, subId = 1, label = "Personal", carrier = "Demo Mobile", signal = 4, network = "LTE"),
            SimInfo(slot = 1, subId = 2, label = "Work", carrier = "Sample Telecom", signal = 2, network = "3G"),
        ),
        defaultVoiceSub = 1, defaultSmsSub = 1,
        caps = Capabilities(smsSend = true, smsRead = true, smsReceive = true, callPlace = true, callControl = true,
            callLog = true, contacts = true, ussd = true, simChoice = true),
        sdk = 35,
    )

    private val contacts = listOf(
        Contact(1, "Sara Ahmadi", listOf(PhoneNumber("+15555550101", "Mobile")), starred = true),
        Contact(2, "Daniel Reyes", listOf(PhoneNumber("+15555550102", "Mobile"))),
        Contact(3, "Neda Karimi", listOf(PhoneNumber("+15555550103", "Mobile"), PhoneNumber("+15555550113", "Work")), starred = true),
        Contact(4, "Omid Rahimi", listOf(PhoneNumber("+15555550104", "Home"))),
        Contact(5, "Lena Fischer", listOf(PhoneNumber("+15555550105", "Mobile"))),
    )

    private val messages = mutableListOf(
        Sms(1, 1, "+15555550101", "Are we still on for lunch tomorrow?", t0 - 35 * min, Direction.IN, read = false, subId = 1),
        Sms(2, 1, "+15555550101", "Yes, 1 pm works.", t0 - 30 * min, Direction.OUT, subId = 1, state = DeliveryState.DELIVERED),
        Sms(3, 1, "+15555550101", "Great, see you there 🙂", t0 - 28 * min, Direction.IN, read = false, subId = 1),
        Sms(4, 2, "+15555550102", "The documents are ready to sign.", t0 - 3 * 60 * min, Direction.IN, subId = 2),
        Sms(5, 3, "DemoBank", "Your verification code is 482913. It expires in 2 minutes.", t0 - day, Direction.IN, subId = 1),
        Sms(6, 4, "+15555550103", "سلام! فردا جلسه ساعت ۱۰ است.", t0 - 2 * day, Direction.IN, subId = 1),
    )

    private val calls = mutableListOf(
        CallEntry(1, "+15555550102", t0 - 50 * min, 0, CallType.MISSED, 2, "Daniel Reyes"),
        CallEntry(2, "+15555550101", t0 - 4 * 60 * min, 312, CallType.OUTGOING, 1, "Sara Ahmadi"),
        CallEntry(3, "+15555550104", t0 - day, 95, CallType.INCOMING, 1, "Omid Rahimi"),
        CallEntry(4, "+15555550199", t0 - 2 * day, 0, CallType.REJECTED, 1, null),
    )

    private var live: LiveCall? = null

    /** Handles a message the client "sent". */
    fun receive(json: String) {
        val env = Wire.decode(json) ?: return
        when (env.t) {
            T.SYNC -> {
                reply(Wire.encode(T.STATUS, GatewayStatus.serializer(), status))
                reply(Wire.encode(T.SMS_PAGE, SmsPage.serializer(), SmsPage(messages.toList(), more = false)))
                reply(Wire.encode(T.CALLS_PAGE, CallsPage.serializer(), CallsPage(calls.toList(), more = false)))
                reply(Wire.encode(T.CONTACTS_PAGE, ContactsPage.serializer(), ContactsPage(contacts, more = false, first = true)))
                reply(Wire.encode(T.CALL_STATE, CallStates.serializer(), CallStates(listOfNotNull(live))))
            }
            T.SMS_SEND -> Wire.body(env, SmsSend.serializer())?.let { s ->
                val thread = messages.firstOrNull { it.address == s.to }?.thread ?: (messages.maxOf { it.thread } + 1)
                val m = Sms(nextSmsId++, thread, s.to, s.body, now(), Direction.OUT, subId = s.subId, state = DeliveryState.SENT, cmid = s.cmid)
                messages += m
                reply(Wire.encode(T.SMS_NEW, SmsNew.serializer(), SmsNew(m)))
                reply(Wire.encode(T.SMS_ACK, SmsAck.serializer(), SmsAck(s.cmid, DeliveryState.DELIVERED)))
            }
            T.CALL_DIAL -> Wire.body(env, CallDial.serializer())?.let { d ->
                live = LiveCall("demo-${d.cmid.take(8)}", d.number, contacts.firstOrNull { c -> c.phones.any { it.number == d.number } }?.name,
                    LiveCallState.ACTIVE, incoming = false, activeSince = now(), subId = d.subId ?: 1)
                reply(Wire.encode(T.CALL_ACK, CallAck.serializer(), CallAck(d.cmid, ok = true)))
                reply(Wire.encode(T.CALL_STATE, CallStates.serializer(), CallStates(listOfNotNull(live))))
            }
            T.CALL_ACTION -> Wire.body(env, CallAction.serializer())?.let { a ->
                val c = live ?: return
                live = when (a.action) {
                    CallActions.ANSWER -> c.copy(state = LiveCallState.ACTIVE, activeSince = now())
                    else -> null
                }
                if (live == null) {
                    calls.add(0, CallEntry((calls.maxOfOrNull { it.id } ?: 0) + 1, c.number, c.activeSince ?: now(),
                        ((now() - (c.activeSince ?: now())) / 1000), if (c.incoming) CallType.INCOMING else CallType.OUTGOING, c.subId, c.name))
                    reply(Wire.encode(T.CALLS_PAGE, CallsPage.serializer(), CallsPage(calls.take(1), more = false)))
                }
                reply(Wire.encode(T.CALL_STATE, CallStates.serializer(), CallStates(listOfNotNull(live))))
            }
            T.USSD_SEND -> Wire.body(env, UssdSend.serializer())?.let { u ->
                reply(Wire.encode(T.USSD_RESULT, UssdResult.serializer(),
                    UssdResult(u.cmid, ok = true, text = "Demo: balance 120,000 tomans. (Simulated reply — no code was sent to a carrier.)")))
            }
            T.PING -> Wire.body(env, Ping.serializer())?.let { reply(Wire.encode(T.PONG, Ping.serializer(), it, re = env.id)) }
            else -> {}
        }
    }

    /** Simulates an incoming call so the ringing UI can be tried. */
    fun simulateIncomingCall() {
        live = LiveCall("demo-in", "+15555550105", "Lena Fischer", LiveCallState.RINGING, incoming = true, subId = 1)
        reply(Wire.encode(T.CALL_STATE, CallStates.serializer(), CallStates(listOfNotNull(live))))
    }

    /** Simulates a new incoming text. */
    fun simulateIncomingSms(body: String = "This is a simulated message.") {
        val m = Sms(nextSmsId++, 2, "+15555550102", Validate.smsBody(body) ?: "…", now(), Direction.IN, read = false, subId = 2)
        messages += m
        reply(Wire.encode(T.SMS_NEW, SmsNew.serializer(), SmsNew(m)))
    }
}
