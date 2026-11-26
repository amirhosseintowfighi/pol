package studio.virgule.pol.android.gateway

import studio.virgule.pol.android.common.SecureStore
import studio.virgule.pol.core.link.TrustStore
import studio.virgule.pol.core.link.TrustedPeer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.Base64

/** Paired clients, persisted in Keystore-sealed storage. Removing one revokes it immediately. */
class PairedDevices(private val store: SecureStore) : TrustStore {
    @Serializable private data class Row(val id: String, val key: String, val name: String, val pairedAt: Long)

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: MutableList<TrustedPeer>? = null

    @Synchronized private fun all(): MutableList<TrustedPeer> {
        cache?.let { return it }
        val rows = store.get(FILE)?.let { runCatching { json.decodeFromString(ListSerializer(Row.serializer()), String(it)) }.getOrNull() }.orEmpty()
        val list = rows.map { TrustedPeer(dec(it.id), dec(it.key), it.name, it.pairedAt) }.toMutableList()
        cache = list
        return list
    }

    @Synchronized private fun save() {
        val rows = all().map { Row(enc(it.id), enc(it.publicKey), it.name, it.pairedAtMs) }
        store.put(FILE, json.encodeToString(ListSerializer(Row.serializer()), rows).toByteArray())
    }

    @Synchronized fun list(): List<TrustedPeer> = all().toList()
    @Synchronized override fun find(id: ByteArray) = all().firstOrNull { it.id.contentEquals(id) }
    @Synchronized override fun add(peer: TrustedPeer) { all().removeAll { it.id.contentEquals(peer.id) }; all().add(peer); save() }
    @Synchronized override fun count() = all().size
    @Synchronized fun remove(id: ByteArray) { all().removeAll { it.id.contentEquals(id) }; save() }
    @Synchronized fun clear() { cache = mutableListOf(); store.remove(FILE) }

    private fun enc(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun dec(s: String) = Base64.getDecoder().decode(s)

    private companion object { const val FILE = "paired_devices" }
}
