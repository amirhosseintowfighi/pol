package studio.virgule.pol.android.gateway

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import studio.virgule.pol.android.common.SafeLog
import studio.virgule.pol.core.domain.CallEntry
import studio.virgule.pol.core.domain.CallType
import studio.virgule.pol.core.domain.Capabilities
import studio.virgule.pol.core.domain.Contact
import studio.virgule.pol.core.domain.DeliveryState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.domain.GatewayStatus
import studio.virgule.pol.core.domain.PhoneNumber
import studio.virgule.pol.core.domain.SimInfo
import studio.virgule.pol.core.domain.Sms
import java.util.concurrent.atomic.AtomicInteger

/**
 * Everything the gateway does with the phone's SIMs, built only on public Android APIs available to a regular
 * (non-system, non-default-dialer) app. Each operation checks its own permission first; a missing permission
 * disables the feature (see [capabilities]) rather than failing later.
 *
 * What a regular app cannot do, and therefore this class does not offer: carry call audio to another device,
 * hold/mute/speaker on a cellular call, change APN, VoLTE, preferred network, SIM PIN or hotspot settings.
 */
class Telephony(private val ctx: Context, private val handler: Handler) {

    fun has(permission: String) = ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private val telephony get() = ctx.getSystemService(TelephonyManager::class.java)
    private val telecom get() = ctx.getSystemService(TelecomManager::class.java)
    private val subscriptions get() = ctx.getSystemService(SubscriptionManager::class.java)

    fun capabilities(settings: GatewaySettings): Capabilities {
        val phoneState = has(Manifest.permission.READ_PHONE_STATE)
        val callPhone = has(Manifest.permission.CALL_PHONE)
        return Capabilities(
            smsSend = settings.messages && has(Manifest.permission.SEND_SMS),
            smsRead = settings.messages && has(Manifest.permission.READ_SMS),
            smsReceive = settings.messages && has(Manifest.permission.READ_SMS),
            callPlace = settings.calls && callPhone,
            callControl = settings.calls && has(Manifest.permission.ANSWER_PHONE_CALLS) && phoneState && Build.VERSION.SDK_INT >= 28,
            callLog = settings.recents && has(Manifest.permission.READ_CALL_LOG),
            contacts = settings.shareContacts && has(Manifest.permission.READ_CONTACTS),
            ussd = settings.carrierCodes && callPhone,
            simChoice = phoneState && sims().size > 1,
        )
    }

    // ---------------------------------------------------------------- status

    fun status(name: String, settings: GatewaySettings, appVersion: String): GatewayStatus {
        val battery = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.let {
            val l = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val s = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (l >= 0 && s > 0) l * 100 / s else null
        }
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val phoneState = has(Manifest.permission.READ_PHONE_STATE)
        return GatewayStatus(
            name = name,
            appVersion = appVersion,
            battery = level,
            charging = plugged,
            sims = if (phoneState) sims() else emptyList(),
            defaultVoiceSub = SubscriptionManager.getDefaultVoiceSubscriptionId().takeIf { it >= 0 },
            defaultSmsSub = SubscriptionManager.getDefaultSmsSubscriptionId().takeIf { it >= 0 },
            caps = capabilities(settings),
            sdk = Build.VERSION.SDK_INT,
        )
    }

    @SuppressLint("MissingPermission")
    fun sims(): List<SimInfo> {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val list = try { subscriptions.activeSubscriptionInfoList ?: emptyList() } catch (e: SecurityException) { emptyList() }
        return list.sortedBy { it.simSlotIndex }.map { info ->
            val tm = telephony.createForSubscriptionId(info.subscriptionId)
            val level = if (Build.VERSION.SDK_INT >= 28) runCatching { tm.signalStrength?.level }.getOrNull() else null
            val net = runCatching { networkName(tm.dataNetworkType) }.getOrNull()
            SimInfo(
                slot = info.simSlotIndex,
                subId = info.subscriptionId,
                label = info.displayName?.toString().orEmpty().take(40),
                carrier = info.carrierName?.toString().orEmpty().take(40),
                signal = level?.coerceIn(0, 4),
                network = net,
                service = if (level == null) "unknown" else "in",
                roaming = runCatching { tm.isNetworkRoaming }.getOrDefault(false),
            )
        }
    }

