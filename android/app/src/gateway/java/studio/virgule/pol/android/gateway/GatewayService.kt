package studio.virgule.pol.android.gateway

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.bluetooth.BluetoothAdapter
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import studio.virgule.pol.android.R
import studio.virgule.pol.android.PolApp
import studio.virgule.pol.android.common.SafeLog

/**
 * Foreground service that keeps the gateway reachable while the phone sits in a bag. It shows a persistent
 * notification for as long as it runs, so the owner always knows the phone is acting as a gateway.
 */
class GatewayService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private var runtime: GatewayRuntime? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_ON -> handler.post { startRuntime() }
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> handler.post { runtime?.stop(); runtime = null; current = null }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        thread = HandlerThread("pol-link").apply { start() }
        handler = Handler(thread.looper)
        val notification = GatewayNotifications.ongoing(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(GatewayNotifications.ID_ONGOING, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(GatewayNotifications.ID_ONGOING, notification)
        val f = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(bluetoothReceiver, f, RECEIVER_NOT_EXPORTED) else registerReceiver(bluetoothReceiver, f)
        handler.post { startRuntime() }
    }

    private fun startRuntime() {
        if (runtime != null) return
        try {
            val r = GatewayRuntime(applicationContext, handler)
            if (r.start()) { runtime = r; current = r } else {
                GatewayState.mutable.value = GatewayState.ui.value.copy(running = false, bluetoothOn = false)
            }
        } catch (e: SecurityException) {
            SafeLog.w("service", "missing Bluetooth permission")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { unregisterReceiver(bluetoothReceiver) }
        val r = runtime
        handler.post { r?.stop(); thread.quitSafely() }
        runtime = null
        current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** The running gateway, for the owner's screen. Null when stopped. */
        @Volatile var current: GatewayRuntime? = null
            private set

        fun start(ctx: Context) {
            val i = Intent(ctx, GatewayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, GatewayService::class.java)) }
    }
}

object GatewayNotifications {
    const val ID_ONGOING = 1
    private const val ID_PAIRING = 2

    private fun open(ctx: Context) = PendingIntent.getActivity(ctx, 0, Intent(ctx, GatewayActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)

    fun ongoing(ctx: Context): Notification = Notification.Builder(ctx, PolApp.CHANNEL_LINK)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(ctx.getString(R.string.gw_notification_title))
        .setContentText(ctx.getString(R.string.gw_notification_text))
        .setContentIntent(open(ctx))
        .setOngoing(true)
        .setCategory(Notification.CATEGORY_SERVICE)
        .build()

    /** Pairing needs the owner to compare a code; surface it if the screen is off or another app is open. */
    fun pairingRequest(ctx: Context) {
        val n = Notification.Builder(ctx, PolApp.CHANNEL_PAIRING)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(ctx.getString(R.string.gw_pair_notification))
            .setContentText(ctx.getString(R.string.gw_pair_notification_text))
            .setContentIntent(open(ctx))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_PAIRING, n) }
    }

    fun clearPairing(ctx: Context) { ctx.getSystemService(NotificationManager::class.java).cancel(ID_PAIRING) }
}
