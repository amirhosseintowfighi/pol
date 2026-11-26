package studio.virgule.pol.android.gateway.telecom

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import studio.virgule.pol.android.R
import studio.virgule.pol.android.PolApp

/**
 * Telecom binds this service only while Pol Gateway is the default phone app and a call exists. It feeds
 * [CallRegistry] (which the gateway runtime shares with paired devices) and shows the gateway phone's own call
 * screen and incoming-call notification, because as the phone app it must let a person at this phone use it too.
 * Telecom keeps playing the ringtone itself (we don't declare IN_CALL_SERVICE_RINGING).
 */
class PolInCallService : InCallService() {
    override fun onCreate() { super.onCreate(); CallRegistry.bound = true }

    override fun onDestroy() { CallRegistry.bound = false; CallRegistry.clear(); cancelNotification(); super.onDestroy() }

    override fun onCallAdded(call: Call) {
        CallRegistry.add(call)
        call.registerCallback(uiCallback)
        updateNotification()
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(uiCallback)
        CallRegistry.remove(call)
        updateNotification()
    }

    private val uiCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = updateNotification()
    }

    private fun updateNotification() {
        val calls = CallRegistry.calls()
        val ringing = calls.firstOrNull { CallRegistry.stateOf(it.first) == Call.STATE_RINGING }
        val current = ringing ?: calls.firstOrNull() ?: run { cancelNotification(); return }
        val (call, id) = current
        val who = call.details.handle?.schemeSpecificPart ?: getString(R.string.unknown_number)
        val open = PendingIntent.getActivity(this, 1, Intent(this, PhoneCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, code: Int) = PendingIntent.getBroadcast(this, code,
            Intent(this, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION).putExtra("id", id).putExtra("action", a),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val b = Notification.Builder(this, PolApp.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(open)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_CALL)
        if (Build.VERSION.SDK_INT >= 31) {
            val person = Person.Builder().setName(who).setImportant(true).build()
            b.style = if (ringing != null) Notification.CallStyle.forIncomingCall(person, action("reject", 2), action("answer", 3))
            else Notification.CallStyle.forOngoingCall(person, action("end", 4))
        } else {
            b.setContentTitle(who).setContentText(getString(if (ringing != null) R.string.incall_ringing else R.string.incall_in_progress))
            if (ringing != null) {
                b.addAction(Notification.Action.Builder(null, getString(R.string.decline), action("reject", 2)).build())
                b.addAction(Notification.Action.Builder(null, getString(R.string.answer), action("answer", 3)).build())
            } else b.addAction(Notification.Action.Builder(null, getString(R.string.end_call), action("end", 4)).build())
        }
        if (ringing != null) b.setFullScreenIntent(open, true)
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, b.build()) }
    }

    private fun cancelNotification() { getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }

    companion object { const val NOTIFICATION_ID = 30 }
}
