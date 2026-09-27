package org.fossify.gallery.nas.search

import org.fossify.gallery.nas.data.NasDirectorySnapshot
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.transport.NasCancellation
import java.util.ArrayDeque
import java.util.concurrent.CancellationException

private const val SEARCH_FOLDERS = 256
private const val SEARCH_ENTRIES = 100_000
private const val SEARCH_RESULTS = 500
private const val SEARCH_MILLIS = 120_000L

internal data class NasSearchFolder(val snapshot: NasDirectorySnapshot?, val failure: NasFailure? = null)
internal data class NasSearchOptions(val text: String, val recursive: Boolean = true, val cachedOnly: Boolean = false)
internal data class NasSearchLimits(
    val folders: Int = SEARCH_FOLDERS, val entries: Int = SEARCH_ENTRIES,
    val results: Int = SEARCH_RESULTS, val millis: Long = SEARCH_MILLIS
) {
    init { require(folders > 0 && entries > 0 && results > 0 && millis > 0) }
}
internal data class NasSearchResult(
    val entries: List<NasEntry>, val folders: Int, val limited: Boolean, val cancelled: Boolean,
    val incomplete: Boolean, val usedCache: Boolean, val failure: NasFailure?
)

/** Explicit, bounded filename search. No content download, regex, background job or mutation. */
internal class NasSearch(
    private val load: (NasRelativePath, Boolean, NasCancellation) -> NasSearchFolder,
    private val limits: NasSearchLimits = NasSearchLimits(),
    private val now: () -> Long = { System.nanoTime() / NANOS_PER_MILLI }
) {
    fun run(source: NasSourceKey, root: NasRelativePath, options: NasSearchOptions,
            cancellation: NasCancellation): NasSearchResult {
        require(options.text.isNotBlank())
        val state = SearchState(options.cachedOnly, now())
        state.queue.add(root)
        state.seen.add(root)
        try {
            while (state.queue.isNotEmpty() && !state.limitReached()) {
                cancellation.throwIfCancelled()
                val path = state.queue.removeFirst()
                val view = load(path, !state.cached, cancellation)
                cancellation.throwIfCancelled()
                state.visited++
                state.observe(view)
                val snapshot = view.snapshot
                if (snapshot != null && snapshot.source == source && snapshot.folder == path) {
                    collect(snapshot, options, state, cancellation)
                } else {
                    state.incomplete = true
                }
            }
        } catch (_: CancellationException) {
            state.cancelled = true
        }
        val limited = state.clipped || state.queue.isNotEmpty() && !state.cancelled
        return NasSearchResult(state.matches, state.visited, limited, state.cancelled,
            state.incomplete, state.cached, state.failure)
    }

    private fun collect(snapshot: NasDirectorySnapshot, options: NasSearchOptions, state: SearchState,
                        cancellation: NasCancellation) {
        for (entry in snapshot.entries) {
            cancellation.throwIfCancelled()
            if (state.examined >= limits.entries || state.matches.size >= limits.results) {
                state.clipped = true
                break
            }
            state.examined++
            if (entry.key.source != snapshot.source || entry.key.path.parent != snapshot.folder) {
                state.incomplete = true
                continue
            }
            if (entry.name.contains(options.text.trim(), ignoreCase = true)) state.matches.add(entry)
            if (options.recursive && entry.kind == NasEntryKind.DIRECTORY) state.enqueue(entry.key.path)
        }
    }

    private inner class SearchState(var cached: Boolean, private val start: Long) {
        val matches = ArrayList<NasEntry>()
        val queue = ArrayDeque<NasRelativePath>()
        val seen = HashSet<NasRelativePath>()
        var visited = 0
        var examined = 0
        var clipped = false
        var incomplete = false
        var cancelled = false
        var failure: NasFailure? = null

        fun limitReached(): Boolean = visited >= limits.folders || examined >= limits.entries ||
            matches.size >= limits.results || now() - start >= limits.millis

        fun enqueue(path: NasRelativePath) {
            if (path in seen) return
            if (seen.size >= limits.folders) clipped = true else { seen.add(path); queue.add(path) }
        }

        fun observe(view: NasSearchFolder) {
            if (view.failure == null) return
            incomplete = true
            if (failure == null) failure = view.failure
            // Stop repeated authentication/connection attempts, but continue traversing saved snapshots.
            if (view.failure != NasFailure.ACCESS_DENIED && view.failure != NasFailure.NOT_FOUND) cached = true
        }
    }

    private companion object { const val NANOS_PER_MILLI = 1_000_000L }
}
