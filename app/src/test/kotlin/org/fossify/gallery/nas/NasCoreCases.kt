@file:Suppress("MagicNumber", "LongMethod")

package org.fossify.gallery.nas

import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSourceKey
import java.util.UUID

/** The exact same cases run under JUnit and the dependency-free offline JVM runner. */
class NasCoreCase(val name: String, val run: () -> Unit) {
    override fun toString() = name
}

object NasCoreCases {
    fun all(): List<NasCoreCase> =
        modelCases() + catalogCases() + policyCases() + cancellationCases() + transferCases() + readerCases()
}

internal val TEST_SOURCE = NasSourceKey(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)

internal fun entry(path: String = "photos/a.jpg", source: NasSourceKey = TEST_SOURCE) = NasEntry(
    NasRemoteKey(source, NasRelativePath.parse(path)), NasEntryKind.FILE, 4, 1000
)

internal fun expect(value: Boolean, message: String = "Expectation failed") {
    if (!value) throw AssertionError(message)
}

internal fun <T> equal(expected: T, actual: T) {
    if (expected != actual) throw AssertionError("Expected <$expected>, actual <$actual>")
}

internal inline fun <reified T : Throwable> throws(block: () -> Unit): T {
    try {
        block()
    } catch (error: Throwable) {
        if (error is T) return error
        throw AssertionError("Expected ${T::class.java.name}, actual ${error.javaClass.name}", error)
    }
    throw AssertionError("Expected ${T::class.java.name}, nothing was thrown")
}
