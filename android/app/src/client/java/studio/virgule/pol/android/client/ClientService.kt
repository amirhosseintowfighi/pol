package studio.virgule.pol.android.client

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import studio.virgule.pol.android.R
import studio.virgule.pol.android.PolApp
import studio.virgule.pol.core.client.ClientSnapshot
import studio.virgule.pol.core.client.LinkState
import studio.virgule.pol.core.domain.Direction
import studio.virgule.pol.core.domain.LiveCallState

/** Keeps the Bluetooth link to the gateway while the app is in the background, with a visible notification. */
class ClientService : Service() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        startForeground(ClientNotifications.ID_ONGOING, ClientNotifications.ongoing(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        ClientRepository.get(this).startLink()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        instance = null
        ClientRepository.get(this).let { r -> r.handler.post { r.stopLink() } }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile private var instance: ClientService? = null

        /**
         * During a bridged call the service also declares the microphone and phone-call types (Android 14+ requires
         * it to use the microphone in the background). Allowed because Telecom has an active self-managed call.
         */
        fun setInCall(ctx: Context, on: Boolean) {
            val s = instance ?: return
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (on) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL else 0)
            runCatching { s.startForeground(ClientNotifications.ID_ONGOING, ClientNotifications.ongoing(ctx), types) }
        }

        fun start(ctx: Context) { ctx.startForegroundService(Intent(ctx, ClientService::class.java)) }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, ClientService::class.java)) }
    }
}

/**
 * Notifications for new texts and incoming calls. Off until the user turns them on (which is when
 * POST_NOTIFICATIONS is requested). Previews are hidden unless the user chose to show them, and the channels
 * are VISIBILITY_PRIVATE so the lock screen shows no content either way.
 */
object ClientNotifications {
    const val ID_ONGOING = 10
    const val ID_BRIDGED_CALL = 12
    private const val ID_CALL = 11
    private const val ID_MESSAGE_BASE = 100
    private var seenMessages: Set<String>? = null
    private var ringing: String? = null

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0,
        Intent(ctx, ClientActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    fun ongoing(ctx: Context): Notification = Notification.Builder(ctx, PolApp.CHANNEL_LINK)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(ctx.getString(R.string.cl_notification_title))
        .setContentText(ctx.getString(R.string.cl_notification_text))
        .setContentIntent(open(ctx))
        .setOngoing(true)
        .build()

    @Synchronized
    fun onSnapshot(ctx: Context, s: ClientSnapshot, enabled: Boolean, previews: Boolean) {
        val allowed = enabled &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
            s.link == LinkState.CONNECTED && !AppVisibility.foreground
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val incoming = s.messages.filterValues { it.dir == Direction.IN && !it.read }.keys
        val previous = seenMessages
        seenMessages = incoming
        if (previous != null && allowed) {
            for (key in incoming - previous) {
                val m = s.messages[key] ?: continue
                val title = if (previews) (s.nameFor(m.address) ?: m.address) else ctx.getString(R.string.cl_new_message)
                val text = if (previews) m.body.take(200) else ctx.getString(R.string.cl_open_to_read)
                val n = Notification.Builder(ctx, PolApp.CHANNEL_MESSAGES)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(title).setContentText(text)
                    .setContentIntent(open(ctx)).setAutoCancel(true)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build()
                nm.notify(ID_MESSAGE_BASE + (m.thread % 1000).toInt(), n)
            }
        }
        val ring = s.liveCalls.firstOrNull { it.state == LiveCallState.RINGING }?.takeIf { ClientCalls.current?.isBridged(it.callId) != true }
        if (ring?.callId != ringing) {
            ringing = ring?.callId
            if (ring == null) nm.cancel(ID_CALL)
            else if (allowed) {
                val who = if (previews) (ring.name ?: s.nameFor(ring.number) ?: ring.number) else ctx.getString(R.string.cl_incoming_call)
                nm.notify(ID_CALL, Notification.Builder(ctx, PolApp.CHANNEL_CALLS)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle(ctx.getString(R.string.cl_incoming_call))
                    .setContentText(who)
                    .setContentIntent(open(ctx))
                    .setCategory(Notification.CATEGORY_CALL)
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .build())
            }
        }
    }
}

/** Incoming bridged call: call-style notification that opens the call screen full-screen (Telecom asked us to). */
fun ClientNotifications.incomingCall(ctx: Context, callId: String, number: String?) {
    val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, ClientActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE)
    fun act(a: String, code: Int) = PendingIntent.getBroadcast(ctx, code, Intent(ctx, ClientCallActionReceiver::class.java)
        .setAction(ClientCallActionReceiver.ACTION).putExtra("id", callId).putExtra("action", a), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val person = android.app.Person.Builder().setName(number?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.unknown_number)).setImportant(true).build()
    val n = Notification.Builder(ctx, PolApp.CHANNEL_CALLS)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setStyle(Notification.CallStyle.forIncomingCall(person, act("reject", 3), act("answer", 4)))
        .setFullScreenIntent(open, true)
        .setContentIntent(open)
        .setCategory(Notification.CATEGORY_CALL)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setOngoing(true)
        .build()
    runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ClientNotifications.ID_BRIDGED_CALL, n) }
}

fun ClientNotifications.clearCall(ctx: Context) { ctx.getSystemService(NotificationManager::class.java).cancel(ClientNotifications.ID_BRIDGED_CALL) }

/** Answer / decline from the call notification. Not exported: only our PendingIntents reach it. */
class ClientCallActionReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra("id") ?: return
        val repo = ClientRepository.get(context)
        repo.handler.post { if (intent.getStringExtra("action") == "answer") repo.answer(id) else repo.end(id, ringing = true) }
    }
    companion object { const val ACTION = "studio.virgule.pol.client.CALL_ACTION" }
}

/** Set by ClientActivity's lifecycle; no notifications while the user is looking at the app. */
object AppVisibility { @Volatile var foreground = false }
