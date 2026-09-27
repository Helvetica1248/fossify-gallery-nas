# NAS P3: read-only SMB transport

Base: `92d93228ab4033b4bf15cbc622fb36c55d749844` (P2, PR #2).
P3 adds explicit connection testing and a read-only transport. It does not add browsing,
scanning, thumbnails, caching, a catalog, a viewer, background jobs, or startup connections.

## Transport and boundaries

SMBJ 0.15.0 uses only SMB 2.0.2/2.1/3.0/3.0.2/3.1.1, with signing enabled and required,
SMB1 negotiation disabled, DFS disabled and an NTLM authenticator. Guest/anonymous sessions
are rejected. There is no retry with relaxed security. Message encryption is not required;
no encryption setting is exposed. INTERNET and ACCESS_NETWORK_STATE are added; targetSdk stays 36.
Android 17 targetSdk 37 ACCESS_LOCAL_NETWORK migration is deferred to a separate SDK migration.

`NasReader` is unchanged. The adapter exposes only list/open. SMB opens use GENERIC_READ,
FILE_OPEN and FILE_OPEN_REPARSE_POINT, never create/overwrite/delete access. Share paths are
built through `source.pathWithinShare()` and `NasRelativePath`. A local-only path resolver
replaces SMBJ's automatic symlink resolver. Ancestor directories are opened and checked for
reparse attributes, and retained without write/delete sharing until the request ends.
Reparse entries in listings are skipped; DFS shares and referrals fail safely.

The standard SMBJ directory iterator can treat repeated pages as EOF. P3 instead sends
QUERY_DIRECTORY through SMBJ's public session API and uses its decoder, accepting only
normal terminal status (NO_MORE_FILES, or NO_SUCH_FILE on the first wildcard query).
Repeated/empty success pages, duplicate names, malformed data and request failures cannot
publish a Complete snapshot. Listing is one level only, bounded to 100,000 names.

Read handles own the request and close all its resources. The stream calls SMBJ File.read,
which checks response status before EOF; it does not use the convenience InputStream that
can interpret a zero-data failure as EOF. Size and modification time are checked at open,
and premature EOF/changed length causes IOException, never successful partial publication.
No cache publication occurs in P3.

## Routing, timeout and cancellation

LAN uses ordinary Android routing. VPN sources select a TRANSPORT_VPN Network and use both
its getAllByName and its socketFactory. SMBJ receives a numeric address to prevent a second
DNS lookup on the default network. Each request has a dedicated client, session and socket
factory; a failed VPN-bound operation never retries over LAN or another address.
VPN presence does not identify Tailscale or validate its routes; Human Review must verify them.

DNS wait, TCP connect, stalled socket read and SMB read/write/transact timeouts are 15 seconds.
Only the first resolved address is attempted. Native DNS may outlive its cancelled future;
the caller wait and the DNS executor (two daemon threads, two queued jobs) are bounded.
A hung resolver cannot proceed to authentication after cancellation.

The context is registered with NasCancellation before DNS/connect/authentication. It closes
the raw socket first and then attempts every remaining resource close independently, exactly
once. Resources acquired after cancellation are immediately closed. Cancel and screen stop
schedule cancellation off the UI thread. There are no shared SMB sessions or periodic retries.
The explicit connection test passes through NasRetryGate; APP_START is rejected. No network
change observer is installed in this phase; reconnection is manual.

## Credentials and UI

The test reads the selected P2 vault entry only after network policy passes. DOMAIN\user
splits at the first backslash; empty passwords remain valid. No guest fallback is attempted.
SMBJ logging is not enabled and no SLF4J backend is added. Exceptions are reduced to fixed
failure categories without showing/logging host, path, credentials or transport messages.

Settings > NAS sources has a Test connection button for each saved source. Save only saves.
Test performs policy, vault load, connect, authentication, share/root listing and full cleanup.
A cancel button and screen-stop cancellation are provided. Success means root listing completed;
it does not mean browsing or image viewing is implemented.

## Validation and Human Review

P1 standalone is run once. Eight focused JVM tests cover credentials/open flags, strict list
completion, failure categories, actual socket cancellation/cleanup, selected VPN factory/no
fallback, missing VPN, startup gate, short reads and reparse/traversal entries.
A small androidTest Instrumentation (`SmbRuntimeProbe`) checks SMBJ cryptography, Android network
selection, and cancellation of a stalled SMB negotiation against an on-device loopback socket.
It never reads saved sources/credentials and never accesses a production NAS.

Device probe command after installing Debug and androidTest APKs:

```text
adb shell am instrument -w io.github.helvetica1248.fossifygallerynas.debug.test/org.fossify.gallery.nas.smb.SmbRuntimeProbe
```

Actual NAS authentication, listing, VPN binding and VPN loss require the following Human checks.
Use a dedicated NAS account whose ACL allows reading only. Never test write restrictions by
creating, deleting or renaming a NAS file; verify client APIs and NAS ACLs instead.

1. Create a LAN source and test with correct credentials; expect success.
2. Test a wrong password; expect authentication failure. Restore the correct password.
3. Test an incorrect share/root; expect a fixed safe failure. Restore the correct values.
4. Create a VPN-required source. Tailscale OFF: expect VPN required.
5. Tailscale ON: expect success. Turn it OFF while testing: regain control in roughly 15–20 seconds.
6. Turn it ON again and retry manually; expect success.
7. Check NAS audit records for absence of create/delete/rename and app logs for absence of credentials.
8. Keep the PR Draft until these checks have been reviewed. No photo viewer check is needed in P3.

## Accepted limitations

- Real NAS and Tailscale outcomes are not inferred from automated tests.
- One address per request; no automatic address or network fallback. Retry manually.
- Native DNS cancellation is best effort, with bounded threads and caller timeout.
- Strict listing rejects servers that repeat pages instead of returning a terminal status.
- Concurrent write/delete sharing is denied during reads, so a busy file may report access denied.
- English/Japanese UI only; other languages fall back to English. Existing lint/upstream warnings remain.
- Release signing is not configured; R8 is validated with an unsigned release APK.
  GSS/Kerberos and unused MBassador EL APIs are absent on Android; narrowly scoped R8 rules
  cover those optional references and preserve annotation-discovered SMBJ event handlers.
- P2 limitations (unsaved editor state, rare orphan encrypted blobs, Commons color-picker restriction) remain.

References: [SMBJ 0.15.0 sources](https://repo.maven.apache.org/maven2/com/hierynomus/smbj/0.15.0/smbj-0.15.0-sources.jar),
[Android Network](https://developer.android.com/reference/android/net/Network).

## Local validation (2026-09-27)

- P1 standalone: 195/195 PASS, one run.
- Gradle: 209/209 PASS (P1 195 + P2 6 + P3 8), zero failures/errors/skips.
- detekt: PASS. lintFossDebug: PASS, 51 warnings; baseline and failure thresholds unchanged.
- clean assembleFossDebug: PASS. Final incremental Debug and unsigned Release/R8: PASS.
- Pixel 9 Pro: Debug update installed; local SMBJ crypto/network/negotiation cancellation probe PASS.
  Stalled negotiation was aborted within the probe's three-second bound. The probe APK was removed.
- Settings UI: saved source and explicit Test connection button visible; no real NAS test was invoked.
- APK: fork ID, launcher alias/provider authorities and targetSdk 36 preserved. Only INTERNET and
  ACCESS_NETWORK_STATE added; no ACCESS_LOCAL_NETWORK. ZIP integrity, v2 signature and 16 KB zipalign PASS.
- Debug SHA-256: d82c47385dd7445d178e9e86a9a95b92e41120ebd1249080a7bf532098a45efe
- Unsigned Release SHA-256: 1da229218803292ea4b67d83f657c55f7a471a1588ba796ab50b071331f259a8
- Review APKs and local evidence: artifacts/nas-p3-pixel/ (locally ignored, not committed).

Self review found and fixed a Kotlin receiver-shadowing bug in the socket destination port;
the real socket regression test and Pixel probe now cover that path. R8 initially failed on
optional JVM-only classes; the narrowly scoped rules above resolve it without changing SMB
security settings or downgrading SMBJ. Real NAS/Tailscale Human Review is still pending.
