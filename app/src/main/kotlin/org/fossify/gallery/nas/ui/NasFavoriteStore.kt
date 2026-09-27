package org.fossify.gallery.nas.ui

import org.fossify.gallery.nas.model.NasRelativePath
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal data class NasFavoriteFolder(val sourceId: UUID, val path: NasRelativePath)

/** Device-only bookmarks. Source edits retain bookmarks; deleted sources are filtered by the UI. */
internal class NasFavoriteStore(private val file: File) {
    fun list(): List<NasFavoriteFolder> = synchronized(lock) {
        if (!file.exists()) return@synchronized emptyList()
        DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == VERSION)
            val count = input.readInt()
            require(count in 0..MAX_FAVORITES)
            List(count) {
                NasFavoriteFolder(UUID(input.readLong(), input.readLong()), NasRelativePath.parse(input.readUTF()))
            }.also { require(input.read() == -1 && it.distinct().size == it.size) }
        }
    }

    fun set(folder: NasFavoriteFolder, favorite: Boolean) = synchronized(lock) {
        val items = list().toMutableList()
        if (favorite && folder !in items) items.add(folder)
        if (!favorite) items.remove(folder)
        require(items.size <= MAX_FAVORITES)
        Files.createDirectories(file.parentFile!!.toPath())
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), "favorites-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temporary).buffered()).use { output ->
                output.writeInt(VERSION)
                output.writeInt(items.size)
                items.forEach {
                    output.writeLong(it.sourceId.mostSignificantBits)
                    output.writeLong(it.sourceId.leastSignificantBits)
                    output.writeUTF(it.path.value)
                }
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        val lock = Any()
        const val VERSION = 1
        const val MAX_FAVORITES = 1000
    }
}
