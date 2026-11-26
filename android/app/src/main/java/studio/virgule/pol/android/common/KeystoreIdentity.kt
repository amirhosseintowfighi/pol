package studio.virgule.pol.android.common

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import studio.virgule.pol.core.crypto.IdentityKey
import studio.virgule.pol.core.crypto.P256
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Long-term device identity in the Android Keystore. The private key is generated inside the Keystore (StrongBox
 * when the phone has one, else the TEE), is not exportable and can only sign. Deleting it (Settings › Reset) makes
 * every paired device distrust this phone.
 */
class KeystoreIdentity private constructor(private val privateKey: PrivateKey, override val publicKey: ByteArray) : IdentityKey {

    override fun sign(data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(privateKey)
        s.update(data)
        return s.sign()
    }

    companion object {
        private const val ALIAS = "pol.identity.v1"

        @Synchronized
        fun load(): KeystoreIdentity {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (!ks.containsAlias(ALIAS)) generate()
            val entry = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
            return KeystoreIdentity(entry.privateKey, P256.encode(entry.certificate.publicKey))
        }

        fun delete() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS)
        }

        private fun generate() {
            fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
                .build()
            val g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            try {
                g.initialize(spec(strongBox = true))
                g.generateKeyPair()
            } catch (e: Exception) {
                // No StrongBox (StrongBoxUnavailableException, or ProviderException on some devices): use the TEE.
                g.initialize(spec(strongBox = false))
                g.generateKeyPair()
            }
        }
    }
}
