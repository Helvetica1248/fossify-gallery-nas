@file:Suppress("MagicNumber", "LongMethod")

package org.fossify.gallery.nas

import org.fossify.gallery.nas.data.NasDirectoryCatalog
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasOpenResult
import org.fossify.gallery.nas.transport.NasReadHandle
import org.fossify.gallery.nas.transport.NasReader
import java.io.ByteArrayInputStream
import java.util.UUID

/** A deterministic transport double. It does not validate real SMB flags, ACLs, or VPN routing. */
private class ScriptedNasReader(private val results: Iterator<NasListingResult>) : NasReader {
    var listingCalls = 0
    override fun list(
        source: NasSource,
        folder: NasRelativePath,
        cancellation: NasCancellation
    ): NasListingResult {
        if (cancellation.isCancelled) return NasListingResult.Cancelled
        source.pathWithinShare(folder)
        listingCalls++
        return results.next()
    }

    override fun open(source: NasSource, entry: NasEntry, cancellation: NasCancellation): NasOpenResult {
        if (cancellation.isCancelled) return NasOpenResult.Cancelled
        if (source.key != entry.key.source) return NasOpenResult.Failed(NasFailure.INVALID_RESPONSE)
        return NasOpenResult.Opened(object : NasReadHandle {
            override val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))
            override fun close() = input.close()
        })
    }
}

internal fun readerCases(): List<NasCoreCase> = buildList {
    fun source() = NasSource(
        TEST_SOURCE, NasHost.parse("nas.example.test"), "photos", NasRelativePath.ROOT,
        NasConnectionMode.VPN, UUID.fromString("00000000-0000-0000-0000-000000000002")
    )
    add(NasCoreCase("fake-reader.disconnect-keeps-catalog") {
        val reader = ScriptedNasReader(listOf(
            NasListingResult.Complete(listOf(entry())),
            NasListingResult.Failed(NasFailure.UNREACHABLE)
        ).iterator())
        val folder = NasRelativePath.parse("photos")
        val catalog = NasDirectoryCatalog(TEST_SOURCE, folder)
        repeat(2) { attempt ->
            catalog.finish(catalog.beginRefresh(), reader.list(source(), folder, NasCancellation()), attempt.toLong())
        }
        equal(1, catalog.current().snapshot.entries.size)
        equal(1L, catalog.current().snapshot.generation)
        equal(NasFailure.UNREACHABLE, catalog.current().lastFailure)
    })
    add(NasCoreCase("fake-reader.cancel-no-enumeration") {
        val reader = ScriptedNasReader(emptyList<NasListingResult>().iterator())
        val cancellation = NasCancellation().apply { cancel() }
        equal(NasListingResult.Cancelled, reader.list(source(), NasRelativePath.ROOT, cancellation))
        equal(0, reader.listingCalls)
    })
    add(NasCoreCase("fake-reader.read-only-handle") {
        val reader = ScriptedNasReader(emptyList<NasListingResult>().iterator())
        val opened = reader.open(source(), entry(), NasCancellation()) as NasOpenResult.Opened
        opened.handle.use { handle ->
            equal(listOf<Byte>(1, 2, 3, 4), handle.input.readBytes().toList())
        }
    })
    add(NasCoreCase("fake-reader.cross-revision-rejected") {
        val reader = ScriptedNasReader(emptyList<NasListingResult>().iterator())
        equal(
            NasOpenResult.Failed(NasFailure.INVALID_RESPONSE),
            reader.open(source(), entry(source = TEST_SOURCE.copy(revision = 2)), NasCancellation())
        )
    })
}
