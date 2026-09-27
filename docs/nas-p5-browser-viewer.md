# NAS P5: read-only albums, browser, grid and viewer

Base main: `423c6de557197c165f34bd9cf1970935b28d5718`.
Branch: `codex/nas-r1-p5-browser-viewer`. Keep the PR Draft until Human Review; no merge in this phase.

## Entry and browser

Main Gallery's ordinary overflow menu has **NAS Albums**. It launches NasBrowserActivity, not a
Gallery Directory or Media item. Third-party picker menus are unchanged. Source cards read P2
SavedNasSource display names and LAN/VPN mode; unnamed sources use a generic NAS label rather than
exposing a host or username. Empty source lists offer a button to NAS settings.

One dedicated Activity owns source/root/child-folder navigation. Back moves to the parent, then
sources, then exits. The title shows the current folder. A cached P4 successful snapshot is read
first. Opening a folder with no snapshot makes one explicit request. Cached folders are not
refreshed automatically; the Refresh menu requests an update while leaving the grid visible.
Failures preserve the old listing and show fixed safe messages. Only Complete(empty) means an empty
folder; a nonempty folder containing only unsupported files has a separate no-supported-images state.

NasBrowserAdapter is click-only: no selection, long press, contextual actions, edit, share or write
operations. It displays directories and case-insensitive jpg/jpeg/png/webp/gif entries. Sorting is
screen-local: name ascending or modification time descending, with directories first. Grid columns
follow the available width; labels use two lines and ellipsis for long filenames.

Thumbnail requests start only for attached, actually visible cells. Binding/prefetch alone does not
start a request. P4 first reuses cached thumbnails; a miss may explicitly fetch the visible item.
Before opening a folder or launching the viewer, visible thumbnail requests are cancelled and joined off the main
thread (the UI coroutine suspends), preventing a duplicate original fetch while a thumbnail owns
the same cache reservation. Recycle/detach/stop cancels work and blocks stale results from reaching another item. Thumbnail
BitmapFactory decoding happens on IO while a lease is held; a fully decoded bitmap can then release
its file lease. Decode failure invalidates the thumbnail. Failed cells do not retry in a loop;
manual refresh/rebinding or reopening is the next explicit opportunity.

## Viewer

NasViewerActivity extends only the lightweight BaseViewerActivity. NasPageFragment is a dedicated
read-only page; PhotoFragment, ViewPagerActivity, MyPagerAdapter, MediaAdapter and DirectoryAdapter
are not reused or modified for NAS. No Gallery DB row or MediaStore registration is created.

Intents/fragments contain source UUID/revision, folder path, selected remote path and small sorting
flags. No cache path or full entry array crosses an Intent. The Activity resolves the current source
revision, reads P4's catalog on IO, filters supported images and resolves the selected entry exactly.
ViewPager2 displays the current and at most one adjacent page on each side. It does not fetch the
whole folder. Requests use P4's existing two-request network gate and P3's read-only/VPN transport.

Pages pass only completed, leased original files to decoders. JPEG/PNG/WebP use Glide's content
recognition (extensionless SHA-256 names work); Glide also applies JPEG EXIF orientation. Decodes are
bounded to a 2048px requested edge for ordinary fit-center viewing. GIF uses the existing
pl.droidsonroids GifDrawable directly with the leased file, so animated playback does not depend on
Glide selecting an animated drawable. The original remote name is only a GIF format hint and UI title.
The P4 cache identity is unchanged.

Glide's blocking FutureTarget decode runs on IO. Cancellation never releases a file underneath an
unfinished decode: late results are disposed after the blocking operation returns. Decoded resources
and their lease remain owned by the page while displayed. On stop/destroy, the image is detached and
animation stopped, then decoder cleanup precedes lease release on a background executor. GIF recycle
likewise precedes lease release. No Room, disk, SMB or Bitmap decode is added to the main thread.

GestureImageView provides pinch/double-tap zoom. A small gesture container keeps multi-touch from
being intercepted by the pager; horizontal paging is disabled while zoomed in and restored at fit
scale. Tap/accessible click toggles fullscreen. Toolbar contains back and filename only. There are
no delete/edit/rename/move/copy/favorite/rotate-save/resize/share/export/open-with/wallpaper actions.

Decode failure invalidates the original and presents a Retry button. A retry is explicit and uses
normal cache-first fetching, never force=true or a reconnect loop. Screen stop cancels outstanding
UI requests, and lifecycle cancellation discards late images/leases. Saved state preserves the
source/folder and selected remote item rather than serializing an image list or transfer progress.

