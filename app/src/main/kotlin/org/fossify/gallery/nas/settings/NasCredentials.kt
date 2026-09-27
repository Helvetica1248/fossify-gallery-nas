package org.fossify.gallery.nas.settings

import java.util.UUID
import javax.crypto.SecretKey

/** Only the encrypted vault may persist these values. */
class NasCredentials(val username: String, val password: String) {
    override fun toString() = "NasCredentials(redacted)"
}

interface NasCredentialKeys {
    fun create(ref: UUID): SecretKey
    fun load(ref: UUID): SecretKey?
    fun delete(ref: UUID)
}
