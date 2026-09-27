package org.fossify.gallery.nas.next

import org.fossify.gallery.nas.cache.NasDiskCache
import org.fossify.gallery.nas.catalog.CacheRow
import org.fossify.gallery.nas.catalog.NasCacheIndex
import org.fossify.gallery.nas.model.NasCacheKey.Variant
import org.fossify.gallery.nas.model.NasEntry
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.transport.NasCancellation
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.util.UUID

class NasCacheBudgetTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun recordBudgetPrunesMissingRowsAndProtectsLeases() {
        val rows = LinkedHashMap<String, CacheRow>()
        val index = object : NasCacheIndex {
            override fun get(key: String) = rows[key]
            override fun all() = rows.values.toList()
            override fun put(row: CacheRow) { rows[row.key] = row }
            override fun remove(key: String) { rows.remove(key) }
        }
        val cache = NasDiskCache(temp.newFolder(), index, maxIndexRows = 2)
        val source = NasSourceKey(UUID.randomUUID(), 1)
        fun entry(name: String) = NasEntry(NasRemoteKey(source, NasRelativePath.parse(name)),
            NasEntryKind.FILE, 1, 1)
        fun publish(name: String) = cache.reserve(entry(name), Variant.ORIGINAL).use {
            it.part.writeBytes(byteArrayOf(1)); cache.publish(it, NasCancellation())
        }
        publish("leased.jpg").use { protected ->
            publish("old.jpg").close()
            publish("new.jpg").close()
            assertEquals(2, rows.size)
            assertTrue(protected.file.exists())
            assertNull(cache.acquire(entry("old.jpg"), Variant.ORIGINAL))
        }
        cache.acquire(entry("new.jpg"), Variant.ORIGINAL)!!.use { it.file.delete() }
        publish("next.jpg").close()
        assertEquals(2, rows.size)
        assertTrue(rows.values.none { it.path == "new.jpg" })
    }
}