## Offline, boundaries and accepted limitations

- Source lists and cached catalog/thumbnail/original access need no network. Cached images remain usable
  when VPN is absent. An uncached image can fail with a fixed error and Retry.
- Opening NAS Albums is explicit. App/Main Gallery startup only adds a menu; no NAS request is added.
- No prefetch of a whole folder, recursive indexing, background sync, new socket or VPN fallback.
- Gallery DB and P2 credentials are unchanged. P4's accepted force-refresh retention behavior is unchanged.
- A zoomed-in page must return to fit scale before swiping. Full-resolution deep zoom, panorama, video,
  exotic formats, exact ICC color fidelity and editing/export are outside P5.
- The 2048px display decode is a practical bound; zooming is not guaranteed to reveal full-resolution detail.
- Rotation/stop can restart a visible page request; an interrupted folder request can be retried manually.
  Full transfer-progress restoration and persistent sort preferences are not provided.
- Minor spacing, fallback translation, cache eviction/redownload and existing upstream warnings are accepted.
- Real NAS/Tailscale Human Review is not inferred from fixture tests. Release builds are unsigned.

## Automated verification

Eleven JVM focused tests cover format filtering, ordering, exact viewer selection, cached folder and
failed-refresh retention, offline cached thumbnail/original reuse, safe cache-miss failure without
retry, cancellation that releases a late lease without delivering to a destroyed view, favorite persistence/removal
and atomic save failure, and bounded cached folder cover selection without recursive traversal.

The P5 instrumentation phase uses the real Activities with a debug-only internal NasUiData fixture
injection, independent Room DB/cache and synthetic JPEG/PNG/WebP/two-frame GIF. Release ignores the
fixture field. It never reads/writes saved sources or credentials and never uses an SMB reader.
It opens Main Gallery's menu, source and child folder, checks decoded thumbnails, exercises viewer
pages with injected horizontal touch swipes, checks JPEG EXIF orientation, zoom controller state,
animated GIF, fullscreen, back navigation, and physical cache cleanup after page lease release.
Diagnostic screenshots are taken only on fixture screens; wait for transitions before visual review.

```text
adb shell am instrument -w -e probe p5-ui io.github.helvetica1248.fossifygallerynas.debug.test/org.fossify.gallery.nas.smb.SmbRuntimeProbe
```

The probe cleans its DB/cache and removes its provider override in finally. Test APK and diagnostic
cache screenshots are removed after evidence collection. Tests do not exercise production NAS traffic.

## Human Review (read-only NAS account)

Use the final Debug APK from `artifacts/nas-p5-pixel/`. Existing app settings are preserved by update install.

1. In both LAN and Tailscale mode: Main Gallery → NAS Albums → source → root → child folder; verify Back.
2. Use Refresh, name sort and modified sort. Confirm progress is visible and the old grid remains during refresh.
3. Check JPEG, PNG, WebP and GIF thumbnails. Open each format; GIF should animate.
4. View one JPEG with EXIF orientation. Confirm its direction, fit-center view, pinch/double-tap zoom,
   return to fit scale, previous/next swipe, tap fullscreen and Back. Ordinary phone/camera photos are sufficient.
5. Turn Tailscale/network off after caching sample images. Reopen the cached folder, thumbnails and original;
   those should remain visible. An uncached image should show a safe failure, not erase the folder.
6. While offline, Refresh should show a safe error and keep the previous successful listing.
7. Restore Tailscale/network and press Retry for the failed image; verify recovery without an automatic retry loop.
8. Check dark mode, one landscape view, a stop/resume and a recreation for practical usability.
9. Verify the viewer has no write/export actions. Check NAS audit records for absence of client write/create/delete/rename;
   do not test permissions by attempting a write. Human PASS/FAIL must be supplied by the user.

## Initial P5 validation record

Validated 2026-09-28 (JST).

- P1 standalone: 195/195 PASS, exactly one run.
- Gradle JVM: 225/225 PASS (P1 195 + P2 6 + P3 8 + P4 9 + P5 7); no failures/errors/skips.
- detekt: PASS. lintFossDebug: PASS, 0 unfiltered errors / 73 warnings. The increase from 53 is
  untranslated fallback strings (17), notifyDataSetChanged (1) and layout overdraw suggestions (2).
  No lint/detekt baseline or global failure threshold was changed.
