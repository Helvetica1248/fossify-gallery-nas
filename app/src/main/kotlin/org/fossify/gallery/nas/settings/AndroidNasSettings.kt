package org.fossify.gallery.nas.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Shared store serializes edits across activity recreation and multiple settings windows. */
object AndroidNasSettings {
    private var store: NasSourceStore? = null

    @Synchronized
    fun get(context: Context): NasSourceStore = store ?: run {
        val directory = File(context.applicationContext.noBackupFilesDir, "nas-settings")
        NasSourceStore(
            File(directory, "sources.bin"),
            NasCredentialVault(File(directory, "credentials"), AndroidNasCredentialKeys())
        ).also { store = it }
    }
}

private class AndroidNasCredentialKeys : NasCredentialKeys {
    private fun keyStore() = KeyStore.getInstance(PROVIDER).apply { load(null) }
    private fun alias(ref: UUID) = "nas.credential.$ref"

    override fun create(ref: UUID): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(alias(ref), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_BITS)
                    .build()
            )
            generateKey()
        }

    override fun load(ref: UUID) = keyStore().getKey(alias(ref), null) as? SecretKey

    override fun delete(ref: UUID) = keyStore().deleteEntry(alias(ref))

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_BITS = 256
    }
}
