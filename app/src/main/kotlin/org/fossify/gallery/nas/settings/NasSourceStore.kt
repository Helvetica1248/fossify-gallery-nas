package org.fossify.gallery.nas.settings

import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class SavedNasSource(val source: NasSource, val displayName: String) {
    override fun toString() = "SavedNasSource(key=${source.key})"
}

/** Small versioned metadata file, separate from credentials and the Gallery database. */
class NasSourceStore(private val file: File, private val vault: NasCredentialVault) {
    @Synchronized
    fun list(): List<SavedNasSource> {
        if (!file.exists()) return emptyList()
        return DataInputStream(file.inputStream().buffered()).use { input ->
            require(input.readInt() == FORMAT_VERSION) { "Unsupported NAS metadata" }
            val count = input.readInt()
            require(count in 0..MAX_SOURCES) { "Invalid NAS metadata" }
            val sources = List(count) {
                val key = NasSourceKey(UUID.fromString(input.readUTF()), input.readLong())
                val displayName = input.readUTF()
                val source = NasSource(
                    key, NasHost.parse(input.readUTF()), input.readUTF(), NasRelativePath.parse(input.readUTF()),
                    NasConnectionMode.valueOf(input.readUTF()), UUID.fromString(input.readUTF())
                )
                SavedNasSource(source, displayName)
            }
            require(input.read() == -1 && sources.map { it.source.key.id }.distinct().size == sources.size)
            sources
        }
    }

    @Synchronized
    fun credentials(item: SavedNasSource): NasCredentials? = vault.load(item.source.credentialRef)

    @Synchronized
    fun save(draft: SavedNasSource, credentials: NasCredentials): SavedNasSource {
        val items = list().toMutableList()
        val index = items.indexOfFirst { it.source.key.id == draft.source.key.id }
        val previous = items.getOrNull(index)
        require(previous == null || previous.source.key == draft.source.key) { "NAS source changed" }
        require(previous != null || items.size < MAX_SOURCES) { "Too many NAS sources" }
        val revision = previous?.source?.key?.revision?.let { Math.addExact(it, 1) } ?: 1
        val ref = vault.save(credentials)
        val source = draft.source
        val saved = SavedNasSource(
            NasSource(NasSourceKey(source.key.id, revision), source.host, source.share, source.root, source.mode, ref),
            draft.displayName
        )
        if (index < 0) items.add(saved) else items[index] = saved
        var published = false
        try {
            write(items)
            published = true
        } finally {
            if (!published) vault.delete(ref)
        }
        previous?.let { vault.delete(it.source.credentialRef) }
        return saved
    }

    @Synchronized
    fun delete(key: NasSourceKey) {
        val items = list()
        val item = items.firstOrNull { it.source.key.id == key.id } ?: return
        require(item.source.key == key) { "NAS source changed" }
        vault.delete(item.source.credentialRef)
        write(items.filterNot { it.source.key.id == key.id })
    }

    private fun write(items: List<SavedNasSource>) {
        Files.createDirectories(file.parentFile!!.toPath())
        val temporary = Files.createTempFile(file.parentFile!!.toPath(), "sources-", ".tmp")
        try {
            DataOutputStream(Files.newOutputStream(temporary).buffered()).use { output ->
                output.writeInt(FORMAT_VERSION)
                output.writeInt(items.size)
                items.forEach { item ->
                    val source = item.source
                    output.writeUTF(source.key.id.toString())
                    output.writeLong(source.key.revision)
                    output.writeUTF(item.displayName)
                    output.writeUTF(source.host.value)
                    output.writeUTF(source.share)
                    output.writeUTF(source.root.value)
                    output.writeUTF(source.mode.name)
                    output.writeUTF(source.credentialRef.toString())
                }
            }
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val FORMAT_VERSION = 1
        const val MAX_SOURCES = 100
    }
}
