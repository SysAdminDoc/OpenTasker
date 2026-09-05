# OpenTasker

[![Version](https://img.shields.io/badge/version-0.2.93-blue.svg)](https://github.com/SysAdminDoc/OpenTasker/releases)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-brightgreen.svg)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-7f52ff.svg)](https://kotlinlang.org)
[![Obtainium](https://img.shields.io/badge/Obtainium-add%20app-1c1c1c.svg)](https://apps.obtainium.imranr.dev/redirect?r=obtainium://app/%7B%22id%22%3A%22com.opentasker.app%22%2C%22url%22%3A%22https%3A%2F%2Fgithub.com%2FSysAdminDoc%2FOpenTasker%22%2C%22author%22%3A%22SysAdminDoc%22%2C%22name%22%3A%22OpenTasker%22%7D)

**OpenTasker** is a fully open-source, on-device, FOSS alternative to [Tasker](https://tasker.joaoapps.com/) for Android.

---

## Screenshots

| Profiles | Tasks | Run Log |
|---|---|---|
| ![Profiles](fastlane/metadata/android/en-US/images/phoneScreenshots/01-profiles.png) | ![Tasks](fastlane/metadata/android/en-US/images/phoneScreenshots/02-tasks.png) | ![Run Log](fastlane/metadata/android/en-US/images/phoneScreenshots/03-run-log.png) |

| Flow | Setup | Settings |
|---|---|---|
| ![Flow](fastlane/metadata/android/en-US/images/phoneScreenshots/04-flow.png) | ![Setup](fastlane/metadata/android/en-US/images/phoneScreenshots/05-setup.png) | ![Settings](fastlane/metadata/android/en-US/images/phoneScreenshots/06-settings.png) |

## Features

### Automation engine

- **Encrypted automation database**. SQLCipher encrypts the complete Room file at rest with a random key wrapped by Android Keystore; existing plaintext databases migrate once before Room opens, and wrong-key opens fail closed

- **Profiles, contexts, tasks, actions**. a complete Room-backed automation pipeline with a Compose UI
- **Companion presence triggers**. user-confirmed CompanionDeviceManager associations emit low-power present/absent events without a scanning loop, with setup-time revocation
- **7 context families**. Application, Time, Day, Location, State, Event, and Plugin (Locale/Tasker condition)
- **78 built-in actions** plus engine-handled flow control (`task.run`, `if`/`else`/`end if`, `for each`/`end for`, `try`/`catch`/`end try`, `stop`)
- **USB device contexts**. attach/detach event pulses expose bounded device, vendor, product, and class fields for local input-device automations
- **Template expressions**. bounded `{{ ... }}` expansion with scoped variables, arrays, JSON paths, string/math/date functions, traces, and strict regex policy
- **Side-effect-free preflight reviews**. preview a task or profile with synthetic event variables, expanded inputs, branch decisions, setup gaps, intended effects, and explicit blockers before any action runs
- **Trigger simulation**. from a profile editor or Context Inspector, pin family-specific synthetic events and see each predicate, context expression, cooldown, and admission result without writing a production run-log row or running the task
- **Automation lint**. profile saves, imports, the flow graph, and Context Inspector surface missing reversals, repeated state triggers, conflicting writers, and inter-profile loops with concrete fixes
- **Profile lifecycle policy**. assign deterministic priority, symmetric activation grace, and never/date/once lifetimes; the Inspector explains expiry and priority suppression, while bundles and Tasker mappings preserve safe defaults
- **Profile execution admission**. optionally bound active and burst starts per profile, choose replayable logged holds or silent skips on overflow, and inspect rejection counts and circuit-breaker state in Diagnostics
- **Failure recovery**. route unhandled failures to an optional per-profile or global fallback task; the fallback receives bounded structured error variables and cannot recurse into another fallback
- **First-class secret variables**. AES-256-GCM Android Keystore storage, deliberate reveal/re-entry UX, and provenance-based redaction for derived action arguments, logs, traces, and failures
- **One redaction boundary for stored arguments**. credential-bearing action arguments (HTTP authorization/headers/query/body, request payloads, script stdin, SMS text) are masked wherever they are displayed, including the task list, flow graph, and previews, so they cannot leak through a screenshot or accessibility semantics; unregistered actions and unknown keys fail closed
- **Coherent execution controls**. per-profile single/restart/queued/parallel re-trigger behavior, followed by a global per-task abort-new/abort-existing/run-both/wait collision policy across profile, manual, nested, widget, notification, and external runs
- **Action-level flow controls**. atomic action reordering plus optional conditions and continue-after-failure behavior, with those semantics preserved through storage and bundle round trips
- **Durable edit history**. task, profile, and scene cards expose five-step undo/redo; variable and project deletion offer snackbar Undo, including readable Keystore-backed secrets after a project restore
- **Profile groups**. organize profiles into named groups with filter chips
- **Nested context logic**. author backward-compatible ALL/ANY/NOT groups over profile contexts; the Inspector explains the evaluated tree
- **Local projects**. scope tasks, profiles, scenes, and variables behind a shared project boundary with explicit reassignment on deletion

### Triggers (contexts)

- **Offline bundle import**. paste exported JSON or decoded QR text into the existing disabled-by-default review flow with bounded input validation
- **MacroDroid migration**. Import full `.mdr` backups or single `.macro` shares into disabled profiles, with bounded decoding and an exact mapped, unsupported, and lossy review

- Time/day schedules with presets, aliases, and ranges
- Device state (battery, charging, headphones, screen, media playback, airplane, power save, Wi-Fi SSID, orientation, proximity, physical activity, speed, roaming, tethering, and phone-call state); sensor/GPS/phone callbacks are demand-gated per profile
- App foreground detection via UsageStats, with optional exact/glob Activity component matching and explicit unavailable-component reporting
- Wi-Fi and data/internet connectivity via NetworkCallback
- Notification listener with package/title/body filters
- NFC tag scans with normalized ID matching and a one-time NDEF write helper
- Calendar windows with redacted event metadata
- Sunrise/sunset filters with coordinate, offset, and window support
- Shake, Bluetooth connect/disconnect and Android 16 bond-loss/encryption security events, Android 16 Advanced Protection transitions, sanitized SMS/MMS receipt on standard/F-Droid builds, package install/remove/replace
- Bluetooth all-devices-disconnected transition with an editor preset and multi-device tracking
- Android 15+ screen-recording visibility trigger. `event=screen_recording` reacts to whether this app is visible in a recording without capturing screen contents
- SMS/MMS-received trigger. `event=sms_received` exposes sanitized sender/body metadata on standard/F-Droid builds; Android 17 may delay standard OTP SMS delivery for up to three hours outside exempt apps
- Received-broadcast trigger. `event=broadcast` fires when another app or `adb shell am broadcast` sends an intent action you name. Only the actions your enabled profiles declare are ever registered, at most 16 of them, and the receiver re-checks the action on delivery. Extras arrive as bounded strings: text, numbers and booleans only, capped at 16 keys and 512 characters each, with anything oversized or Parcelable dropped and flagged in `broadcast_extras_lossy` rather than truncated. Tasks get `broadcast_action`, `broadcast_sender`, `broadcast_extra_count` and one `broadcast_extra_*` per surviving extra. A word on the sender: Android names it only when the sending app opts in to sharing its identity, which most apps and adb do not, so `broadcast_sender` is usually empty and a sender filter refuses what it cannot identify
- Quick Settings tile tap, home-screen widget/shortcut, boot
- Authenticated `event=push` bridge for a de-googled UnifiedPush distributor; delivery IDs are deduplicated, payloads are bounded, and message content is redacted before matching/logging
- Received Share (`ACTION_SEND`/`SEND_MULTIPLE`) trigger for bounded text, URLs, single files, and multiple files, with MIME/text/URI filters and `share_*` task variables
- FOSS platform location/geofence. GPS/network fixes, balanced provider cadence, radius/accuracy/dwell evaluation, persisted dwell state, and API 36 background delivery evidence
- Locale/Tasker condition plugins. polled as first-class context predicates with last-known-state caching
- Home Assistant bridge proof of concept. bounded outbound JSON webhooks with HTTPS-by-default policy, redacted webhook secrets, and transient retry/backoff

### Actions (78 registered + 10 engine-handled)

| Category | Count | Examples |
|----------|------:|---------|
| Settings | 19 | Wi-Fi, Wi-Fi scan, Bluetooth, brightness, volume, airplane, mobile data, always-on display, screen timeout, write setting, DND, Zen rule set/clear, ringer mode, torch, tile state, temporary state, keyboard info, keyboard picker |
| App | 10 | launch intent, launch app, publish shortcut, kill, archive, unarchive, go home, open URL, SMS, screenshot |
| File | 5 | read, write, append, delete, list |
| Network | 8 | HTTP Request, Home Assistant webhook, MQTT publish, legacy GET/POST aliases, ping, download, Wake-on-LAN |
| Media | 6 | play, stop, pause, next, previous, mute |
| System | 7 | vibrate, clipboard set, reboot, lock, screen off, wake, log |
| Notification | 4 | notify/toast, progress, cancel, TTS speak |
| Variable | 13 | set variable, clipboard get, contacts lookup, read data (JSON/CSV/XML/HTML), date-time (format/parse/add), text (match/replace/split/join/substring) |
| Flow | 1+10 | wait; engine: task.run, if/else/end if, for each/end for, try/catch/end try, stop |
| Plugin | 2 | Locale setting dispatch, Locale condition query |
| Script | 1 | SHA-256-pinned Termux `RUN_COMMAND` with bounded result capture |
| Import | 2 | unsupported Tasker and MacroDroid action placeholders |

Every action carries an explicit capability contract; an action with no reviewed contract resolves to unsupported rather than defaulting to available. Privileged actions (airplane, mobile data, always-on display, screenshot, reboot, screen off, kill app) are gated to fail honestly. Always-on display reads the setting back after writing it, so a build that accepts the write and ignores it reports a failure rather than a success. Set brightness and set screen timeout require the **Modify system settings** special access granted from Setup, and Wake-on-LAN requires local network access on Android 17+. Lock device needs the **Lock screen admin**, also turned on from Setup: Android only lets an app lock the screen through a device admin, and OpenTasker's asks for `force-lock` and nothing else, so it cannot wipe the device, set a password policy, or read anything. Turning it on means Android will not let you uninstall OpenTasker until you turn it back off, which the same Setup row does. No root, Shizuku, or accessibility service is involved, so it keeps working under Android 17 Advanced Protection. SMS is available in standard/F-Droid builds; Play builds omit SMS/phone-state permissions.

New automations use **HTTP Request** for GET, HEAD, POST, PUT, PATCH, DELETE, and OPTIONS. It accepts structured query/header lines, inline or OpenTasker-file request bodies, per-stage timeouts, status/header/body variables, and atomic file output. Redirects default off and can be enabled only for the same origin; TLS verification cannot be disabled, cleartext remains private-LAN-only, and response/request sizes are bounded. Stored `http.get` and `http.post` actions continue to execute through compatibility aliases. Put credentials in Keystore-backed secret variables and reference them from Authorization or header fields so traces remain redacted.

The **Intent Dispatch** action supports bounded activity, explicit broadcast, and explicit service delivery. It accepts allowlisted URI/MIME data, six activity/URI flags, capped string/int/bool extras, and optional ordered-broadcast result-code capture. Every configured data URI must carry an explicit `grant_read_uri` or `grant_write_uri` flag; external activity actions with arbitrary actions require a chosen component; broadcasts and services always require one. `file://` URIs, parcelable/serialization-style extras, unknown flags, ambiguous targets, and non-exported external components fail closed.

The **MQTT Publish** action uses a small in-app MQTT 3.1.1 QoS 0/1 client over platform sockets and TLS, so the F-Droid build adds no MQTT dependency. TLS is enabled by default, verifies the broker hostname, and sends SNI; cleartext is restricted to private/local hosts and the Android 17 local-network grant. Payloads are capped at 64 KB, QoS 2 and wildcard publish topics are rejected, and username/password fields are redacted.

The **Clipboard** actions read and write text without an extra permission, cap transfers at 64 KiB, and mark clipboard-derived values sensitive. **Contact lookup** supports bounded name/phone/email matching into sensitive variables. Android 17+ defaults to a field-scoped system picker with a timeout and no broad address-book grant; explicit `READ_CONTACTS` permission mode remains available for unattended runs through Setup.

**Quick Settings tiles** provide four app-owned slots. Long-press a tile to bind a task and configure its label, subtitle, icon, and state; `tile.set` can update a configured slot at runtime, and tile-triggered task runs use the same foreground execution and run-log identity as other external entry points.

The **Temporary State** action applies a bounded reversible setting (brightness, volume, ringer mode, or DND) and restores the captured prior value through a unique persisted WorkManager job. Reusing the same revert key replaces the earlier timer, and pending work remains inspectable through WorkManager after process death.

The **Keyboard** actions report the current/enabled IMEs into bounded variables. `ime.set` validates the requested component or package and opens Android's picker; normal applications cannot silently select another keyboard, so it fails with an explicit user-selection message rather than pretending the switch happened.

The **push trigger** uses the official distributor-neutral UnifiedPush connector. Setup can discover a distributor through `unifiedpush://link`, register/unregister the default instance, and retain the latest endpoint for inspection or copying. The connector chooses the SDK 34+ shared-identity registration path and the immutable `PendingIntent` fallback on older Android versions, decrypts RFC 8291 bytes messages, and acknowledges deliveries. OpenTasker consumes the standard ntfy JSON fields (`id`, `topic`, `title`, and `message`) as `event=push`; payloads are bounded, duplicate topic/event-ID deliveries are suppressed for 30 seconds, and message content is redacted before event matching and logs. `VAPID_REQUIRED`, network, action-required, and internal failures remain visible in Setup. The legacy token-authenticated `com.opentasker.action.PUSH_EVENT` broadcast remains available for existing adapters.

The **Received Share trigger** registers OpenTasker in Android's Sharesheet for text, URLs, MIME-typed content, and one or more file/content URIs. It sanitizes and bounds every value before matching, rejects arbitrary Parcelable or oversized extras, checks content-URI readability using the temporary grant without reading file contents, and shows an in-app error when access is missing. Matching share tasks receive `share_text`, `share_uri`, `share_uris`, `share_mime`, `share_count`, and `share_multiple` as run-scoped variables.

Variable names follow Tasker's scope rule: an all-lowercase name is local to the current task, while any name containing an uppercase letter is global and durable. `var.persist` promotes an all-lowercase target to a global name, and the Variable vault applies the same normalization. Concurrent runs merge changes to different globals; if two stale snapshots change the same global, the first committed value is kept and the later conflict is recorded in the run log.

### Reliability and observability

- OEM battery-killer detection with per-vendor remediation (Samsung, Xiaomi, OnePlus, Oppo, Realme, Vivo, Huawei, etc.)
- Alarm-backed time/day reevaluation through Doze, with a persisted engine heartbeat and periodic WorkManager watchdog that re-arms dropped ticks and foreground-service timeout recovery
- Setup checklist covering notifications, exact alarms, battery optimization, usage access, overlays, location, physical activity, Bluetooth, SMS, phone state, DND, modify system settings, Shizuku, and Termux
- Optional Android 16+ promoted ongoing notifications for active tasks, with standard foreground-notification fallback when promotion is unavailable or denied
- Context inspector with live source health, latest values, per-profile match explanations, and
  Loading/Ready/Stale/Error observation status with age-aware reporting
- Keyset-paged run logs with SQL-backed task/status/date/search filters, complete expandable action traces, redacted JSON/CSV export, per-step diagnostics and variable writes, reviewed retention reductions, held admission rows with safe manual replay, and user-pinned history
- Live view of in-flight automations. task, origin, current step, and elapsed time. with per-run cancellation that unwinds nested sub-tasks and records a terminal `Cancelled` outcome
- In-app diagnostics for service/foreground-type/standby/exact-alarm/matcher/watchdog health, execution admission limits and circuit trips, a bounded process log, and captured crash previews; shared reports include the same evidence with credential redaction
- Scheduling diagnostics include API 36 pending-job reason history, API 37 aggregate pending durations, expanded WorkManager stop reasons, and plain-language standby delivery consequences with honest unsupported states
- Crash log capture and local diagnostic export

### Interoperability

- **Locale/Tasker plugin host**. setting dispatch, condition queries, configuration parsing, request-query events, bundle validation, and last-known-state fallback
- **Locale/Tasker condition plugin target**. third-party hosts can configure OpenTasker profile-active, context-satisfied, and non-secret variable-comparison conditions; the exported ordered query receiver returns satisfied, unsatisfied, or unknown with bounded, typed bundles
- **Locale/Tasker condition context**. condition plugins as first-class profile predicates polled every 30 seconds
- **External automation target**. signature-scoped intents to run tasks, toggle profiles, query status, and pass variables. Task runs are asynchronous (protocol v2): the receiver validates and enqueues, then returns an execution ID that callers poll with `QUERY_EXECUTION`, because a broadcast cannot stay open for a task that may wait minutes. Callers must send `PROTOCOL_VERSION=2`; see [docs/EXTERNAL_INTENTS.md](docs/EXTERNAL_INTENTS.md)
- **Home Assistant / ntfy interoperability**. the Home Assistant webhook action accepts the Companion `message`/`data` envelope, including documented `command_*` values such as `command_broadcast_intent`. The push bridge accepts ntfy's `id`, `topic`, `title`, `time`, `tags`, `priority`, and other bounded metadata names; an ntfy `broadcast` action can target `com.opentasker.action.PUSH_EVENT` directly with the per-install token and needs no relay app. Message bodies and remote URLs stay out of event metadata and logs.
- **OpenTasker JSON bundles**. schema-versioned export/import with deterministic legacy migrations, project membership preservation, computed action-power manifests, data-to-external-chain warnings, explicit keep/rename/replace review for variable-name conflicts, disabled-by-default installation, explicit first-enable acknowledgement, and secret values omitted by design
- **Tasker XML import/export**. preview with migration/capability warnings, deterministic mapped/unsupported/lossy reporting, and safe batches for notification, variable, media, settings, and flow actions
- **MacroDroid JSON import**. full `.mdr` backups and single `.macro` shares use the same review gate, with safe trigger/action conversions, explicit placeholders, and disabled-by-default profiles
- **Profile sharing**. offline share manifests with editable local preview, screenshot attachments, safety findings, import-plan review, and GitHub Discussions submission text; unverified shares stay blocked until the existing import review passes

Untrusted imports are preflighted before object/DOM allocation. OpenTasker and MacroDroid JSON are capped at 16 Mi characters, 250,000 lexical tokens, and depth 64; Tasker XML is capped at 4 Mi characters, 100,000 nodes, and depth 64. Every format shares decoded limits of 5,000 top-level entities, 20,000 actions, 10,000 contexts, 10,000 scene elements, and 8 MiB of aggregate UTF-8 string data. A named budget violation aborts before the Room transaction.

### UI and theming

- True-black workspace surfaces now use a compact, information-first layout across Profiles, Tasks, Variables, Flow, Scenes, Inspector, Setup, Run Log, Diagnostics, and Settings
- Five primary destinations stay visible in the bottom navigation, while advanced workspaces remain one tap away in More
- Project scope, readiness, search, and primary creation actions have a consistent hierarchy on every main page
- AMOLED-first Catppuccin Mocha (dark) and Latte (light) palettes, high contrast mode
- Refined mobile shell with clearer primary navigation, bottom-bar contrast, and edge-to-edge system bar theming
- Accessible Setup theme selector with explicit selected state plus denser, confidence-building backup controls
- Compact-safe profile, task, and run-log cards with horizontally safe status chips and filtered empty states
- Variable vault, Flow, Scenes, and Inspector surfaces with summary metrics, clear status language, and polished empty states
- Shared installed-app picker across application, notification, action, and Locale plugin editors, with label/package search, app icons, validated manual entry, and a latest-observed Inspector shortcut
- Metadata-driven action forms with stable-value selectors, bounded number validation, task/app/file pickers, typed output variable chips for safe step chaining, localized parameter summaries, and forward-compatible preservation of unfamiliar imported arguments
- Guided profile templates with variable slots and safety notes
- Scene element editor with drag-to-move, resize handles, multi-select, alignment guides, scaled canvas previews, overlay launch, accessible image metadata, validated sliders, and tap/long-press task bindings
- Flow graphs with zoom/pan canvas previews, edge routing, branch/subflow markers, node deep links, and picker-backed add commands
- Profile and task search bars
- One global search across profiles, tasks, actions, variables, and scenes, including named references, with live results and deep links into the matching editor or library surface
- Saveable editor/dialog state across rotation and resize
- Adaptive navigation rail at medium/expanded widths keeps every destination discoverable while compact windows retain the bottom navigation layout

### Distribution

- F-Droid readiness profile with dependency-policy and metadata verification
- Non-F-Droid builds can opt into a daily HTTPS-only GitHub release check from Setup. It sends no identifying data, stores only a newer-release link, and never downloads or installs updates; F-Droid builds omit this check because F-Droid supplies their updates.
- Play distribution profile with SMS/phone-state manifest policy gate
- Local release verification scripts for F-Droid metadata, readiness, and APK payload comparison
- Environment-driven release signing kept outside the repository; the F-Droid distribution builds unsigned because F-Droid signs what it builds
- SQLite database backup/restore with WAL-safe validation and atomic staged restore, reviewed before staging (source, schema version, compatibility, entity counts) and cancellable afterwards; encrypted `.otbackup` v2 exports use bounded-memory, independently authenticated 64 KiB frames while legacy v1 files remain restorable. Secret rows stay ciphertext and the device-bound Keystore key is never copied, so a restore on another device requires secret re-entry
- APK payload comparison harness for reproducibility checks
- SQLCipher native libraries are included in both standard and F-Droid source builds; the release gate audits their 16 KB page alignment and keeps the dependency checksum-pinned

### Power-user backends

- Shizuku manager/service/permission status, a persisted default-on kill switch, and a fail-closed command allowlist; six elevated actions run through a separately bound AIDL user service and fail closed when Shizuku is absent, revoked, or stopped
- Termux 0.109+ `RUN_COMMAND` integration with a user-managed SHA-256 allowlist, pre-run hash verification, timeouts, and bounded output variables

To run a Termux script, place it below `~/.termux/tasker/`, enable Termux's external-app access, and grant OpenTasker `RUN_COMMAND` permission from Setup. Add the script path and the expected 64-character SHA-256 under **Approved Termux scripts**; OpenTasker performs a hash preflight and rechecks inside the fixed execution wrapper before the script can run. A capture prefix such as `%script` writes bounded `%script_stdout`, `%script_stderr`, `%script_exit_code`, and original-length variables; captured content is never written to the run log.

---

## Architecture

```
AutomationService (foreground)
  ↓
ProfileMatcher (monitors context streams)
  ↓
ContextSources (app, time, state, event, location, plugin)
  ↓
TaskRunner (executes action list with flow control)
  ↓
ActionRegistry (built-ins + capability gates + Locale plugin dispatch)
  ↓
Room DB (persistent storage + StateFlow live queries)
```

No Hilt. manual dependency wiring via `OpenTaskerApp_NoHilt`. MVVM with Compose, Room, coroutines, DataStore, and WorkManager.

---

## Install

There is no F-Droid or IzzyOnDroid listing yet, so releases come from this repository.

**With [Obtainium](https://github.com/ImranR98/Obtainium)** (recommended, it tracks new releases for you): tap the Obtainium badge above on the device, or add `https://github.com/SysAdminDoc/OpenTasker` as a GitHub source. Default settings are correct, nothing needs configuring.

**By hand:** download the APK attached to the [latest release](https://github.com/SysAdminDoc/OpenTasker/releases/latest) and install it. From the next release on, that file is named `OpenTasker-v<version>.apk`; `v0.2.87` and earlier predate the naming gate, and `v0.2.87` ships its APK as `app-release.apk`.

**Upgrading from v0.2.92 or earlier?** v0.2.93 is signed with a new key, so Android refuses to install it over an older copy. The install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE` until the old one is removed. Uninstalling also erases the automation database, so export a backup before you do anything else. Open Setup, export a copy of the database or an encrypted `.otbackup` snapshot to a folder you control, then uninstall the old build, install v0.2.93, and restore. A backup that only lives in app storage goes away with the app, so make sure the copy you keep is the exported one. Secret variable values stay bound to the device that created them and have to be re-entered after a restore. The previous key was published in this repository, which is why it was retired instead of reused.

Releases tagged `v0.1.0`, `v0.3.0`, `v0.4.1` and `v0.4.2` are dead. All four carry versionCode 1 and were signed with a key that no longer exists, so they cannot be upgraded in place. Ignore them, and if you have one installed, uninstall before installing a current build. The live line is `v0.2.x`, and its newest tag is the one to take.

---

## Build & Run

```bash
git clone https://github.com/SysAdminDoc/OpenTasker
cd OpenTasker
./gradlew :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`compileDebugAndroidTestKotlin` is in that line on purpose. The instrumented tests only run when a
device is attached, so without a compile step the everyday build will happily go green while the
instrumented sources no longer build against the code you just changed.

The external-decoder fuzzer is opt-in and is not part of the release or local quality gates. Run
`./gradlew :app:fuzzExternalDecoders -PfuzzSeconds=30` for a bounded coverage-guided pass, or
`./gradlew :app:fuzzExternalDecoderRegression` to replay the checked-in regression corpus.

Compose screenshot references are generated and validated headlessly. Run
`./gradlew :app:updateDebugScreenshotTest` after an intentional UI change to update the checked-in
references, then run `./gradlew :app:validateDebugScreenshotTest` to compare every theme, font-scale,
and RTL case.

Release builds require `OPEN_TASKER_RELEASE_KEYSTORE`,
`OPEN_TASKER_RELEASE_KEYSTORE_PASSWORD`, `OPEN_TASKER_RELEASE_KEY_ALIAS`, and
`OPEN_TASKER_RELEASE_KEY_PASSWORD`. The key file stays outside this repository,
and it has to: Android ties an app's identity to its signing key, so a key that
leaks or gets lost costs every installed copy an uninstall before it can
upgrade. That has happened twice here. `SigningMaterialCustodyTest` fails the
build if a keystore, a `.p12`, or a `signing.properties` ever gets tracked,
including through `git add -f`.

```bash
./gradlew :app:assembleRelease
```

Published release assets are named `OpenTasker-v<versionName>.apk`. Stage and check the name before
uploading anything to a GitHub release:
```bash
./gradlew :app:verifyReleaseAssetName
```
That stages the signed APK to `app/build/outputs/release-assets/` under the published name and fails
if the staged file is still AGP's default `app-release.apk`, if more than one asset is staged, or if
the name disagrees with the `versionName` in `tools/release-truth.json`. Upload from that directory,
not from `app/build/outputs/apk/release/`.

F-Droid profile:
```bash
./gradlew -PopenTaskerDistribution=fdroid :app:assembleRelease :app:verifyFdroidReadiness :app:verifyFdroidMetadata
```

Play manifest policy check:
```bash
./gradlew -PopenTaskerDistribution=play :app:verifyPlayManifestPolicy
```

Full local release gate (pinned Gradle bootstrap verification, blocking lint, the configured JVM test floor, JaCoCo coverage floors for scheduling/resilience/receivers/UI utilities, Room schemas, Android-test compilation, resolved dependency/SBOM and OSV policy, configuration-cache reuse, plus Play and F-Droid release builds):

Release-facing version, SDK, capability-count, schema, and required artifact-commit claims are generated and checked from [`tools/release-truth.json`](tools/release-truth.json) by the same gate.

Performance evidence is local and explicit. The quality gate validates the committed baseline-profile artifact and compiles the API 35+ Macrobenchmark harness; collect device evidence with `./gradlew :app:generateBaselineProfile` and run the release-like benchmark APK with `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`. The harness records cold-start (`StartupTimingMetric`) and first-navigation (`FrameTimingMetric`) results. Review repeated clean runs before changing a regression budget; hosted CI is intentionally not required.

Current release claims come from `tools/release-truth.json` and the versioned README/CHANGELOG. The local quality gate reports stale version, schema, and capability claims found in ignored historical research files as warnings; labeled historical snapshots remain non-blocking.

The gate writes the debug JaCoCo XML report to `app/build/reports/jacoco/debugCoverage/debugCoverage.xml` and an HTML drill-down beside it. Its machine-readable JVM test report records the observed JVM test count and configured JVM test floor separately; the floor is a release threshold, not a claim about the observed suite size. The four area floors are explicit in `app/build.gradle.kts` and fail the gate on regression.

```powershell
.\tools\verify-local-release.ps1
```

Before it executes Gradle, the gate checks that `distributionSha256Sum` matches the configured binary distribution and that the checked-in `gradle-wrapper.jar` matches Gradle's published SHA-256. It also independently checks every dependency checksum against its recorded upstream Maven evidence, requires signature verification with an explicit trusted-key set, and rejects Gradle-generated or blanket-trust metadata. Use `.\tools\verify-dependency-verification.ps1 -UpdateOrigins` only after deliberately reviewing a dependency change; the normal verifier must pass without that switch. Use `.\tools\verify-local-release.ps1 -BootstrapOnly` for the fast bootstrap preflight alone. The full gate writes those verified hashes into the machine-readable report under `build/reports/opentasker/`. To prove failure propagation without running the full build, run `.\tools\verify-local-release.ps1 -SeedFailure`; success is a nonzero exit with `Seeded local quality-gate failure`.

The repository uses Gradle's local build cache only; do not configure an untrusted remote build cache while Kotlin build-cache metadata is affected by [GHSA-r937-wjx7-w2jp](https://github.com/advisories/GHSA-r937-wjx7-w2jp). Revisit the Kotlin/Compose compiler batch when Kotlin `2.4.20` is stable.

Treat a wrapper upgrade as one atomic change: run `gradlew wrapper --gradle-version <version> --distribution-type bin --gradle-distribution-sha256-sum <official-bin-sha256>`, verify the regenerated JAR against Gradle's published wrapper-JAR checksum, update both expected hashes in `tools/verify-local-release.ps1` and `ReleaseTruthContractTest`, then run a clean wrapper bootstrap and the full local release gate. Never update only the distribution URL or only the executable JAR.

---

## Development

| Property | Value |
|----------|-------|
| Kotlin | 2.4.10 |
| Gradle | 9.7.1 |
| AGP | 9.3.2 |
| KSP | 2.3.11 |
| Build Tools | 36.0.0 |
| Macrobenchmark | 1.5.0-rc02 |
| JDK | 17 or 21 |
| Min SDK | 26 (Android 8.0) |
| Compile SDK | 37 |
| Target SDK | 37 |
| Room | 2.8.4 |
| Compose BOM | 2026.08.00 |
| WorkManager | 2.11.2 |

All dependency versions are centralized in `gradle/libs.versions.toml`.

---

## Planned

`data.read` supports bounded HTML extraction with CSS selectors and normalized element text. Parsing is local-only and never fetches linked resources; the pinned jsoup parser is MIT-licensed and included in the F-Droid-compatible dependency set. The regex-matching selectors (`:matches()`, `:matchesOwn()`, `:matchesWholeText()`, `:matchesWholeOwnText()` and `[attr~=regex]`) are refused with an explanation, because jsoup evaluates them with a backtracking engine that a pathological pattern can run for minutes inside a call the task timeout cannot interrupt. Every other selector, including `[attr^=]`, `[attr$=]`, `[attr*=]` and `:contains()`, works as usual.

Key remaining work:

- Broad device-verified background geofence reliability evidence
- API 37 platform readiness pass (FGS, predictive back, large-screen QA)
- Device-run performance evidence is collected locally through the checked-in Macrobenchmark and Baseline Profile harness

## Non-goals

These are settled, and each one is a deliberate trade rather than a gap:

- **No accessibility-service automation.** No UI scraping and no synthetic taps. Triggers keep working when an accessibility service is disabled by policy, by Advanced Protection, or by an OEM, and every action can honestly declare what it will do.
- **No cloud sync, account, or remote execution.** Automations are local. This is what makes "no account anywhere" a fact rather than a slogan.
- **No URL or network import for shared profiles.** An imported bundle is untrusted input that can request capabilities; the local review step is the control.
- **No Google Play Services dependency**, including for geofencing. geofences are evaluated in-app from platform location fixes.
- **No crash reporting or analytics**, self-hosted or otherwise. The redacted diagnostic export you choose to share is the only way anything leaves the device.

---

## License

MIT. see [LICENSE](LICENSE).

## Contributing

Issues and pull requests welcome. Open an issue to discuss a feature before building it. the Non-goals above are settled, and everything else is fair game.

[CONTRIBUTING.md](CONTRIBUTING.md) has the build and test commands, a map of where things live, and the source guards that will fail your build before a reviewer sees it.

### Translations

OpenTasker supports localization. The current release ships English only; English source copy lives in `app/src/main/res/values/strings.xml`, `action_catalog_strings.xml`, and `dynamic_surface_strings.xml`. The app uses AGP's generated per-app language configuration with `en-US` as the default. An alternate locale is included in a release only after at least 80% of the default `<string>` resources have genuinely translated values; incomplete or empty locale directories are rejected by the release gate. Debug builds enable Android's `en-XA` and `ar-XB` pseudolocales for expansion and right-to-left checks. To contribute a translation:

1. Copy the three translatable XML files from `app/src/main/res/values/` to `app/src/main/res/values-<locale>/`
2. Translate only the string values (not the `name` attributes)
3. Omit strings that are identical to English. Android falls back automatically, but the locale must still reach the 80% translated-value threshold
4. Run `./gradlew :app:verifyLocaleResources` and submit the locale directory only when it passes

No incomplete locale directories are kept as release placeholders. The gate reports the exact translated-string count when a locale falls below the threshold.