- `--no-daemon clean assembleFossDebug`: PASS. Final navigation-cancellation update also built successfully.
- `--no-daemon assembleFossRelease`: PASS, including R8; revalidated once after the final navigation fix.
- Pixel 9 Pro final APK: fixture UI probe PASS for entry/source/folder/grid, JPEG EXIF/PNG/WebP/animated GIF,
  injected touch swipes, zoom state, fullscreen, back, offline reads, and lease cleanup after screen destruction.
- Grid/Viewer fixture captures reviewed. Real pinch gestures, landscape, ordinary NAS images and LAN/Tailscale
  connectivity/recovery remain in the Human Review procedure above; automated results are not Human PASS.
- Final Debug installed, fixture DB/cache and test APK removed, ordinary Gallery NAS launch succeeded.
- Debug signature / 16KB ZIP alignment and Debug/Release ZIP integrity: PASS.
- Debug SHA-256: `0984f942c2700c8391c5d650527f7386f4a8627435a2d624ba3c15e7c7abd007`.
- Unsigned Release SHA-256: `d74d70b48eda473952a98d612ea7f6bf5699df1d157b19b184713a1991430c94`.
- Local APKs, logs and fixture screenshots: `artifacts/nas-p5-pixel/` (ignored, not committed).

Self review/runtime verification fixed the GIF path by using the existing GIF decoder, separated
multi-touch interception from accessible click handling, and joined cancelled thumbnail requests
before viewer launch to avoid competing original reservations. The UI probe was corrected to wait
for Activity/pager transitions and report assertions safely rather than throwing them on Android's
main thread. No unresolved STOP condition was found. Keep this PR Draft for real-NAS Human Review.

## Favorite folders and folder covers (Human Review follow-up)

The user reported the original P5 functionality working and requested folder bookmarks and automatic
folder thumbnails. These additions remain in the same P5 PR; no merge is authorized by that feedback.

- Open a folder and use the toolbar star to add/remove it. NAS Albums lists favorites first, marked
  with a star, source display name and relative path. A tap opens that folder directly. Source roots
  can also be bookmarked. Back follows the normal parent-folder navigation.
- Bookmarks persist locally in `noBackupFilesDir/nas-settings/favorites.bin`, keyed by source UUID
  and relative path. They survive app restarts and source name/mode/credential edits, and resolve
  against the source's current revision. If the same source is repointed to a different root/share,
  bookmarks follow its new root. Removed sources are hidden; no credentials are stored here.
- Favorite updates are serialized and atomically replace the file off the main thread. They never
  modify the NAS or local Gallery DB. A save failure leaves the prior file and reports a fixed message.
- A visible folder/favorite card obtains one cached child listing (or makes one listing request if
  uncached), then chooses the first supported direct-child image by name. It retrieves only that
  image's thumbnail via P4. No recursive search or whole-folder image downloads are introduced.
- Cached folder covers work offline. Empty, unsupported-only, inaccessible or offline-uncached folders
  retain their folder icon. A failed cover does not erase the listing or trigger an automatic retry loop.
  A child folder's saved listing is updated by opening that folder and using Refresh.
- Folder/grid/viewer transitions cancel and join cover work before destination requests start. Rebinding
  invalidates old row identities so they cannot restart during navigation.

NAS delete/move is deferred: the user's condition was to implement it if simple. The current transport
exposes only reads; reliable writes require a separate design for permissions, confirmation, overwrite,
partial failure and catalog/cache invalidation. No NAS write UI or permission changes are introduced.

Additional Human Review: add a nested folder with the star, return to NAS Albums, reopen it from the
favorite, restart the app and verify persistence, then remove it. Check folder covers online and after
turning VPN off. Folders containing only other folders intentionally retain the folder icon.

Follow-up validation: Gradle **229/229 PASS** (11 P5 focused), detekt and lintFossDebug PASS
(0 unfiltered errors / 77 warnings; four new untranslated fallback warnings). Debug and unsigned
Release/R8 builds PASS. Pixel fixture PASS for favorites add/open/remove, folder covers and all
previous viewer/offline/lease checks. Fixture captures confirm dark-mode labels and aligned cards.
No real NAS writes or credential changes were performed. The added features await user confirmation.

Updated Debug SHA-256: `27e1ce9d2bec4632faf5d3810a89b26807ab75802eea1f888ba870d96bccb756`.
