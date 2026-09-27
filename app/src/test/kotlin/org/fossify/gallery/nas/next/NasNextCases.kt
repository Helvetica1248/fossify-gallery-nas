@file:Suppress("MagicNumber", "LongMethod")

package org.fossify.gallery.nas.next

import org.fossify.gallery.nas.data.NasDirectorySnapshot
import org.fossify.gallery.nas.external.NasExternalTypes
import org.fossify.gallery.nas.external.NasOpenTokens
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.search.NasSearch
import org.fossify.gallery.nas.search.NasSearchFolder
import org.fossify.gallery.nas.search.NasSearchLimits
import org.fossify.gallery.nas.search.NasSearchOptions
import org.fossify.gallery.nas.smb.SmbSeekableHandle
import org.fossify.gallery.nas.transport.NasCancellation
import org.fossify.gallery.nas.transport.NasRangeRead
import org.fossify.gallery.nas.transport.NasSeekableHandle
import java.io.Closeable
import java.io.IOException
import java.util.UUID

class NasNextCase(val name: String, val run: () -> Unit) {
    override fun toString() = name
}

internal object NasNextCases {
    private val source = NasSourceKey(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1)
    private val root = NasRelativePath.ROOT
    private fun entry(path: String, directory: Boolean = false) = NasEntry(
        NasRemoteKey(source, NasRelativePath.parse(path)),
        if (directory) NasEntryKind.DIRECTORY else NasEntryKind.FILE, 8, 1)
    private fun view(folder: NasRelativePath, entries: List<NasEntry>) =
        NasSearchFolder(NasDirectorySnapshot(source, folder, 1, 1, entries))

    fun all(): List<NasNextCase> = listOf(
        NasNextCase("search.recursive-filenames-and-japanese") {
            val child = entry("資料", true)
            val snapshots = mapOf(root to view(root, listOf(child, entry("note.txt"))),
                child.key.path to view(child.key.path, listOf(entry("資料/図版.PDF"), entry("資料/図版.jpg"))))
            var requests = 0
            val result = NasSearch({ path, live, _ -> check(live); requests++; snapshots.getValue(path) })
                .run(source, root, NasSearchOptions("図版"), NasCancellation())
            check(result.entries.size == 2 && requests == 2 && !result.limited && !result.incomplete)
        },
        NasNextCase("search.failed-network-keeps-catalog-and-does-not-retry-auth") {
            val child = entry("child", true)
            val previous = view(root, listOf(child, entry("old.pdf")))
            var liveRequests = 0
            val result = NasSearch({ path, live, _ ->
                if (live) { liveRequests++; previous.copy(failure = NasFailure.AUTHENTICATION_FAILED) }
                else view(path, listOf(entry("child/cached.pdf")))
            }).run(source, root, NasSearchOptions("pdf"), NasCancellation())
            check(liveRequests == 1 && result.entries.size == 2 && result.usedCache && result.incomplete)
        },
        NasNextCase("search.limits-are-visible-and-cancellation-keeps-prior-hits") {
            val token = NasCancellation()
            val first = view(root, listOf(entry("keep.pdf"), entry("child", true)))
            val result = NasSearch({ path, _, _ ->
                if (!path.isRoot) token.cancel()
                first
            }).run(source, root, NasSearchOptions("pdf"), token)
            check(result.cancelled && result.entries.single().name == "keep.pdf")
            val clipped = NasSearch({ _, _, _ -> first }, NasSearchLimits(results = 1))
                .run(source, root, NasSearchOptions("pdf"), NasCancellation())
            check(clipped.limited && clipped.entries.size == 1)
        },
        NasNextCase("search.cached-only-is-bounded-and-foreign-entries-rejected") {
            val bad = entry("wrong.pdf").copy(key = NasRemoteKey(source.copy(revision = 2), root.child("wrong.pdf")))
            val result = NasSearch({ path, live, _ -> check(!live); view(path, listOf(entry("GOOD.PDF"), bad)) })
                .run(source, root, NasSearchOptions("pdf", cachedOnly = true), NasCancellation())
            check(result.usedCache && result.incomplete && result.entries.single().name == "GOOD.PDF")
        },
        NasNextCase("external.types-are-whitelisted-and-no-directory-grants") {
            check(NasExternalTypes.mime(entry("a.PDF")) == "application/pdf")
            check(NasExternalTypes.mime(entry("a.MKV")) == "video/x-matroska")
            check(NasExternalTypes.mime(entry("a.exe")) == null)
            check(NasExternalTypes.mime(entry("folder.pdf", true)) == null)
        },
        NasNextCase("external.tokens-bound-capacity-expire-and-preserve-revision") {
            var now = 0L
            val tokens = NasOpenTokens(2) { now }
            val first = tokens.issue(entry("a.pdf"))
            val second = tokens.issue(entry("b.mp4"))
            check(tokens.get(second)?.key?.source == source)
            tokens.issue(entry("c.pdf"))
            check(tokens.get(first) == null && tokens.get("../a") == null)
            now = 31 * 60 * 1000L
            check(tokens.get(second) == null)
        },
        NasNextCase("ranges.fulfill-short-reads-seek-and-eof-without-temp-file") {
            val bytes = ByteArray(4096) { (it % 251).toByte() }
            val handle = memory(bytes)
            val output = ByteArray(600)
            check(NasRangeRead.read(handle, 1000, output.size, output) == 600)
            check(output.contentEquals(bytes.copyOfRange(1000, 1600)))
            check(NasRangeRead.read(handle, 4090, output.size, output) == 6)
            check(NasRangeRead.read(handle, 4096, output.size, output) == 0)
            check(NasRangeRead.read(handle, 0, 16, output) == 16)
        },
        NasNextCase("ranges.short-eof-or-zero-is-failure-not-truncated-success") {
            val bad = object : NasSeekableHandle {
                override val size = 16L
                override fun readAt(offset: Long, data: ByteArray, start: Int, length: Int) = 0
                override fun close() = Unit
            }
            check(runCatching { NasRangeRead.read(bad, 0, 8, ByteArray(8)) }.exceptionOrNull() is IOException)
        },
        NasNextCase("smb-range-read-only-close-and-cancel") {
            var closed = 0
            val token = NasCancellation()
            val handle = SmbSeekableHandle(8, token, Closeable { closed++ }) { data, offset, start, length ->
                repeat(length) { data[start + it] = (offset + it).toByte() }; length
            }
            val buffer = ByteArray(4)
            check(handle.readAt(4, buffer, 0, 4) == 4 && buffer.toList() == listOf<Byte>(4, 5, 6, 7))
            token.cancel()
            check(runCatching { handle.readAt(0, buffer, 0, 4) }.isFailure)
            handle.close(); handle.close()
            check(closed == 1)
        }
    )

    private fun memory(bytes: ByteArray) = object : NasSeekableHandle {
        override val size = bytes.size.toLong()
        override fun readAt(offset: Long, data: ByteArray, start: Int, length: Int): Int {
            val count = minOf(7, length, bytes.size - offset.toInt())
            bytes.copyInto(data, start, offset.toInt(), offset.toInt() + count)
            return count
        }
        override fun close() = Unit
    }
}
