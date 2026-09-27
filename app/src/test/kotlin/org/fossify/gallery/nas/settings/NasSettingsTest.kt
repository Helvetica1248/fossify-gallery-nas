package org.fossify.gallery.nas.settings

import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class NasSettingsTest {
    @get:Rule
    val temporary = TemporaryFolder()
    private val keys = MemoryKeys()
    private val credentials = NasCredentials("private-username", "private-password")

    @Test
    fun credentialsRoundTripWithoutPlaintextOnDisk() {
        val directory = temporary.newFolder()
        val vault = NasCredentialVault(directory, keys)
        val ref = vault.save(credentials)
        val loaded = requireNotNull(vault.load(ref))
        assertEquals(credentials.username, loaded.username)
        assertEquals(credentials.password, loaded.password)
        val disk = File(directory, "$ref.bin").readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(disk.contains(credentials.username))
        assertFalse(disk.contains(credentials.password))
        assertFalse(credentials.toString().contains(credentials.username))
        assertFalse(credentials.toString().contains(credentials.password))
    }

    @Test
    fun corruptBlobAndWrongKeyAreUnavailable() {
        val directory = temporary.newFolder()
        val vault = NasCredentialVault(directory, keys)
        val corrupt = vault.save(credentials)
        val file = File(directory, "$corrupt.bin")
        val bytes = file.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(vault.load(corrupt))
        val wrongKey = vault.save(credentials)
        keys.create(wrongKey)
        assertNull(vault.load(wrongKey))
        File(directory, "$wrongKey.bin").writeBytes(byteArrayOf(1))
        assertNull(vault.load(wrongKey))
    }

    @Test
    fun sourceCreateReloadEditAndDeleteKeepReferencesConsistent() {
        val directory = temporary.newFolder()
        val vault = NasCredentialVault(File(directory, "credentials"), keys)
        val file = File(directory, "sources.bin")
        val store = NasSourceStore(file, vault)
        val first = store.save(draft(), credentials)
        val other = store.save(draft(), NasCredentials("other-user", "other-password"))
        assertEquals(1L, first.source.key.revision)
        val reloaded = NasSourceStore(file, vault).list().first()
        assertEquals(first.source.key, reloaded.source.key)
        assertEquals(first.source.credentialRef, reloaded.source.credentialRef)
        assertEquals("photos", reloaded.source.share)
        assertEquals("family", reloaded.source.root.value)
        assertEquals(NasConnectionMode.VPN, reloaded.source.mode)
        assertFalse(file.readText(Charsets.ISO_8859_1).contains(credentials.username))
        assertFalse(file.readText(Charsets.ISO_8859_1).contains(credentials.password))
        val edited = store.save(reloaded, NasCredentials("updated-user", "updated-password"))
        assertEquals(first.source.key.id, edited.source.key.id)
        assertEquals(2L, edited.source.key.revision)
        assertNotEquals(first.source.credentialRef, edited.source.credentialRef)
        assertNull(vault.load(first.source.credentialRef))
        assertFalse(keys.entries.containsKey(first.source.credentialRef))
        assertEquals("updated-password", store.credentials(edited)?.password)
        assertThrows(IllegalArgumentException::class.java) { store.save(reloaded, credentials) }
        store.delete(edited.source.key)
        assertEquals(listOf(other.source.key), store.list().map { it.source.key })
        assertNull(store.credentials(edited))
        assertFalse(File(directory, "credentials/${edited.source.credentialRef}.bin").exists())
        assertFalse(keys.entries.containsKey(edited.source.credentialRef))
        assertEquals("other-password", store.credentials(other)?.password)
    }

    @Test
    fun missingKeyCanBeRecoveredByReenteringCredentials() {
        val directory = temporary.newFolder()
        val vault = NasCredentialVault(File(directory, "credentials"), keys)
        val store = NasSourceStore(File(directory, "sources.bin"), vault)
        val saved = store.save(draft(), credentials)
        keys.delete(saved.source.credentialRef)
        assertNull(store.credentials(saved))
        val recovered = store.save(saved, credentials)
        assertEquals(2L, recovered.source.key.revision)
        assertEquals(credentials.password, store.credentials(recovered)?.password)
    }

    @Test
    fun failedMetadataPublicationRetainsPreviousSourceAndCredentials() {
        val directory = temporary.newFolder()
        val vault = NasCredentialVault(File(directory, "credentials"), keys)
        val file = File(directory, "sources.bin")
        val store = NasSourceStore(file, vault)
        val saved = store.save(draft(), credentials)
        val invalid = SavedNasSource(saved.source, "x".repeat(70000))
        assertThrows(java.io.UTFDataFormatException::class.java) { store.save(invalid, credentials) }
        assertEquals(saved.source.key, store.list().single().source.key)
        assertEquals(credentials.password, store.credentials(saved)?.password)
        assertEquals(setOf(saved.source.credentialRef), keys.entries.keys)
    }

    @Test
    fun corruptMetadataIsNotSilentlyReplacedWithEmptyList() {
        val directory = temporary.newFolder()
        val file = File(directory, "sources.bin")
        file.writeBytes(byteArrayOf(1))
        val vault = NasCredentialVault(File(directory, "credentials"), keys)
        val store = NasSourceStore(file, vault)
        assertThrows(java.io.EOFException::class.java) { store.list() }
        assertThrows(java.io.EOFException::class.java) { store.save(draft(), credentials) }
        assertArrayEquals(byteArrayOf(1), Files.readAllBytes(file.toPath()))
        assertTrue(keys.entries.isEmpty())
    }

    private fun draft() = SavedNasSource(
        NasSource(
            NasSourceKey(UUID.randomUUID(), 1), NasHost.parse("nas.local"), "photos",
            NasRelativePath.parse("family"), NasConnectionMode.VPN, UUID.randomUUID()
        ),
        "Home photos"
    )

    private class MemoryKeys : NasCredentialKeys {
        val entries = mutableMapOf<UUID, SecretKey>()
        override fun create(ref: UUID): SecretKey = KeyGenerator.getInstance("AES").generateKey().also {
            entries[ref] = it
        }
        override fun load(ref: UUID) = entries[ref]
        override fun delete(ref: UUID) { entries.remove(ref) }
    }
}
