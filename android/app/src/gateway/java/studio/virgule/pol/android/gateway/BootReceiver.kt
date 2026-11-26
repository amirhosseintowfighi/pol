package studio.virgule.pol.android.gateway

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restarts the gateway after a reboot, only if the owner turned on "Start after restart". */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = GatewaySettings(context)
        if (s.enabled && s.startOnBoot) GatewayService.start(context)
    }
}
