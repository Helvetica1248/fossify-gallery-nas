# NAS filename search and external PDF/video viewing

Base: `5df3d20881fc8ffa23cf2805b094262084bed7de`. Branch: `codex/nas-p6-search-external-viewers`.
Delete/move is deferred. This feature never writes to the NAS, changes its ACLs, or uses other credentials.

## User-visible behavior

Open **NAS Albums → source/folder → menu → Search files**. Searches match file/folder names, case-insensitively,
not document text. Subfolders are optional. Searches start only on Search/keyboard submission, never per keystroke.
The initial scope is the current source/folder, so source root searches the whole registered root.
All file types can appear in search; images open the existing folder viewer, PDFs/videos open externally,
folders open the browser, and other types show an unsupported-open message.

Search is bounded to 256 folders, 100,000 entries examined, 500 results or approximately two minutes.
A UI deadline cancels blocking SMB work too. Stop returns the hits collected so far. Partial/limited/inaccessible
results are explicitly marked, not reported as an exhaustive search or an empty NAS. Narrow the folder/query
when a limit is reached. No whole-NAS persistent index, PDF-text indexing, regex, auto retry or background crawl.

"Saved listings only" performs no network requests. On connection/authentication failure, live search falls back
to saved listings instead of repeating the same failed login in every directory. Saved results can be stale and
missing folders cannot be searched offline. Current source/revision and P3 no-follow/VPN boundaries are retained.

Normal browsing now also shows PDF and common video extensions: mp4/m4v/mkv/webm/mov/avi/3gp/mpeg/mpg/ts.
Visible PDF/video entries load a first-page/representative-frame thumbnail, with the type icon as fallback.
Image viewing, favorites and direct-child folder covers remain unchanged. External playback codecs and format
variants depend on the selected application.

The external-app picker offers **Use this app next time**. PDF and video choices are stored separately and
survive app restarts. **NAS settings** has separate reset buttons for these two choices. Missing, disabled or
unlaunchable saved apps return to the picker. Only an exported handler for the current MIME type is eligible;
remembering an app does not broaden its per-file read grant.

## PDF/video thumbnails

Only visible browser entries request previews; search remains metadata-only. Rendering uses a seekable,
read-only NAS descriptor without saving the original PDF/video. Each render allows at most 16 MiB of cumulative
reads (including repeated ranges) and 20 seconds. One decoder runs at a time; queued requests also
expire after 20 seconds. Leaving/recycling a row cancels its work. Unsupported, corrupt, password-protected or
over-budget files keep the type icon and can still be opened externally.

The non-exported services receive only a descriptor and MIME type, never credentials or a NAS path.
PDF rendering uses an isolated UID without settings access. Video uses a separate app process because Pixel's
system media service rejects isolated UIDs; this process retains normal app permissions. The services render
PDF page one or a video sync frame near one second, at most 256 pixels per edge.
Unbinding terminates the dedicated process, including a stuck native decoder (up to three seconds to reap it).
Android 8.0's unscaled video API
is restricted to at most 1920×1080 pixels and a 4096-pixel edge; Android 8.1+ uses the scaled frame API.

Only the generated PNG is published into the existing 128 MiB thumbnail quota, using the normal revision-aware
cache key, reservation, validation and atomic publication. Decoder failures/cancellation do not publish partial
files. No new full-file cache or dependency is introduced.

## Read-only external stream

A tap performs a read-only open/close preflight, then offers the installed compatible external applications.
The selected application receives `ACTION_VIEW`, MIME type and a temporary exact-URI read grant. No write,
persistable or prefix grants, no `smb://` URL, credentials or broad FileProvider paths are given to it.
The new provider is non-exported and rejects write modes/insert/update/delete.

`StorageManager.openProxyFileDescriptor` (API 26+) supplies a seekable descriptor. PDF page access and video seeks
read requested byte ranges through `SmbNasReader.openSeekable`, using the same signed SMB2/3, strict path/ancestor
checks, no DFS/reparse following, fixed VPN DNS/socket and no guest/LAN fallback as image reads.
Sequential image reads reuse that checked open. The `NasReader` interface stays list/open only.

The app does **not** download a full PDF/video or persist a new external-file cache. Full-size videos are not
subject to the image cache's 128 MiB single-image limit. Reads are chunked at 128 KiB and fulfill short SMB reads
until the requested range or EOF; premature/zero reads fail instead of silently truncating the file.

There are at most four external descriptors and at most two concurrent network operations through the existing
network gate. Per-descriptor handler threads and request watchdogs are bounded. A 30-second callback deadline
or ten minutes without reads closes its raw socket/resources; closing the client descriptor also cleans up.
Active SMB handles can block concurrent edits/renames by other clients until released (read-share policy is unchanged).

The process-local URI table has at most 32 entries with a 30-minute opening lifetime. Existing open descriptors
are independent of token expiry. After process death, expiration, stream failure or an idle timeout, reopen the
file from Gallery NAS; automatic network reconnection or resuming a broken descriptor is not attempted.
PDF/video viewing needs a network connection. Existing image/catalog offline behavior is unchanged.
A trusted receiver can copy/export the granted file or maintain its own cache; Gallery NAS cannot limit that cache.

