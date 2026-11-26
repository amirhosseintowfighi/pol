package studio.virgule.pol.core.crypto

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** HKDF-SHA256 (RFC 5869) composed from the platform's HMAC; CryptoKit's `HKDF<SHA256>` gives identical output. */
object Hkdf {
    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * 32))
        val prk = hmac(if (salt.isEmpty()) ByteArray(32) else salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmac(prk, t + info + byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

/** AES-256-GCM with a 16-byte tag, via the platform provider. */
object AesGcm {
    const val TAG_SIZE = 16

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    /** Returns null on any authentication failure. */
    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray? = try {
        if (sealed.size < TAG_SIZE) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_SIZE * 8, nonce))
            c.updateAAD(aad)
            c.doFinal(sealed)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * A device's long-term identity. On Android the private key lives in the Android Keystore (StrongBox / TEE when
 * available) and never leaves it; on iOS it lives in the Secure Enclave. Only [sign] is needed: ECDH is always done
 * with fresh ephemeral keys, so the identity key never has to be exportable.
 */
interface IdentityKey {
    /** 65-byte uncompressed P-256 public key. */
    val publicKey: ByteArray
    /** ECDSA-SHA256 over [data], ASN.1 DER encoded. */
    fun sign(data: ByteArray): ByteArray
}

/** Software identity for tests and the JVM; apps must use hardware-backed implementations. */
class SoftwareIdentityKey(private val pair: java.security.KeyPair = P256.generateKeyPair()) : IdentityKey {
    override val publicKey: ByteArray = P256.encode(pair.public)
    override fun sign(data: ByteArray): ByteArray {
        val s = java.security.Signature.getInstance("SHA256withECDSA")
        s.initSign(pair.private)
        s.update(data)
        return s.sign()
    }
}

/** Short, human-comparable fingerprint of an identity key ("AB12-CD34-EF56"), shown in device details. */
fun fingerprint(publicKey: ByteArray): String {
    val h = Hash.sha256(publicKey)
    val hex = h.copyOfRange(0, 6).joinToString("") { "%02X".format(it) }
    return hex.chunked(4).joinToString("-")
}
