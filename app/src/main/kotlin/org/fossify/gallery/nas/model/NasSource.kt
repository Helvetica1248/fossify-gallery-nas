package org.fossify.gallery.nas.model

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.UUID

/** Validation only: parsing a host never performs DNS or probes the network. */
class NasHost private constructor(val value: String) {
    override fun equals(other: Any?) = other is NasHost && value == other.value
    override fun hashCode() = value.hashCode()
    override fun toString() = "NasHost(<redacted>)"

    companion object {
        fun parse(value: String): NasHost {
            require(value.isNotEmpty() && value.length <= 253) { "Invalid NAS host" }
            require(value == value.trim() && value.none { it.code > 127 }) { "Invalid NAS host" }
            val valid = when {
                ':' in value -> validIpv6Literal(value)
                value.all { it.isDigit() || it == '.' } -> validIpv4Literal(value)
                else -> value.split('.').all { label ->
                    label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                        label.all { it.isLetterOrDigit() || it == '-' }
                }
            }
            require(valid) { "Invalid NAS host" }
            return NasHost(value.lowercase(Locale.ROOT))
        }

        private fun validIpv4Literal(value: String): Boolean {
            val parts = value.split('.')
            return parts.size == 4 && parts.all {
                it.isNotEmpty() && (it.length == 1 || it[0] != '0') && (it.toIntOrNull() ?: -1) in 0..255
            }
        }

        private fun validIpv6Literal(value: String): Boolean {
            if (value.any { it !in "0123456789abcdefABCDEF:." }) return false
            return try {
                URI("smb://[$value]").host != null
            } catch (ignored: URISyntaxException) {
                false
            }
        }
    }
}

enum class NasConnectionMode { LAN, VPN }

data class NasSourceKey(val id: UUID, val revision: Long) {
    init {
        require(revision > 0) { "NAS configuration revision must be positive" }
    }
}

/** No password or username belongs in this object, its URI, or its generated log output. */
class NasSource(
    val key: NasSourceKey,
    val host: NasHost,
    val share: String,
    val root: NasRelativePath,
    val mode: NasConnectionMode,
    val credentialRef: UUID
) {
    init {
        val sharePath = NasRelativePath.parse(share)
        require(!sharePath.isRoot && sharePath.parent == NasRelativePath.ROOT) { "Invalid NAS share" }
    }

    val port: Int get() = 445

    fun pathWithinShare(relative: NasRelativePath): NasRelativePath = root.resolve(relative)

    override fun toString() = "NasSource(id=${key.id}, revision=${key.revision}, mode=$mode)"
}
