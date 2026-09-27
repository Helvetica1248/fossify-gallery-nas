# NAS P2: local connection settings

P1 was squash-merged as dda51cc7b3e08f2010f9efc75abf2da45c334236, with the same tree as audited
e7c01c5e32ec54cf9ec02ac1b4cb17444b017c60. P2 adds local settings only.

## Installation identity

- Release application ID: io.github.helvetica1248.fossifygallerynas
- Debug application ID: io.github.helvetica1248.fossifygallerynas.debug
- Namespace and Kotlin packages remain org.fossify.gallery; display name is Gallery NAS.
- Provider authority already uses applicationId. Launcher aliases now use the release ID, matching
  Commons' removal of the debug suffix when changing icons. Actual activity class names remain unchanged.
- No new permissions or SMB dependency. No connection, test connection, listing, scanner, cache or viewer.

## Storage and lifecycle

Settings → NAS sources supports add, edit and delete. The editor accepts a display name, server,
single share name, relative root, username, password and VPN-required flag (unchecked means LAN).
A domain can be entered as DOMAIN\user. Username is required; an empty password is supported.
The P1 NasSource model validates server/share/root. VPN-required is represented by the existing VPN enum.

A separate versioned binary metadata file contains only the source ID, revision, display name, host,
share, root, mode and credential reference. It never touches Gallery Room databases or shared preferences.
Each edit increments revision; an old revision cannot overwrite an existing newer one.

Credentials are encrypted using AES-256-GCM, a provider-generated IV, a 128-bit authentication tag and
the credential UUID as additional authenticated data. Each new blob has its own non-exportable Android
Keystore key. Passwords are not stored in Keystore itself. JVM tests inject in-memory AES keys only.

Both metadata and encrypted blobs live under noBackupFilesDir/nas-settings. Android documents that
[getNoBackupFilesDir is always excluded from backup](https://developer.android.com/identity/data/autobackup);
there is no redundant backup XML. Existing Gallery backup behavior is unchanged.

On save, write a new credential blob first, atomically replace metadata, then destroy the old credential
key and blob. A failed metadata write deletes the unpublished new credential and retains the old source.
Delete destroys the referenced key and blob before removing metadata. An interrupted/failed delete can
therefore leave a source requiring re-entry, but never silently reuse credentials for another host.
Corrupt metadata is reported, not interpreted as an empty source list.

A missing, invalidated or corrupt credential returns unavailable. Editing prompts for re-entry;
saving creates a fresh reference/key. Other sources keep their own keys. There is no plaintext fallback,
biometric prompt, master password, cloud sync or credential export.

Credential objects redact toString. The UI reports fixed error messages without logging provider
exceptions. Password fields are masked, view-state saving and autofill are disabled, and the NAS screen
uses FLAG_SECURE. Credentials never enter Intent extras or saved-state Bundles. Keystore/disk work runs
off the main thread. Operations serialize through one process-wide store.

## Accepted limitations and validation boundary

- Unsaved dialog edits are discarded on rotation/process death. English and Japanese NAS strings are
  provided; other locales use English. No device UI/Keystore/backup-restore test has been performed.
- A process kill between metadata publication and old-key cleanup can leave an unreferenced encrypted
  blob/key in app-private no-backup storage. There is no recovery journal or orphan sweeper in P2.
  A rare storage failure after a committed edit is reported generically; reopen to see the saved revision.
- Commons 6.1.6 contains a non-Fossify-package restriction in its primary-color picker after more than
  50 app launches (confirmed by dependency bytecode inspection). This optional customization limitation
  is retained rather than forking Commons in P2; core Gallery/NAS settings do not depend on that picker.
- Android/JVM strings cannot guarantee immediate memory erasure. No plaintext credentials are persisted
  or logged; this does not claim protection from a compromised device/process.
- Release signing/R8, live SMB behavior and device-install verification are outside the P2 gate.

## Checks

Run testFossDebugUnitTest, detekt, lintFossDebug, then clean assembleFossDebug.
P2 has six focused JVM tests: encryption/redaction, corrupt/wrong-key failure, CRUD/revisions/deletion,
key-loss recovery, metadata-write rollback and corrupt-metadata preservation. P1 remains 195 JUnit cases.
Run tools/nas-core/run.py once: it explicitly compiles only P1's Android-independent directories.

Before device use, manually verify create/edit/reopen/delete, the re-entry message after key loss,
and official Gallery coexistence. These are pending Human checks, not inferred passes.

## Deferred

P3 adds actual SMB transport, connection validation and runtime VPN enforcement. Catalog/Room, scanning,
thumbnails, partial-cache publication and Gallery browsing/viewer integration remain later work.
P2 must not be merged automatically.

## Local validation (2026-09-27)

Final clean run: assembleFossDebug, testFossDebugUnitTest, detekt and lintFossDebug all passed.
JUnit: P1 195 + P2 6, no failures/errors/skips. P1 standalone: 195/195, one run.
Detekt: zero findings. Lint: zero new errors, 42 warnings (18 existing, 22 missing translations,
one SetTextI18n and one unused original launcher-name resource); baseline unchanged.
APK: version 1.13.1 (28), minSdk 26, targetSdk 36, Debug APK Signature Scheme v2 verified.
ZIP integrity, activity classes, enabled launcher alias, provider authorities and no INTERNET verified.
No APK installation or Human test was performed.
