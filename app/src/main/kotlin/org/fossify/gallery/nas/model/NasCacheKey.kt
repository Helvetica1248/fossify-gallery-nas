package org.fossify.gallery.nas.model

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** A bounded, filename-safe cache identity. It is not proof that remote contents are unchanged. */
object NasCacheKey {
    enum class Variant { ORIGINAL, THUMBNAIL }

    fun forEntry(entry: NasEntry, variant: Variant, decoderRevision: Int = 1): String {
        require(decoderRevision > 0) { "Invalid cache decoder revision" }
        val digest = MessageDigest.getInstance("SHA-256")
        listOf(
            "nas-cache-v1",
            entry.key.source.id.toString(),
            entry.key.source.revision.toString(),
            entry.key.path.value,
            entry.kind.name,
            entry.size?.toString(),
            entry.modifiedEpochMillis?.toString(),
            entry.fileId,
            variant.name,
            decoderRevision.toString()
        ).forEach { field ->
            // Length-prefix every field; absent and empty are different and separators cannot collide.
            val bytes = field?.toByteArray(StandardCharsets.UTF_8)
            digest.update(ByteBuffer.allocate(Int.SIZE_BYTES).putInt(bytes?.size ?: -1).array())
            if (bytes != null) digest.update(bytes)
        }
        val hex = "0123456789abcdef"
        return buildString(64) {
            digest.digest().forEach { byte ->
                val value = byte.toInt() and 255
                append(hex[value ushr 4])
                append(hex[value and 15])
            }
        }
    }
}
