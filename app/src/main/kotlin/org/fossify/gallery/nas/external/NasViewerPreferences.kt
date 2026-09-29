package org.fossify.gallery.nas.external

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal enum class NasViewerKind {
    PDF, VIDEO;

    companion object {
        fun forMime(mime: String): NasViewerKind {
            require(mime == "application/pdf" || mime.startsWith("video/"))
            return if (mime == "application/pdf") PDF else VIDEO
        }
    }
}

/** Device-only component names. Does not store URIs, grants, filenames or account information. Call on IO. */
internal class NasViewerPreferences(private val file: File) {
    fun get(kind: NasViewerKind): String? = synchronized(lock) { read()[kind] }

    fun clearIf(kind: NasViewerKind, expected: String) = synchronized(lock) {
        if (read()[kind] == expected) set(kind, null)
    }

    fun set(kind: NasViewerKind, component: String?) = synchronized(lock) {
        require(component == null || component.length in 1..MAX_COMPONENT_LENGTH)
        // A corrupt preference must be replaceable, without affecting NAS sources or credentials.
        val values = runCatching { read() }.getOrDefault(emptyMap()).toMutableMap()
        if (component == null) values.remove(kind) else values[kind] = component
        Files.createDirectories(file.parentFile!!.toPath())
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), "viewers-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temporary).buffered()).use { output ->
                output.writeInt(VERSION)
                NasViewerKind.entries.forEach { output.writeUTF(values[it].orEmpty()) }
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }

    private fun read(): Map<NasViewerKind, String> {
        if (!file.exists()) return emptyMap()
        require(file.length() <= MAX_FILE_BYTES)
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == VERSION)
            val result = NasViewerKind.entries.mapNotNull { kind ->
                val component = input.readUTF()
                require(component.length <= MAX_COMPONENT_LENGTH)
                component.takeIf { it.isNotEmpty() }?.let { kind to it }
            }.toMap()
            require(input.read() == -1)
            result
        }
    }

    private companion object {
        val lock = Any()
        const val VERSION = 1
        const val MAX_COMPONENT_LENGTH = 512
        const val MAX_FILE_BYTES = 4096
    }
}
