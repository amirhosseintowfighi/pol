package studio.virgule.pol.android.common

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key-value files in no-backup storage, sealed with an AES-256-GCM key held in the Android
 * Keystore. Used for the list of paired devices and pairing metadata. Message, call and contact content is never
 * written here (or anywhere) by the client; the gateway reads it from Android's own providers on demand.
 */
class SecureStore(context: Context) {
    private val dir = File(context.noBackupFilesDir, "secure").apply { mkdirs() }

    fun put(name: String, value: ByteArray) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        c.updateAAD(name.toByteArray())
        val sealed = c.iv + c.doFinal(value)
        val tmp = File(dir, "$name.tmp")
        tmp.writeBytes(sealed)
        if (!tmp.renameTo(File(dir, name))) { File(dir, name).delete(); tmp.renameTo(File(dir, name)) }
    }

    fun get(name: String): ByteArray? {
        val f = File(dir, name)
        if (!f.exists()) return null
        return try {
            val b = f.readBytes()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
            c.updateAAD(name.toByteArray())
            c.doFinal(b, 12, b.size - 12)
        } catch (e: Exception) {
            SafeLog.w("store", "unreadable entry dropped")
            f.delete()
            null
        }
    }

    fun remove(name: String) { File(dir, name).delete() }

    /** Deletes every entry and the wrapping key. */
    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
    }

    @Synchronized
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        g.init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return g.generateKey()
    }

    private companion object { const val ALIAS = "pol.store.v1" }
}
