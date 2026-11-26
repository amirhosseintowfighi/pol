package studio.virgule.pol.core.crypto

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

/**
 * NIST P-256 helpers on top of the platform JCA provider (no custom primitives).
 *
 * Public keys travel as 65-byte uncompressed SEC1/X9.63 points (`0x04 || X || Y`), the same encoding as
 * CryptoKit's `x963Representation`, so the Swift client interoperates without conversion.
 */
object P256 {
    const val PUBLIC_KEY_SIZE = 65
    private const val COORD = 32

    // Curve constants (SEC 2, secp256r1). Used only to reject points that are not on the curve before
    // handing them to the provider (defence against invalid-curve attacks on providers that skip the check).
    private val P = BigInteger("ffffffff00000001000000000000000000000000ffffffffffffffffffffffff", 16)
    private val A = P.subtract(BigInteger.valueOf(3))
    private val B = BigInteger("5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b", 16)

    val params: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    private val random = SecureRandom()

    fun generateKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        return g.generateKeyPair()
    }

    fun encode(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        return byteArrayOf(0x04) + fixed(w.affineX) + fixed(w.affineY)
    }

    /** Parses and validates an uncompressed point. Throws [CryptoException] for anything malformed. */
    fun decode(bytes: ByteArray): PublicKey {
        if (bytes.size != PUBLIC_KEY_SIZE || bytes[0] != 0x04.toByte()) throw CryptoException("bad public key encoding")
        val x = BigInteger(1, bytes.copyOfRange(1, 1 + COORD))
        val y = BigInteger(1, bytes.copyOfRange(1 + COORD, PUBLIC_KEY_SIZE))
        if (x >= P || y >= P) throw CryptoException("coordinate out of range")
        val lhs = y.multiply(y).mod(P)
        val rhs = x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P)
        if (lhs != rhs) throw CryptoException("point not on curve")
        return try {
            KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
        } catch (e: Exception) {
            throw CryptoException("rejected public key", e)
        }
    }

    /** ECDH; returns the 32-byte X coordinate of the shared point. */
    fun ecdh(priv: PrivateKey, peer: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv)
        ka.doPhase(peer, true)
        val s = ka.generateSecret()
        if (s.size != COORD || s.all { it == 0.toByte() }) throw CryptoException("bad shared secret")
        return s
    }

    /** Verifies an ASN.1 DER ECDSA-SHA256 signature (CryptoKit `derRepresentation`). */
    fun verify(publicKey: ByteArray, data: ByteArray, derSignature: ByteArray): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(decode(publicKey))
        s.update(data)
        s.verify(derSignature)
    } catch (_: Exception) {
        false
    }

    private fun fixed(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == COORD -> raw
            raw.size == COORD + 1 && raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
            raw.size < COORD -> ByteArray(COORD - raw.size) + raw
            else -> throw CryptoException("coordinate too large")
        }
    }
}

object Hash {
    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }
}

object Random {
    private val rng = SecureRandom()
    fun bytes(n: Int): ByteArray = ByteArray(n).also { rng.nextBytes(it) }
}

class CryptoException(msg: String, cause: Throwable? = null) : Exception(msg, cause)

/** Constant-time equality for MACs, transcripts and commitments. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)
