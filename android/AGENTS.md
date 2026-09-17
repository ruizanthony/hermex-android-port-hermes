# Android fork — Hermex Pichot

## Purpose

Android-only unofficial fork based on `android-v1.2.0`. Keep upstream iOS sources intact. Deliver a separately installable APK without changing Hermes WebUI or its stored conversations.

## Ownership

Changes and releases require the fork owner's approval. Work on a dedicated branch; keep upstream release refs unchanged. No upstream PR or third-party workflow activation is implicit in a fork release.

## Local Contracts

- Android application ID is `com.uzairansar.hermex.pichot`; Kotlin namespace remains `com.uzairansar.hermex`. Keep launcher alias class names, shortcut target packages and FileProvider authorities consistent with this distinction.
- Release signing uses the existing `HERMEX_ANDROID_*` environment contract. Store the durable private keystore outside Git; never publish signing material or local server data.
- Conversation grouping is display-only. Trust confirmed compression redirects / server lineage roots, never titles or ordinary parenthood. Preserve manual forks, subagents, profile boundaries, search matches, raw cached rows and explicit historical access.
- Superseded segment filter: a Desktop row carrying a fallback title ("Desktop Session"/"Untitled"/null, Desktop source only) hides only when replacement is proven within the same listing window — a transitive titled descendant, an inactive titled parent, or a parent itself proven superseded (dead branch). Title or parenthood alone never hides; active rows (streaming/activeStreamId/pinned) always stay; local and remote search plus the compression-segments disclosure toggle bypass the filter so every segment remains reachable.
- Legacy Desktop/CLI subagents may be hidden only with a matching direct parent `delegate_task` call: exact initial user request equals a task goal, creation within five seconds of that call, original first message near creation, same normalized profile, no fork/compression marker. The generic server category `other` (and blank/null) is eligibility-neutral: it triggers the proof lookup but is never itself proof. Titles and plain parenthood are insufficient. Keep ambiguous rows visible. Proofs drive only the existing subagent list toggle; preserve raw source, capabilities and cached histories through additive Room migrations. Proof lookups are read-only, serialized and budgeted; downloaded transcripts are not retained by the classifier.
- Choose an actual streaming row while it is active; never transplant a stream identifier onto another session. Commands operate on that real representative, not on an artificial aggregate.
- Optional metadata reads use `messages=0` and `resolve_model=0`, bounded concurrency/time and conservative failure. Do not archive/delete server sessions to tidy the list.
- When list/detail omit redirects, the existing read-only lineage report may identify a handoff: ended reason `compression`, one report segment, one child within five seconds of the parent's ended boundary, and matching list parent/profile. Preserve explicit forks/subagents, competing boundary children and unknown reports. Confirmed handoffs may cross Desktop/WebUI, not messaging surfaces; never treat all children as continuations. Qualify the actual repository-to-UI flow with absent redirects, not only a fabricated complete redirect.
- Sparse compression chains use hidden nodes only as private proofs, never as new list rows. A report-confirmed direct child may supply missing legacy parentage; a missing/empty legacy profile means `default` as in the server contract, not the currently selected arbitrary profile. Explicit mismatches/forks still refuse the link. Bound each visible-list refresh and cache both positive and negative lookups; retry only while the list is visible. The tip's mutable `archived`/stream state is re-read each pass from list/detail, never from the link cache; when a proven chain's verified continuation is archived and no member is live, display-level archive hides the whole conversation from the ordinary view (archived view still reaches it) without touching raw rows.
- One user archive gesture on a collapsed conversation targets every proven chain member sequentially (stop on first error, report it visibly); the server stays the source of truth for the archive flag. The gesture applies the display projection immediately (optimistic flip): rows leave the ordinary view at once, server mutations continue in the background, and a refusal restores the exact pre-gesture rows with a visible error. A rejected mutation (concurrent action in flight) must surface a visible error, never drop silently.
- Chat transcript caching is incremental, not completion-only: the materialized transcript is persisted to Room at safe milestones (tool completion, screen refresh) and synchronously on screen exit, excluding optimistic echoes, the in-flight streaming placeholder and local-only notices. Reopening a conversation renders from the warm cache first; the network refresh stays silent and authoritative.
- Visible auto-refresh (sessions list and idle chat reconciliation) runs only while its screen is RESUMED. Its cadence backs off on server failure: 5s base doubling to a 60s cap, reset on the first success. A pass is a failure when it returns `ResultState.Error` OR `ResultState.Data(fromCache=true)` — the latter is reachable only through the network-failure fallback, so cached content on screen never means the server is healthy. Busy/mid-flight passes record nothing.
- Foreground/background conversation refresh (ChatForegroundRefreshCoordinator): an app foreground transition triggers one immediate read-only snapshot attempt; while the app is backgrounded AND the open conversation is active (streaming/activeStreamId), a bounded 15s warm loop keeps the transcript cache fresh. The active flag is bridged from the VM's own state (never the frozen UI collect) so a stream ending in background stops the loop immediately; inactive conversations never poll in background. The refresh spinner (`isRefreshingConversation`, set after the busy-check around effective fetches only) shows beside the chat title.
- The session list stays interactive from cached content: `isLoading` is reserved for the empty-list first load, and cached sessions keep rendering while the background refresh completes. Negative proof verdicts (not-a-subagent, no-continuation) are cached for five minutes, not re-probed every minute.
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
