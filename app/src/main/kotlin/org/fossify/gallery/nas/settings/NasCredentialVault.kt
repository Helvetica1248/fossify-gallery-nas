package org.fossify.gallery.nas.settings

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The directory must be app-private and excluded from backup (Android: noBackupFilesDir). */
class NasCredentialVault(private val directory: File, private val keys: NasCredentialKeys) {
    fun save(credentials: NasCredentials): UUID {
        val ref = UUID.randomUUID()
        val plain = ByteArrayOutputStream().use { buffer ->
            DataOutputStream(buffer).use {
                it.writeUTF(credentials.username)
                it.writeUTF(credentials.password)
            }
            buffer.toByteArray()
        }
        var saved = false
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, keys.create(ref))
            cipher.updateAAD(ref.toString().toByteArray(Charsets.UTF_8))
            check(cipher.iv.size == IV_BYTES)
            Files.createDirectories(directory.toPath())
            // A fresh reference is published only after the complete blob is written.
            DataOutputStream(file(ref).outputStream()).use {
                it.writeInt(FORMAT_VERSION)
                it.write(cipher.iv)
                it.write(cipher.doFinal(plain))
            }
            saved = true
            return ref
        } finally {
            plain.fill(0)
            if (!saved) delete(ref)
        }
    }

    /** Missing, invalidated or corrupted credentials require re-entry; no plaintext fallback. */
    fun load(ref: UUID): NasCredentials? = try {
        val key = keys.load(ref)
        if (key == null || !file(ref).isFile) {
            null
        } else {
            decrypt(ref, key)
        }
    } catch (_: GeneralSecurityException) {
        null
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decrypt(ref: UUID, key: SecretKey): NasCredentials =
        DataInputStream(file(ref).inputStream()).use { input ->
            require(input.readInt() == FORMAT_VERSION)
            val iv = ByteArray(IV_BYTES).also(input::readFully)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(ref.toString().toByteArray(Charsets.UTF_8))
            decode(cipher.doFinal(input.readBytes()))
        }

    private fun decode(plain: ByteArray): NasCredentials = try {
        DataInputStream(ByteArrayInputStream(plain)).use {
            val result = NasCredentials(it.readUTF(), it.readUTF())
            require(it.read() == -1)
            result
        }
    } finally {
        plain.fill(0)
    }

    fun delete(ref: UUID) {
        // Destroy the key first so a failed file deletion cannot leave decryptable credentials.
        keys.delete(ref)
        Files.deleteIfExists(file(ref).toPath())
    }

    private fun file(ref: UUID) = File(directory, "$ref.bin")

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val FORMAT_VERSION = 1
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
