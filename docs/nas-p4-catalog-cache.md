# NAS P4: persistent catalog and private image cache

P4 adds storage and blocking repository APIs for P5. No browser, Gallery integration,
startup network request, recursive scan, background work or prefetch is added.
The P2 source/credential store and GalleryDatabase schema/migrations are unchanged.

## Catalog and generation publication

`AndroidNasRepository.get(context)` lazily opens `noBackupFilesDir/nas-catalog.db`.
This separate Room v1 database has three flat tables:

- `snapshots`: source UUID + revision + folder primary key, generation, successful refresh timestamp.
  Row presence is the complete marker; pending snapshots are never stored.
- `entries`: source UUID + revision + folder + relative path primary key, display name, kind,
  size, modification time and optional remote file ID.
- `cache`: SHA-256 key primary key, source UUID/revision, relative path, variant, local path,
  byte count, last-used timestamp, remote size/mtime/file ID, decoder revision and complete flag.

Listing stays in memory until P3 returns Complete. Children/source identity/duplicates/entry count
are validated before a Room transaction replaces entries and advances the snapshot together.
Snapshot reads also use a transaction. Failed, timed-out, incomplete, cancelled and superseded
refreshes leave the previous successful generation intact. Only Complete(empty) clears entries.
Concurrent refreshes use a process-local last-started ticket; there is no second in-memory catalog.
Old revisions may remain on disk, but reads require an exact source UUID/revision.

Schema starts at v1 without migration infrastructure or Room schema export; future schema changes
must add an explicit migration and revisit schema export. Room/Gallery settings are not changed globally.

## Cache publication and bounds

`cacheDir/nas/{original,thumbnail,tmp}` is private and never registered with MediaStore or exported.
Names use the existing `NasCacheKey` with decoder revision 1; remote names never become local paths.
Identity includes source UUID/revision, root-relative path, kind, size, mtime, remote file ID,
variant and decoder revision. Size/mtime are not cryptographic remote content identity.

Defaults: original quota 512 MiB, thumbnail quota 128 MiB, single original 128 MiB,
single thumbnail reservation 2 MiB. There is no size settings UI.

An explicit fetch reserves capacity in memory before creating `tmp/<key>.part`.
The P3 reader opens the source; NasStreamCopier enforces the expected length and transfer bound.
The output is synced and closed, the image is bounds/sample validated, then Files.move with
ATOMIC_MOVE publishes the file. Only afterwards is complete metadata inserted. Failed or cancelled
operations close resources and remove the part. Unsupported atomic rename fails safely.
A crash between rename and metadata insert can leave an invisible orphan, never a partial cache hit.

Completed files, in-flight reserved bytes (at least actual part size), and abandoned parts count
against capacity. Each variant evicts its oldest unleased files. Orphan complete files are included
in capacity and may be evicted first. Same-key concurrent writes return a failure rather than duplicate
work. The repository shares a two-permit request gate across list and original operations, using
NasReadLimits; all real requests go through SmbNasReader and its existing read-only/VPN policy.

`NasCacheLease : Closeable` protects a file until the caller closes the lease. Source deletion and
corruption invalidation immediately remove metadata, but defer physical removal of leased files.
Source deletion also prevents in-flight cache publication and invalidates refresh tickets. Cleanup
is hooked after successful P2 source deletion and is best effort, so storage failure cannot undo deletion.

Cache acquisition checks complete metadata, exact identity/local path, file existence and size.
No content hash is recomputed on access. P5 must call `invalidateCache` when its decoder detects
same-size corruption. Thumbnail generation detects and invalidates a corrupt cached original.
Old parts are cleaned when the process singleton first initializes. Cache directories are recreated
on demand after OS removal. Lease/reservation state intentionally does not survive process death.

## Thumbnail and offline APIs

Call repository APIs from an I/O executor. `getDirectory`, `getCachedOriginal` and
`getCachedThumbnail` do no network I/O. Cache getters return a lease or null; null is a normal miss.
`refreshDirectory`, `fetchOriginal`, and `fetchThumbnail` are explicit operations; they never retry
in a reconnect loop. `fetchOriginal(force = true)` can refresh unchanged remote metadata, provided
that key is not currently leased. The caller must keep the lease open through all decoder/viewer use.
Disk/Room I/O errors can propagate from storage access and should be handled by the future UI boundary.

