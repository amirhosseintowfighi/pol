package studio.virgule.pol.android.gateway.telecom

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Answer / decline / end from the gateway's call notification. Not exported: only our PendingIntents reach it. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getStringExtra("id") ?: return
        val action = intent.getStringExtra("action") ?: return
        if (action in setOf("answer", "reject", "end")) CallRegistry.perform(id, action)
    }
    companion object { const val ACTION = "studio.virgule.pol.gateway.CALL_ACTION" }
}
