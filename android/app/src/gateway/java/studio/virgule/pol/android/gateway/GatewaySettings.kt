package studio.virgule.pol.android.gateway

import android.content.Context
import android.os.Build

/**
 * Owner's choices on the gateway. Non-sensitive flags only (plain SharedPreferences, excluded from backup).
 * Every feature starts off; turning one on is what triggers its permission request.
 */
class GatewaySettings(context: Context) {
    private val p = context.getSharedPreferences("gateway_settings", Context.MODE_PRIVATE)

    var enabled: Boolean get() = p.getBoolean("enabled", false); set(v) = p.edit().putBoolean("enabled", v).apply()
    var startOnBoot: Boolean get() = p.getBoolean("boot", false); set(v) = p.edit().putBoolean("boot", v).apply()
    var messages: Boolean get() = p.getBoolean("messages", false); set(v) = p.edit().putBoolean("messages", v).apply()
    var calls: Boolean get() = p.getBoolean("calls", false); set(v) = p.edit().putBoolean("calls", v).apply()
    var recents: Boolean get() = p.getBoolean("recents", false); set(v) = p.edit().putBoolean("recents", v).apply()
    var shareContacts: Boolean get() = p.getBoolean("contacts", false); set(v) = p.edit().putBoolean("contacts", v).apply()
    var callAudio: Boolean get() = p.getBoolean("call_audio", false); set(v) = p.edit().putBoolean("call_audio", v).apply()
    var carrierCodes: Boolean get() = p.getBoolean("ussd", false); set(v) = p.edit().putBoolean("ussd", v).apply()
    var name: String
        get() = p.getString("name", null) ?: (Build.MODEL ?: "Android phone")
        set(v) = p.edit().putString("name", v.take(40)).apply()

    fun clear() = p.edit().clear().apply()
}