Official API references:
- https://developer.android.com/reference/android/os/storage/StorageManager#openProxyFileDescriptor(int,android.os.ProxyFileDescriptorCallback,android.os.Handler)
- https://developer.android.com/reference/android/os/ProxyFileDescriptorCallback
- https://developer.android.com/training/secure-file-sharing/share-file

## Cache growth audit and changes

P4 already bounds original image files to 512 MiB and thumbnails to 128 MiB, with a 128 MiB individual original
limit. Reservations and abandoned parts are counted; leased files cannot be evicted. If all remaining files are
leased or storage is low, new fetches fail rather than ignore the quota. P5's Glide NAS path disables its separate
disk cache. Folder covers reuse P4. No new persistent PDF/video file cache is introduced here.

Two metadata growth paths needed limits: successful directory snapshots/old revisions previously accumulated
without a global bound; OS-removed image files could leave stale cache-index rows.

- Catalog retention: at most 512 successful folder snapshots / 100,000 entries across sources/revisions.
  On successful publication, one Room transaction prunes oldest *other* snapshots. It never publishes a partial
  listing and never converts a failed refresh to an empty snapshot. An evicted directory must be fetched again.
- Cache index: at most 8,192 current/reserved keys. Before reserving, missing-file records are removed and oldest
  unleased records are evicted when needed. Byte quotas, active leases and pending publication rules stay intact.
- No Room schema change or GalleryDatabase migration. Existing SQLite pages can remain allocated for reuse;
  WAL, metadata, the local Gallery's independent caches and another viewer's files are outside the 640 MiB figure.
  Therefore **640 MiB is not a strict total application-storage limit**. This controls normal repeated growth of
  NAS media/cache metadata, not every byte in the app sandbox or exceptional filesystem failure.
- NAS settings/favorites/credentials are not cleared or migrated. Favorites already have a 1,000-bookmark limit.

## Machine checks and Codex device gate

Run existing unit tests once, plus `python tools/nas-next/run.py` for the shared nine-case pure JVM search/range
corpus. One additional cache-index test exercises pruning with a tiny injected limit and an active lease.
No new dependency, targetSdk, signing policy, Image Minimizer config or NAS mutation is required.
The NAS build workflow builds Debug, unsigned Release/R8 and the test APK. It does not execute device tests.

The existing custom instrumentation runner adds a local-only phase:

```powershell
adb shell am instrument -w -e probe p6-local io.github.helvetica1248.fossifygallerynas.debug.test/org.fossify.gallery.nas.smb.SmbRuntimeProbe
```

This fixture uses real Android proxy descriptors and Room, synthetic bytes, an isolated in-memory DB and an
internal Debug-only read seam. It checks forward/backward seek, short-read completion, EOF, write-open rejection,
resource release and catalog retention. It never loads stored NAS credentials or contacts the NAS.
The seam and URI are cleared afterwards. Passing this probe is not external-application or Human PASS.

The additional `-e probe p6-previews` phase checks PDF/video rendering in dedicated processes and 256-pixel bounds, invalid-PDF
fallback, cancellation during a blocked range read and stream cleanup. It uses a generated PDF and the synthetic
`app/src/androidTest/assets/nas-preview-fixture.mp4` (FFmpeg `testsrc2=size=96x64:rate=2`, two seconds, H.264
baseline/yuv420p, no audio, `+faststart`), never stored NAS credentials. JVM tests cover byte/time budgets,
separate PDF/video preferences, stale-choice cleanup and thumbnail-only cache publication/cancellation.

Codex/user must then verify on the Pixel:
1. Build Debug and test APK locally with the same existing debug key, or verify signer compatibility before install.
   Do not uninstall/clear user data to bypass a CI debug-key mismatch. Remove only the test APK after testing.
2. Run `p6-local`; launch Gallery NAS. Check favorites, covers and an ordinary image/swipe still work.
3. Search a known name from the NAS root and a child folder. Test case/Japanese, subfolders on/off, Stop,
   saved-only offline and visible partial results. No thumbnails/content should download merely for search.
4. Use a trusted installed PDF viewer: open a real multipage PDF and jump to a later page.
5. Use a trusted installed video player: play a typical MP4 (including one over 128 MiB) and seek forward/backward.
   Test other formats only when actually used. No playback capability is inferred merely from MIME matching.
6. Exercise LAN and VPN-required/Tailscale. While reading, disconnect: no Gallery crash/hang or LAN fallback.
   Restore the network and explicitly reopen. External app error presentation is app-dependent.
7. Inspect `cache/nas` before/after PDF/video playback; full media files must not accumulate there. Remember the
   external app can maintain its own storage. A synthetic small-quota probe is preferable to filling the phone.
8. Confirm only the chosen file was granted read access, no write operations reached the NAS, and no credentials
   appear in URI/logs. Do not test write permissions on real NAS files.

Report exact HEAD, unit/static/build outcomes, local-proxy probe, external app/version, play/page/seek, LAN/VPN,
cache observations, existing-feature regression and unperformed checks. Keep the PR Draft; merge, production
NAS mutations and destructive reinstall are not part of this stage.
