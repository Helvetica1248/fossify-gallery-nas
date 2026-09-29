package org.fossify.gallery.nas.external

import org.fossify.gallery.nas.model.NasEntry
import java.util.UUID

/** Process-local, bounded capabilities. Neither credentials nor host/share are encoded in a URI. */
internal class NasOpenTokens(
    private val capacity: Int = MAX_GRANTS,
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }
) {
    private data class Grant(val entry: NasEntry, val expires: Long)
    private val grants = LinkedHashMap<String, Grant>()

    @Synchronized
    fun issue(entry: NasEntry): String {
        require(NasExternalTypes.mime(entry) != null && entry.size != null && entry.size >= 0)
        prune()
        while (grants.size >= capacity) grants.remove(grants.keys.first())
        val token = UUID.randomUUID().toString()
        grants[token] = Grant(entry, now() + TTL_MILLIS)
        return token
    }

    @Synchronized
    fun get(token: String): NasEntry? { prune(); return grants[token]?.entry }

    @Synchronized
    fun revoke(token: String) { grants.remove(token) }

    private fun prune() { grants.entries.removeAll { it.value.expires <= now() } }

    init { require(capacity > 0) }
    private companion object {
        const val MAX_GRANTS = 32
        const val TTL_MILLIS = 30 * 60 * 1000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
