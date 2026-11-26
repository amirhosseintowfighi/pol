package studio.virgule.pol.android.common

import android.util.Log

/**
 * The only logging entry point. Messages must be static descriptions: never phone numbers, message text, contact
 * names, keys, codes or device addresses. Logcat stays on the device; nothing is uploaded. Debug-level output is
 * stripped from release builds by R8 (proguard-rules.pro).
 */
object SafeLog {
    private const val TAG = "Pol"
    fun d(area: String, msg: String) { Log.d(TAG, "[$area] $msg") }
    fun w(area: String, msg: String) { Log.w(TAG, "[$area] $msg") }
}
