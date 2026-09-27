package org.fossify.gallery.nas

import org.fossify.gallery.nas.model.NasCacheKey
import org.fossify.gallery.nas.model.NasConnectionMode
import org.fossify.gallery.nas.model.NasEntryKind
import org.fossify.gallery.nas.model.NasHost
import org.fossify.gallery.nas.model.NasListingResult
import org.fossify.gallery.nas.model.NasRelativePath
import org.fossify.gallery.nas.model.NasRemoteKey
import org.fossify.gallery.nas.model.NasSource
import org.fossify.gallery.nas.model.NasSourceKey
import org.fossify.gallery.nas.transport.NasReader
import java.util.UUID

internal fun modelCases(): List<NasCoreCase> = buildList {
    listOf(
        "",
        "photos",
        "photos/a.jpg",
        "写真/画像 01.jpg",
        "a#b%20c.jpg",
        "%2e%2e/literal.jpg",
        "絵/🐿️.png"
    ).forEachIndexed { i, path ->
        add(NasCoreCase("path.accept.$i") { equal(path, NasRelativePath.parse(path).value) })
    }
    listOf(
        "/photos",
        "photos/",
        "a//b",
        ".",
        "..",
        "a/../b",
        "./b",
        "a/./b",
        "C:/a",
        "a\\b",
        "\\\\host\\share",
        "a:b",
        "a\u0000b",
        "a\nb",
        "a\u007fb",
        "a\u0080b",
        "name.",
        "name ",
        "a/ b ",
        "a\uD800",
        "a\uDC00",
        "a".repeat(256),
        "a?b",
        "a*b",
        "a|b",
        "a\"b"
    ).forEachIndexed { i, path ->
        add(NasCoreCase("path.reject.$i") { throws<IllegalArgumentException> { NasRelativePath.parse(path) } })
    }
    add(NasCoreCase("path.root-parent") { equal(null, NasRelativePath.ROOT.parent) })
    add(NasCoreCase("path.direct-parent") {
        equal(NasRelativePath.parse("photos"), NasRelativePath.parse("photos/a.jpg").parent)
    })
    add(NasCoreCase("path.case-sensitive") { expect(NasRelativePath.parse("A.jpg") != NasRelativePath.parse("a.jpg")) })
    add(NasCoreCase("path.unicode-not-normalized") {
        expect(NasRelativePath.parse("é.jpg") != NasRelativePath.parse("e\u0301.jpg"))
    })
    add(NasCoreCase("path.child-not-relative-path") {
        throws<IllegalArgumentException> { NasRelativePath.ROOT.child("a/b") }
    })
    add(NasCoreCase("path.join") {
        equal("root/photos/a.jpg", NasRelativePath.parse("root").resolve(NasRelativePath.parse("photos/a.jpg")).value)
    })
    add(NasCoreCase("path.root-join") {
        equal("root", NasRelativePath.parse("root").resolve(NasRelativePath.ROOT).value)
    })
    add(NasCoreCase("path.max-total") {
        throws<IllegalArgumentException> { NasRelativePath.parse(List(129) { "a".repeat(255) }.joinToString("/")) }
    })
    add(NasCoreCase("path.identity-set") {
        equal(1, setOf(NasRelativePath.parse("a"), NasRelativePath.ROOT.child("a")).size)
    })
    listOf(
        "nas",
        "NAS.EXAMPLE.TEST",
        "192.0.2.1",
        "2001:db8::1",
        "::1",
        "nas-01.tail.example"
    ).forEachIndexed { i, host ->
        add(NasCoreCase("host.accept.$i") { NasHost.parse(host) })
    }
    listOf(
        "",
        " nas",
        "nas ",
        "smb://nas",
        "user:password@nas",
        "nas/a",
        "nas\\a",
        "[::1]",
        "nas:445",
        "256.1.1.1",
        "01.2.3.4",
        "123",
        "nas..test",
        "-nas",
        "nas-",
        "::xyz",
        "::::",
        "fe80::1%wlan0",
        "日本語",
        "a".repeat(64)
    ).forEachIndexed { i, host ->
        add(NasCoreCase("host.reject.$i") { throws<IllegalArgumentException> { NasHost.parse(host) } })
    }
    add(NasCoreCase("host.canonical-case") {
        equal(NasHost.parse("NAS.EXAMPLE.TEST"), NasHost.parse("nas.example.test"))
    })
    add(NasCoreCase("host.redacted-output") { expect("example" !in NasHost.parse("nas.example.test").toString()) })
    add(NasCoreCase("source.redacted-output") {
        val source = NasSource(
            TEST_SOURCE,
            NasHost.parse("nas.example.test"),
            "private-share",
            NasRelativePath.parse("private-folder"),
            NasConnectionMode.VPN,
            UUID.randomUUID()
        )
        expect("private" !in source.toString() && "example" !in source.toString())
        equal(445, source.port)
        equal("private-folder/a.jpg", source.pathWithinShare(NasRelativePath.parse("a.jpg")).value)
    })
    listOf("", "a/b", "../x", "a:stream").forEachIndexed { i, share ->
        add(NasCoreCase("source.share-reject.$i") {
            throws<IllegalArgumentException> { NasSource(
                TEST_SOURCE,
                NasHost.parse("nas"),
                share,
                NasRelativePath.ROOT,
                NasConnectionMode.LAN,
                UUID.randomUUID()
            ) }
        })
    }
    add(NasCoreCase("source.revision-positive") {
        throws<IllegalArgumentException> { NasSourceKey(TEST_SOURCE.id, 0) }
    })
    add(NasCoreCase("entry.root-rejected") {
        throws<IllegalArgumentException> { NasRemoteKey(TEST_SOURCE, NasRelativePath.ROOT) }
    })
    add(NasCoreCase("entry.size-rejected") { throws<IllegalArgumentException> { entry().copy(size = -1) } })
    add(NasCoreCase("entry.date-rejected") {
        throws<IllegalArgumentException> { entry().copy(modifiedEpochMillis = -1) }
    })
    add(NasCoreCase("entry.unknown-metadata") { expect(!entry().copy(size = null).hasVersionMetadata) })
    add(NasCoreCase("cache.safe-key") {
        expect(NasCacheKey.forEntry(entry(), NasCacheKey.Variant.ORIGINAL).matches(Regex("[a-f0-9]{64}")))
    })
    add(NasCoreCase("cache.deterministic") {
        equal(
            NasCacheKey.forEntry(entry(), NasCacheKey.Variant.ORIGINAL),
            NasCacheKey.forEntry(entry(), NasCacheKey.Variant.ORIGINAL)
        )
    })
    add(NasCoreCase("cache.identity-fields") {
        val base = entry()
        val originals = listOf(
            base, base.copy(key = base.key.copy(source = TEST_SOURCE.copy(revision = 2))),
            base.copy(key = base.key.copy(source = TEST_SOURCE.copy(id = UUID.randomUUID()))),
            entry("photos/A.jpg"), base.copy(size = 5), base.copy(modifiedEpochMillis = 1001),
            base.copy(fileId = "abc"), base.copy(kind = NasEntryKind.DIRECTORY),
            base.copy(size = null), base.copy(modifiedEpochMillis = null)
        ).map { NasCacheKey.forEntry(it, NasCacheKey.Variant.ORIGINAL) }
        equal(originals.size, originals.toSet().size)
    })
    add(NasCoreCase("cache.variant-separated") {
        expect(NasCacheKey.forEntry(
            entry(),
            NasCacheKey.Variant.ORIGINAL
        ) != NasCacheKey.forEntry(entry(), NasCacheKey.Variant.THUMBNAIL))
    })
    add(NasCoreCase("cache.decoder-separated") {
        expect(NasCacheKey.forEntry(
            entry(),
            NasCacheKey.Variant.ORIGINAL,
            1
        ) != NasCacheKey.forEntry(entry(), NasCacheKey.Variant.ORIGINAL, 2))
    })
    add(NasCoreCase("result.defensive-copy") {
        val list = arrayListOf(entry())
        val result = NasListingResult.Complete(list)
        list.clear()
        equal(1, result.entries.size)
        throws<UnsupportedOperationException> { (result.entries as MutableList).clear() }
    })
    add(NasCoreCase("reader.no-write-api") {
        equal(setOf("list", "open"), NasReader::class.java.declaredMethods.map { it.name }.toSet())
    })
}
