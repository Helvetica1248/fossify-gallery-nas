package org.fossify.gallery.nas

import org.fossify.gallery.nas.data.NasDirectoryCatalog
import org.fossify.gallery.nas.model.NasFailure
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath

internal fun catalogCases(): List<NasCoreCase> = buildList {
    fun catalog() = NasDirectoryCatalog(TEST_SOURCE, NasRelativePath.parse("photos"))
    fun populated() = catalog().also { it.finish(it.beginRefresh(), NasListingResult.Complete(listOf(entry())), 1000) }
    add(NasCoreCase("catalog.never-seen-is-not-empty-success") {
        val state = catalog().current()
        equal(null, state.snapshot.lastSuccessfulRefreshMillis)
        equal(0L, state.snapshot.generation)
        expect(state.snapshot.entries.isEmpty())
    })
    NasFailure.values().forEach { reason ->
        add(NasCoreCase("catalog.failure-preserves.$reason") {
            val catalog = populated()
            val before = catalog.current().snapshot
            expect(catalog.finish(catalog.beginRefresh(), NasListingResult.Failed(reason), 2000))
            expect(catalog.current().snapshot === before)
            equal(reason, catalog.current().lastFailure)
            expect(!catalog.current().isRefreshing)
        })
    }
    add(NasCoreCase("catalog.first-failure") {
        val catalog = catalog()
        catalog.finish(catalog.beginRefresh(), NasListingResult.Failed(NasFailure.UNREACHABLE), 1000)
        equal(null, catalog.current().snapshot.lastSuccessfulRefreshMillis)
    })
    add(NasCoreCase("catalog.cancel-preserves") {
        val catalog = populated()
        val before = catalog.current().snapshot
        catalog.finish(catalog.beginRefresh(), NasListingResult.Cancelled, 2000)
        expect(catalog.current().snapshot === before)
        expect(catalog.current().wasCancelled)
    })
    add(NasCoreCase("catalog.incomplete-preserves") {
        val catalog = populated()
        val before = catalog.current().snapshot
        catalog.finish(catalog.beginRefresh(), NasListingResult.Incomplete, 2000)
        expect(catalog.current().snapshot === before)
    })
    add(NasCoreCase("catalog.confirmed-empty-replaces") {
        val catalog = populated()
        catalog.finish(catalog.beginRefresh(), NasListingResult.Complete(emptyList()), 2000)
        equal(2L, catalog.current().snapshot.generation)
        equal(2000L, catalog.current().snapshot.lastSuccessfulRefreshMillis)
        expect(catalog.current().snapshot.entries.isEmpty())
    })
    add(NasCoreCase("catalog.refresh-start-retains") {
        val catalog = populated()
        val before = catalog.current().snapshot
        catalog.beginRefresh()
        expect(catalog.current().snapshot === before && catalog.current().isRefreshing)
    })
    add(NasCoreCase("catalog.stale-success-ignored") {
        val catalog = populated()
        val first = catalog.beginRefresh()
        val second = catalog.beginRefresh()
        expect(!catalog.finish(first, NasListingResult.Complete(emptyList()), 2000))
        expect(catalog.current().isRefreshing)
        expect(catalog.finish(second, NasListingResult.Complete(listOf(entry("photos/b.jpg"))), 3000))
        equal("b.jpg", catalog.current().snapshot.entries.single().name)
    })
    add(NasCoreCase("catalog.stale-failure-ignored") {
        val catalog = populated()
        val first = catalog.beginRefresh()
        catalog.finish(catalog.beginRefresh(), NasListingResult.Complete(emptyList()), 3000)
        expect(!catalog.finish(first, NasListingResult.Failed(NasFailure.UNREACHABLE), 4000))
        equal(null, catalog.current().lastFailure)
    })
    add(NasCoreCase("catalog.duplicate-completion-ignored") {
        val catalog = populated()
        val ticket = catalog.beginRefresh()
        expect(catalog.finish(ticket, NasListingResult.Cancelled, 1000))
        expect(!catalog.finish(ticket, NasListingResult.Complete(emptyList()), 2000))
    })
    add(NasCoreCase("catalog.foreign-ticket-ignored") {
        val catalog = populated()
        catalog.beginRefresh()
        expect(!catalog.finish(catalog().beginRefresh(), NasListingResult.Complete(emptyList()), 2000))
    })
    listOf(
        listOf(entry(), entry()), listOf(entry("elsewhere/a.jpg")), listOf(entry("photos/nested/a.jpg")),
        listOf(entry("photos/a.jpg", TEST_SOURCE.copy(revision = 2)))
    ).forEachIndexed { i, entries ->
        add(NasCoreCase("catalog.reject-invalid-listing.$i") {
            val catalog = populated()
            val before = catalog.current().snapshot
            catalog.finish(catalog.beginRefresh(), NasListingResult.Complete(entries), 2000)
            expect(catalog.current().snapshot === before)
            equal(NasFailure.INVALID_RESPONSE, catalog.current().lastFailure)
        })
    }
    add(NasCoreCase("catalog.case-distinct-entries") {
        val catalog = catalog()
        catalog.finish(catalog.beginRefresh(), NasListingResult.Complete(listOf(entry(), entry("photos/A.jpg"))), 1000)
        equal(2, catalog.current().snapshot.entries.size)
    })
    add(NasCoreCase("catalog.limit-keeps-old-snapshot") {
        val catalog = NasDirectoryCatalog(TEST_SOURCE, NasRelativePath.parse("photos"), 1)
        catalog.finish(catalog.beginRefresh(), NasListingResult.Complete(listOf(entry())), 1000)
        val old = catalog.current().snapshot
        catalog.finish(catalog.beginRefresh(), NasListingResult.Complete(listOf(entry(), entry("photos/b.jpg"))), 2000)
        expect(catalog.current().snapshot === old)
    })
    add(NasCoreCase("catalog.immutable-snapshot") {
        val snapshot = populated().current().snapshot
        throws<UnsupportedOperationException> { (snapshot.entries as MutableList).clear() }
    })
    add(NasCoreCase("catalog.viewer-generation-stable") {
        val catalog = populated()
        val viewer = catalog.current().snapshot
        catalog.finish(catalog.beginRefresh(), NasListingResult.Complete(listOf(entry("photos/b.jpg"))), 2000)
        equal("a.jpg", viewer.entries.single().name)
        equal(1L, viewer.generation)
    })
    listOf(10_000, 50_000).forEach { count ->
        add(NasCoreCase("catalog.large-directory.$count") {
            val catalog = catalog()
            catalog.finish(
                catalog.beginRefresh(),
                NasListingResult.Complete(List(count) { entry("photos/$it.jpg") }),
                1000
            )
            equal(count, catalog.current().snapshot.entries.size)
        })
    }
}