    private fun networkName(type: Int): String? = when (type) {
        TelephonyManager.NETWORK_TYPE_NR -> "5G"
        TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
        TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_HSPA, TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_UMTS -> "3G"
        TelephonyManager.NETWORK_TYPE_EDGE, TelephonyManager.NETWORK_TYPE_GPRS, TelephonyManager.NETWORK_TYPE_GSM -> "2G"
        else -> null
    }

    // ---------------------------------------------------------------- messages

    /**
     * Newest-first scan of the system SMS store. For a full sync only the most recent [limit] messages from the
     * last [maxAgeDays] are shared, to keep what crosses the link to what a person actually scrolls through.
     */
    fun readSms(sinceId: Long, limit: Int = 1000, maxAgeDays: Int = 90): List<Sms> {
        if (!has(Manifest.permission.READ_SMS)) return emptyList()
        val minDate = if (sinceId > 0) 0L else System.currentTimeMillis() - maxAgeDays * 86_400_000L
        val out = ArrayList<Sms>()
        val cols = arrayOf("_id", "thread_id", "address", "body", "date", "type", "read", "sub_id", "status")
        try {
            ctx.contentResolver.query(android.provider.Telephony.Sms.CONTENT_URI, cols, "_id > ? AND date >= ?",
                arrayOf(sinceId.toString(), minDate.toString()), "_id DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val type = c.getInt(5)
                    val status = c.getInt(8)
                    val dir = if (type == 1) Direction.IN else Direction.OUT
                    val state = when {
                        dir == Direction.IN -> DeliveryState.RECEIVED
                        type == 5 || status == 64 -> DeliveryState.FAILED
                        type == 4 || type == 6 -> DeliveryState.PENDING
                        status == 0 -> DeliveryState.DELIVERED
                        else -> DeliveryState.SENT
                    }
                    out += Sms(
                        id = c.getLong(0), thread = c.getLong(1), address = c.getString(2).orEmpty().take(40),
                        body = c.getString(3).orEmpty().take(4000), ts = c.getLong(4), dir = dir,
                        read = c.getInt(6) != 0, subId = c.getInt(7).takeIf { it >= 0 }, state = state,
                    )
                }
            }
        } catch (e: Exception) {
            SafeLog.w("sms", "query failed: ${e.javaClass.simpleName}")
        }
        return out.sortedBy { it.id }
    }

    private val requestCodes = AtomicInteger(1)

    /** Sends a text; [onState] gets SENT/FAILED and, if the network reports it, DELIVERED. */
    @SuppressLint("MissingPermission")
    fun sendSms(to: String, body: String, subId: Int?, onState: (DeliveryState, String?) -> Unit) {
        if (!has(Manifest.permission.SEND_SMS)) { onState(DeliveryState.FAILED, "permission"); return }
        val sub = subId ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        val sm: SmsManager = try {
            if (Build.VERSION.SDK_INT >= 31) {
                val base = ctx.getSystemService(SmsManager::class.java)
                if (sub >= 0) base.createForSubscriptionId(sub) else base
            } else {
                @Suppress("DEPRECATION")
                if (sub >= 0) SmsManager.getSmsManagerForSubscriptionId(sub) else SmsManager.getDefault()
            }
        } catch (e: Exception) { onState(DeliveryState.FAILED, "no_service"); return }

        val parts = sm.divideMessage(body)
        val token = "sms-${System.nanoTime()}"
        var sent = 0; var delivered = 0; var failed = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.getStringExtra(EXTRA_TOKEN) != token) return
                when (i.action) {
                    ACTION_SENT -> {
                        if (resultCode != android.app.Activity.RESULT_OK && !failed) { failed = true; onState(DeliveryState.FAILED, "failed") }
                        if (++sent == parts.size && !failed) onState(DeliveryState.SENT, null)
                    }
                    ACTION_DELIVERED -> if (++delivered == parts.size && !failed) {
                        onState(DeliveryState.DELIVERED, null)
                        runCatching { ctx.unregisterReceiver(this) }
                    }
                }
                if (failed) runCatching { ctx.unregisterReceiver(this) }
            }
        }
        val filter = IntentFilter().apply { addAction(ACTION_SENT); addAction(ACTION_DELIVERED) }
        // The flags overload exists since Android 8; before 13 the NOT_EXPORTED bit is simply ignored.
        ctx.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED)
        // Delivery reports are optional on many networks: stop listening after 10 minutes either way.
        handler.postDelayed({ runCatching { ctx.unregisterReceiver(receiver) } }, 10 * 60_000L)

        fun pending(action: String) = ArrayList(parts.indices.map {
            PendingIntent.getBroadcast(ctx, requestCodes.incrementAndGet(),
                Intent(action).setPackage(ctx.packageName).putExtra(EXTRA_TOKEN, token),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT)
        })
        try {
            if (parts.size == 1) sm.sendTextMessage(to, null, body, pending(ACTION_SENT)[0], pending(ACTION_DELIVERED)[0])
            else sm.sendMultipartTextMessage(to, null, parts, pending(ACTION_SENT), pending(ACTION_DELIVERED))
        } catch (e: Exception) {
            SafeLog.w("sms", "send rejected: ${e.javaClass.simpleName}")
            runCatching { ctx.unregisterReceiver(receiver) }
            onState(DeliveryState.FAILED, "failed")
        }
    }

    // ---------------------------------------------------------------- recent calls

    fun readCalls(sinceId: Long, limit: Int = 500): List<CallEntry> {
        if (!has(Manifest.permission.READ_CALL_LOG)) return emptyList()
        val accounts = accountToSub()
        val out = ArrayList<CallEntry>()
        val cols = arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION,
            CallLog.Calls.TYPE, CallLog.Calls.PHONE_ACCOUNT_ID, CallLog.Calls.CACHED_NAME)
        try {
            ctx.contentResolver.query(CallLog.Calls.CONTENT_URI, cols, "${CallLog.Calls._ID} > ?", arrayOf(sinceId.toString()),
                "${CallLog.Calls._ID} DESC")?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val type = when (c.getInt(4)) {
                        CallLog.Calls.INCOMING_TYPE -> CallType.INCOMING
                        CallLog.Calls.OUTGOING_TYPE -> CallType.OUTGOING
                        CallLog.Calls.MISSED_TYPE -> CallType.MISSED
                        CallLog.Calls.REJECTED_TYPE -> CallType.REJECTED
                        CallLog.Calls.BLOCKED_TYPE -> CallType.BLOCKED
                        else -> CallType.OTHER
                    }
                    out += CallEntry(c.getLong(0), c.getString(1).orEmpty().take(40), c.getLong(2), c.getLong(3), type,
                        c.getString(5)?.let { accounts[it] }, c.getString(6)?.take(100))
                }
            }
        } catch (e: Exception) {
            SafeLog.w("calls", "query failed: ${e.javaClass.simpleName}")
        }
        return out.sortedBy { it.id }
    }

    // ---------------------------------------------------------------- contacts (opt-in)

    fun readContacts(limit: Int = 5000): List<Contact> {
        if (!has(Manifest.permission.READ_CONTACTS)) return emptyList()
        val byId = LinkedHashMap<Long, Contact>()
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY,
            ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.TYPE,
            ContactsContract.CommonDataKinds.Phone.LABEL, ContactsContract.CommonDataKinds.Phone.STARRED)
        try {
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && byId.size < limit) {
                    val id = c.getLong(0)
                    val label = ContactsContract.CommonDataKinds.Phone.getTypeLabel(ctx.resources, c.getInt(3), c.getString(4)).toString()
                    val phone = PhoneNumber(c.getString(2).orEmpty().take(40), label.take(30))
                    val existing = byId[id]
                    byId[id] = existing?.copy(phones = (existing.phones + phone).distinctBy { it.number }.take(10))
                        ?: Contact(id, c.getString(1).orEmpty().take(100), listOf(phone), c.getInt(5) != 0)
                }
            }
        } catch (e: Exception) {
            SafeLog.w("contacts", "query failed: ${e.javaClass.simpleName}")
        }
        return byId.values.toList()
    }

    // ---------------------------------------------------------------- calls

    @SuppressLint("MissingPermission")
    private fun handleFor(subId: Int?): PhoneAccountHandle? {
        if (subId == null || !has(Manifest.permission.READ_PHONE_STATE)) return null
        val handles = runCatching { telecom.callCapablePhoneAccounts }.getOrNull() ?: return null
        return handles.firstOrNull { h ->
            if (Build.VERSION.SDK_INT >= 30) runCatching { telephony.getSubscriptionId(h) }.getOrNull() == subId
            else h.id == subId.toString()
        }
    }

    /** SIM of a Telecom call, for showing which line it is on. */
    @SuppressLint("MissingPermission")
    fun subIdFor(handle: PhoneAccountHandle?): Int? {
        if (handle == null || !has(Manifest.permission.READ_PHONE_STATE)) return null
        return if (Build.VERSION.SDK_INT >= 30) runCatching { telephony.getSubscriptionId(handle) }.getOrNull()?.takeIf { it >= 0 }
        else handle.id.toIntOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun accountToSub(): Map<String, Int> {
        if (!has(Manifest.permission.READ_PHONE_STATE)) return emptyMap()
        val handles = runCatching { telecom.callCapablePhoneAccounts }.getOrNull() ?: return emptyMap()
        return handles.mapNotNull { h ->
            val sub = if (Build.VERSION.SDK_INT >= 30) runCatching { telephony.getSubscriptionId(h) }.getOrNull() else h.id.toIntOrNull()
            sub?.takeIf { it >= 0 }?.let { h.id to it }
        }.toMap()
    }

    /** Returns null on success, else an error code from [studio.virgule.pol.core.proto.Err]. */
    @SuppressLint("MissingPermission")
    fun dial(number: String, subId: Int?): String? {
        if (!has(Manifest.permission.CALL_PHONE)) return "permission"
        return try {
            val extras = Bundle()
            handleFor(subId)?.let { extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, it) }
            telecom.placeCall(Uri.fromParts("tel", number, null), extras)
            null
        } catch (e: SecurityException) { "permission" } catch (e: Exception) { "failed" }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION") // Still the public API for non-dialer apps holding ANSWER_PHONE_CALLS.
    fun answer(): Boolean {
        if (!has(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching { telecom.acceptRingingCall(); true }.getOrDefault(false)
    }

    /** Ends the active call or rejects a ringing one (Android 9+). */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun endCall(): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !has(Manifest.permission.ANSWER_PHONE_CALLS)) return false
        return runCatching { telecom.endCall() }.getOrDefault(false)
    }

    // ---------------------------------------------------------------- carrier codes

    @SuppressLint("MissingPermission")
    fun ussd(code: String, subId: Int?, done: (Boolean, String?) -> Unit) {
        if (!has(Manifest.permission.CALL_PHONE)) { done(false, "permission"); return }
        val tm = if (subId != null && subId >= 0) telephony.createForSubscriptionId(subId) else telephony
        try {
            tm.sendUssdRequest(code, object : TelephonyManager.UssdResponseCallback() {
                override fun onReceiveUssdResponse(t: TelephonyManager, request: String, response: CharSequence) = done(true, response.toString().take(1000))
                override fun onReceiveUssdResponseFailed(t: TelephonyManager, request: String, failureCode: Int) = done(false, "failed")
            }, handler)
        } catch (e: Exception) {
            done(false, "failed")
        }
    }

    companion object {
        private const val ACTION_SENT = "studio.virgule.pol.gateway.SMS_SENT"
        private const val ACTION_DELIVERED = "studio.virgule.pol.gateway.SMS_DELIVERED"
        private const val EXTRA_TOKEN = "token"
    }
}
