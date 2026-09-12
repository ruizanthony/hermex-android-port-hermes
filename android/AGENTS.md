# Android fork — Hermex Pichot

## Purpose

Android-only unofficial fork based on `android-v1.2.0`. Keep upstream iOS sources intact. Deliver a separately installable APK without changing Hermes WebUI or its stored conversations.

## Ownership

Changes and releases require the fork owner's approval. Work on a dedicated branch; keep upstream release refs unchanged. No upstream PR or third-party workflow activation is implicit in a fork release.

## Local Contracts

- Android application ID is `com.uzairansar.hermex.pichot`; Kotlin namespace remains `com.uzairansar.hermex`. Keep launcher alias class names, shortcut target packages and FileProvider authorities consistent with this distinction.
- Release signing uses the existing `HERMEX_ANDROID_*` environment contract. Store the durable private keystore outside Git; never publish signing material or local server data.
- Conversation grouping is display-only. Trust confirmed compression redirects / server lineage roots, never titles or ordinary parenthood. Preserve manual forks, subagents, profile boundaries, search matches, raw cached rows and explicit historical access.
- Choose an actual streaming row while it is active; never transplant a stream identifier onto another session. Commands operate on that real representative, not on an artificial aggregate.
- Optional metadata reads use `messages=0` and `resolve_model=0`, bounded concurrency/time and conservative failure. Do not archive/delete server sessions to tidy the list.
- When list/detail omit redirects, the existing read-only lineage report may identify a handoff: ended reason `compression`, one report segment, one child within five seconds of the parent's ended boundary, and matching list parent/profile. Preserve explicit forks/subagents, competing boundary children and unknown reports. Confirmed handoffs may cross Desktop/WebUI, not messaging surfaces; never treat all children as continuations. Qualify the actual repository-to-UI flow with absent redirects, not only a fabricated complete redirect.
- Sparse compression chains use hidden nodes only as private proofs, never as new list rows. A report-confirmed direct child may supply missing legacy parentage; a missing/empty legacy profile means `default` as in the server contract, not the currently selected arbitrary profile. Explicit mismatches/forks still refuse the link. Bound each visible-list refresh and cache both positive and negative lookups; retry only while the list is visible.
- Idle chat reconciliation runs only in the resumed chat lifecycle, is serialized, and must not reset drafts, optimistic sends or an in-flight SSE. Rejoin newly announced streams and retain a `compressed.new_session_id` through incomplete stream termination. Continuation draft copies preserve both source and target and do not transfer queued auto-sends.
- Technical-message filtering is a reversible display projection driven by `display_kind`/`_source` metadata retained through cache serialization. Keep human requests, quoted markers and ambiguous unfinished turns visible. Never delete the raw transcript.
- Cache schema changes are additive Room migrations. Preserve existing rows and include the generated schema.

## Work Guidance

Build from `android/` with the checked-in Gradle wrapper, JDK 17 and Android SDK 36. No new application dependency is needed for compression grouping. Fixtures and public screenshots must contain only generic data.

## Verification

- `./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease`
- Build the instrumented test APK with `:app:assembleDebugAndroidTest`, then run `ChatRefreshInstrumentedTest`, `SessionCompressionInstrumentedTest` and `HermexDatabaseMigrationInstrumentedTest` on an Android emulator.
- Verify APK identity/signature, installation, launcher, grouped UI, live-row navigation, historical disclosure and cache persistence. Verify the published APK by downloading it again and comparing SHA-256.
- Emulator proof is not installation proof on the user's phone. Rollback is opening the unchanged original app.

## Child DOX Index

No child instruction files.
