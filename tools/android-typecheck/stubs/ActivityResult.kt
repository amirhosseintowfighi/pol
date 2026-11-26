@file:Suppress("unused")
package androidx.activity.result.contract
abstract class ActivityResultContract<I, O>
object ActivityResultContracts {
    class RequestMultiplePermissions : ActivityResultContract<Array<String>, Map<String, Boolean>>()
    class RequestPermission : ActivityResultContract<String, Boolean>()
    class StartActivityForResult : ActivityResultContract<android.content.Intent, Any?>()
}