Thumbnail generation is lazy for one requested entry. It reuses a cached original or explicitly
fetches one, retaining it in the ordinary original cache. JPEG, PNG, WebP and GIF first-frame use
Android BitmapFactory. Bounds reject dimensions over 32768 or 100 million pixels; power-of-two
sampling limits the intermediate edge to about 512 px, then the longest thumbnail edge is at most
256 px (`THUMBNAIL_EDGE`). Output is PNG, synced and atomically published through the same cache.
OOM is a safe failure. RAW/HEIF/AVIF/JXL/SVG are not promised. EXIF orientation/color fidelity and
animated playback are outside this foundation and should be assessed with the P5 viewer.

## Focused verification

Nine JVM tests cover successful/failed/incomplete/cancelled/superseded generation changes,
partial download cleanup, validated publication and offline reuse, reservations/LRU/active leases,
source and revision separation, missing/corrupt files, a lease acquired during forced replacement,
deletion during a pending write, and lazy
thumbnail reuse/corruption invalidation. Android-only Room and bitmap behavior uses an isolated probe.

The Pixel probe creates local JPEG/PNG fixtures in a separate DB/cache, publishes thumbnails,
checks LRU while a lease is held, then reopens the DB/cache in a new process. Offline reads verify
catalog, original and thumbnails; failed refresh preserves the catalog; source cleanup removes rows
and files. No saved source, credentials or real NAS is accessed. Install Debug + androidTest APKs:

```text
adb shell am instrument -w -e probe p4-write io.github.helvetica1248.fossifygallerynas.debug.test/org.fossify.gallery.nas.smb.SmbRuntimeProbe
adb shell am force-stop io.github.helvetica1248.fossifygallerynas.debug
adb shell am instrument -w -e probe p4-read io.github.helvetica1248.fossifygallerynas.debug.test/org.fossify.gallery.nas.smb.SmbRuntimeProbe
```

The read phase removes probe DB/cache fixtures. Remove the test APK afterwards. Existing default
P3 instrumentation behavior is retained; the P4 phases do not run the P3 socket probe.

## Accepted limitations

- Android can remove cacheDir; offline cache misses are normal. Persistent catalog remains separate.
- Best-effort cleanup can leave small orphan files/metadata or abandoned parts; no recovery journal.
- Same timestamps need not yield strict LRU ordering. No multi-process cache/DB coordination.
- Original retention during thumbnail generation is simple and may evict other unleased originals.
- Low storage, malformed images, absurd dimensions, OOM or busy keys can fail a fetch safely.
- No real NAS/Tailscale P4 Human result is inferred; full browser/viewer Human review belongs to P5.
- Existing warnings remain. New lint suggestions concern StorageManager allocatable space and Bitmap KTX;
  conservative usableSpace checking and the platform bitmap call are intentional simple choices.
- Release is unsigned. Existing Image Minimizer GitHub App configuration failures are out of scope.

## Validation results

Validated on 2026-09-27 against base main `5a678fe36dd3843d6e48dfa874623a1c90d29edc`.

- P1 standalone: 195/195 PASS, exactly one run.
- Gradle JVM: 218/218 PASS (P1 195 + P2 6 + P3 8 + P4 9), zero failures/errors/skips.
- detekt: PASS, no baseline/rule changes.
- lintFossDebug: PASS, 0 unfiltered errors and 53 warnings (51 existing + 2 suggestions above).
- `--no-daemon clean assembleFossDebug`: PASS.
- `--no-daemon assembleFossRelease`: initial PASS including Room KSP and R8; PASS again after the
  final lease-publication fix, alongside the updated Debug build and focused tests.
- Pixel 9 Pro: Debug update installed. Local `p4-write` PASS (PID 29370), force-stop,
  `p4-read` PASS (PID 29413), proving a fresh-process Room/cache reopen. Fixture cleanup passed.
  Test APK removed; ordinary GalleryNAS launch succeeded. Saved NAS settings were not modified.
- Debug signature and 16 KB ZIP alignment: PASS. Debug/Release ZIP integrity: PASS.
- Debug SHA-256: `7ff2e50231f64d82b152ffb3697ce68789a4f8932f6f1a12d12566eeb6a72359`.
- Unsigned Release SHA-256: `f4d38046625262d77d1f7fff60dfe877a56bd7e9a659a5166a4a1af703da9553`.
- Local APKs/logs/probe evidence: `artifacts/nas-p4-pixel/` (ignored, not committed).

Self review kept publication/lease updates under one cache monitor, rejected late publication after
source deletion, restored cache directories after OS removal, and handled interrupted gate waits.
Final review also found that force-download could replace a file leased after reservation. Publication
now rechecks leases under the same monitor and fails safely while preserving the previous completed
file. A focused regression test covers this ordering; Debug/Release are revalidated after the fix.
No STOP condition was observed. No production NAS test or P4 Human PASS is claimed.
Keep the PR Draft for review; merge requires separate approval.
