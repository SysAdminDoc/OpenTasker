# Changelog

## Unreleased

### Security

- Updated jsoup to 1.23.2, which fixes a denial of service in its XML parser
  (CVE-2026-75140). OpenTasker only ever fed that library HTML, so nothing here
  could reach the affected code, and a build check keeps it that way.
- A release build that is meant to be signed now fails when the signing
  variables are missing, instead of quietly producing an unsigned APK. Staging
  a release asset also verifies the APK really carries a signature, so an
  unsigned build cannot reach a release page under the published filename.

### Fixed

- A secret typed with different capitalisation than the one you stored is now
  removed from exports too. Matching was case-sensitive, so a secret saved as
  `sk-live-abc123` and written into an action, a "Run only if" guard or a label
  as `sk-Live-ABC123` went out in the clear in the exported bundle, the paste
  text, a shared profile and the diagnostic report. It is the same credential
  either way.

- Read data from HTML no longer accepts the selectors that can hang a task.
  `:matches()` and its variants, plus `[attr~=regex]`, are matched by a regular
  expression engine that certain patterns send into a search that runs for
  minutes, and nothing could interrupt it once started, so the task sat there
  rather than failing. Those five selectors are now refused up front with a
  message naming the one you used and what to use instead. Every other selector
  is unchanged, including the `^=`, `$=` and `*=` attribute matches and
  `:contains()`.

- Cards that open and close a section now tell a screen reader what they are
  and whether the section is already open. Seven of them, across Setup, the run
  log, the Inspector, the scene library and both automation lists, previously
  said only "double tap to activate", with the same words whether the next tap
  opened or closed. Finishing a backup, an import or a preflight is also spoken
  now instead of changing silently on screen.

### Added

- Importing from Tasker now understands three more of its actions. HTTP Request
  arrives as an HTTP Request here, keeping the method, address, headers, body and
  timeout; Set Clipboard and Write File arrive as themselves. A Write File set to
  append becomes an append rather than a write, so importing one can no longer
  wipe the file it was meant to add a line to. Anything the import cannot carry
  across, such as query parameters or an attached file, is named in the import
  review instead of being dropped quietly or put in the wrong box.

- **Write Setting**, a new action that changes one Android setting by name, the way
  Tasker's Custom Setting does. The Global and Secure tables need access that no app can
  ask for and no settings screen offers: you grant it once from a computer, and a new
  Setup row hands you the exact command, with the MIUI caveat noted. The System table
  uses the Modify system settings access you may already have. Whatever you write is read
  back afterwards, so a value Android quietly ignores is reported as a failure instead of
  a success, and without the access the action refuses with the command in the message.
  Settings that control what other apps are allowed to do are refused outright, whatever
  the table: accessibility services, notification access, the keyboard, the package
  verifier, installs from unknown sources and USB debugging among them. That access is
  granted once and then stays, so a profile you import later must not be able to reach
  those through it.
- A profile can now trigger on a broadcast another app sends. Name the intent
  action, and OpenTasker listens for that one action while the profile is
  enabled and stops the moment you disable it. Extras come through as bounded
  text, so a task can read `broadcast_extra_msg` the way it reads any other
  variable, and anything too big or too complex to carry safely is dropped with
  a note rather than half-copied. You can also filter on an extra's name or
  value. Filtering on the sender is offered but rarely useful: Android only
  names the sender when the sending app opts in to sharing its identity, and
  almost nothing does, so the editor says as much where you type it.
- The HTTP Request action can be restricted to a kind of connection: Wi-Fi,
  mobile data, or anything unmetered. A restricted request is refused before
  anything is sent, with a message naming what the connection actually is, and
  the task preview says so too. It used to take a flow-control branch on a
  connectivity variable, which raced the request it was guarding.
- The overflow menu on any action in a task now offers "Run this action". It
  runs that one action on its own, so tuning an HTTP call or a variable no
  longer means running everything above it first. The run appears in the run log
  under its own name. Flow-control markers are not offered, because an "if"
  without its "end if" is not a smaller task.
- Settings has an About section. It shows the version and build you are running,
  links out to the source, the release notes and the licence, and offers a
  "Report a problem" button that opens a new issue with your build and device
  already filled in.
- The Diagnostics screen can copy its redacted report to the clipboard. Sharing
  it opened a chooser, which is awkward when what you want is to paste it into a
  bug report.
- A running import, export, or preview can be stopped. The review dialog's
  button becomes Stop while work is in progress and says which step it is on,
  instead of going grey and refusing to close.
- Installing a starter template that needs a permission now opens Setup showing
  just the grants that template is waiting on, instead of leaving the new
  automation to fail on its first run with nothing explaining why.
- Settings has a "Run onboarding again" action that reopens the starter
  templates.

### Fixed

- The "all Bluetooth devices disconnected" and "some connected" triggers recover
  after you turn your Bluetooth profiles off and on again. OpenTasker stops
  listening while none are enabled, so anything that disconnected in the meantime
  went unnoticed, and both triggers then stayed wrong until the app restarted.
- Starting a task set to "abort existing" no longer cancels an unrelated task
  that happened to be running it as a step. The two shared an identity, so the
  caller was stopped and its run log said it had been replaced, when nothing had
  replaced it. The step now fails on its own and the calling task carries on.
  "Abort new" had the mirror image of the same bug: a task could refuse to start
  because another task was running it as a step.
- Global variables set by a run that was cancelled or that failed are now saved.
  Only a run that finished normally saved them, so a profile set to restart on
  every trigger lost the values on every restart but the last, and the run log
  still showed the write that had been thrown away.
- Exporting a bundle no longer writes a secret you typed into a "Run only if"
  guard or an action label. Only action arguments were cleaned, so a guard like
  `%Pin == 4321` carried the value into the exported file, the paste text, and a
  shared profile. The whole guard is replaced rather than just the secret inside
  it, so an action that was guarded before export cannot come back unguarded,
  and the export warns you that something needs re-entering.
- Setup counts what it shows you. The progress line counted by section while the
  rows were marked required or optional by a different rule, so a fresh install
  read "1 of 3 ready" above four rows marked Required. The sentence under it no
  longer cuts off mid-word either.
- A Quick Settings tile no longer runs its task straight from the lock screen.
  Anyone holding your phone could tap one and run whatever it was bound to, which
  might send a message or run a script. The tap now waits for you to unlock and
  then runs, so nothing is lost except the shortcut for someone who should not
  have it. Devices with no lock set are unaffected.- Secret variable values and the backup passphrase now use the password keyboard.
  They were masked on screen but typed as ordinary text, so the keyboard's
  autocorrect and personal dictionary could learn them and offer them as a
  suggestion in another app later.
- Another app can no longer stop OpenTasker by sending it a malformed push or
  Locale message. Both of those entry points are open to any app by design, and
  reading a value the sending app deliberately made unreadable took the whole
  automation service down with it. Such a message is now discarded and noted.
- Tapping a notification button, or triggering a task from another app, now
  starts the automation engine properly when OpenTasker was not already running.
  It used to run that one task and then sit there looking healthy while no
  trigger worked, until the next minute tick woke it up.
- The Inspector no longer says a profile is "Blocked" when nothing is being
  refused. Those findings now read "Conflicts", which is what they are for a
  profile that already exists and already runs. The word is kept for the imported
  profile review, which genuinely withholds the enable button, and now says
  "Blocks enabling" so it names what it stops.
- A Wi-Fi profile no longer goes deaf after an unrelated edit. Wi-Fi and
  internet state shared one slot, so a matcher rebuilt by any profile change saw
  only whichever of the two had spoken last. A profile on "connected to Home"
  could stop matching until the next time you actually joined or left a network,
  and because OpenTasker still believed it was active, the exit task did not run
  when you did leave.
- Asking for a run while one is already going now says so. Run task, Run this
  action, and replaying a held run all refused a second request silently, so a
  tap did nothing with no explanation, for up to five minutes if the earlier run
  was waiting its turn. The overflow menu also shows "Running…" instead of
  offering an option that would be ignored.
- Lock device works. It has been in the action list for a long time while always
  failing, because Android only lets an app lock the screen through a device
  admin and OpenTasker had none. Setup now has a Lock screen admin row that turns
  one on. It asks for the single force-lock permission, so it cannot wipe your
  device, set a password policy, or read anything, and the same row turns it back
  off. Worth knowing before you turn it on: Android will not let you uninstall an
  app while its device admin is active.
- An HTTP Request restricted to Wi-Fi is no longer refused on a Wi-Fi network
  that has no route to the internet. A router with its uplink down, a captive
  portal, or a plain local network between two devices all counted as "offline"
  before, which blocked exactly the local-network requests the restriction was
  meant to allow. When some other restriction does refuse a request, the message
  now says the connection has no internet access instead of leaving you to guess.
- The notification shown while a scene overlay is on screen now uses the
  OpenTasker glyph. It was still showing Android's stock compass icon, which
  made it look like it came from another app. The home screen widget was doing
  the same thing with Android's media-play triangle.
- A backup that fails while being written no longer leaves two hidden SQLite
  side files behind. One pair was orphaned per failed backup, and nothing ever
  cleaned them up because the backup list cannot see them.
- A scheduled snapshot whose upload does not finish now says so in the log if
  the partial file could not be removed from your folder, instead of leaving it
  there silently.
- Leaving the Setup screen mid-backup no longer leaves the backup card stuck on
  "Working".
- The run log can be cleared on demand from the retention card. Pinned runs and
  held runs waiting to be replayed are kept, and it asks first because a purge
  cannot be undone.
- Variables and Flow no longer flash their empty state for a moment on a cold
  start before stored data arrives, which read as data loss.
- The Variables tab's empty state offers a "New variable" button and explains
  what variables are for instead of naming action IDs.
- Dismissing the first-launch template dialog no longer counts as finishing
  onboarding, so backing out of it does not hide the flow forever.
- The F-Droid metadata gate no longer fails on a stale store-screenshot version
  stamp. The v0.2.93 bump left it recording the previous release's code.

### Changed

- Reworded the parts of the interface that were written in the engine's own
  vocabulary. Run limits, protected device settings, direct boot, templates, and
  the background job names now say what they do. "Burst start limit" is "Limit
  rapid repeats", "Admission overflow" is "When the limit is reached", and three
  screens that said an action would "fail closed" now say it refuses to run.
- Taps now play the standard Android click sound, which arrived with the Compose
  update underneath. If you have touch sounds turned off in your phone's Sound
  settings, you will not hear them; OpenTasker follows that setting rather than
  overriding it.
- Dropped the Navigation Compose library. The app has always moved between its
  top-level screens on its own, so the library was being packaged and listed in
  the bill of materials without a single line of code using it.
- Updated SQLCipher to 4.18.0, which carries SQLite 3.53.4. This also moves
  androidx.sqlite to 2.7.0 underneath Room.
- Updated OkHttp to 5.5.0, which stops the hostname verifier accepting hosts
  that fail IP canonicalisation and applies HTTP/1 timeouts while a request body
  is still being flushed. Both sit under the HTTP Request and Home Assistant
  actions. This also moves Okio to 3.18.1.
- Every text file is now stored with LF endings, so a small edit to the
  changelog diffs as a small edit.
- The install instructions and the store changelog now explain that v0.2.93 is
  signed with a new key, will not install over v0.2.92 or earlier, and needs a
  backup exported before the old copy is uninstalled.

## v0.2.93

### Security

- The development signing key is no longer stored in this repository. Standard
  release builds now use an external local signing identity, while F-Droid builds
  remain unsigned for F-Droid to sign.
- v0.2.93 uses a new signing identity because the old key was publicly available.
  Anyone who installed v0.2.92 must uninstall it before installing this release.

## v0.2.92

### Changed

- New app icon: adaptive, themed (monochrome) and legacy variants regenerated from the 2026-08 icon set.

## v0.2.91

OpenTasker has a new launcher identity and a tighter, quieter interface built for dense automation work.

### Added

- A new OT launcher mark with adaptive foreground, monochrome themed-icon support, density-specific legacy icons, a notification glyph, Play Store artwork, and a launch screen that stays crisp across Android launcher masks.

### Changed

- The app shell and every main destination now share one navy, cyan, and AMOLED design system. Headers, project scope, navigation, cards, fields, controls, spacing, and status treatments use the same compact visual language.
- Profiles opens directly on its search and automation rows. Run Log keeps every export action visible on a phone. Scenes uses a bottom create action, while Inspector shows one selected profile and its context sources before deeper diagnostics.
- Setup, Variables, Flow, Diagnostics, and Settings were rebuilt around flatter surfaces, smaller radii, clearer status color, and less repeated copy.
- Updated kotlinx-collections-immutable to 0.5.2, which corrects wrong results from bulk add, remove and retain operations on persistent hash sets and maps when a collision node meets a single element.

### Fixed

- A failed Termux script now says which step failed instead of blaming the hash. The old wording covered a missing result, a Termux error, and a genuine mismatch with one sentence, so a correct hash still read as wrong. The five causes now report separately: no result came back (with the allow-external-apps setting named, since that is the usual cause), Termux reported an error (with its own message and code), the check exited non-zero (with the code and the script path, which covers a missing script or a missing sha256sum), the output held no readable hash, and a real mismatch that prints both the approved hash and the one Termux computed. The run log records the Termux error code alongside the exit code, and script output stays redacted throughout.
- Termux scripts could never run. Termux reports "no internal error" as `Activity.RESULT_OK`, which is -1, and OpenTasker treated any value other than 0 as a failure, so the hash check that runs before every script was thrown away before the hash was even compared. The script then failed with "Termux could not verify the approved script hash" even when the SHA-256 the user entered was correct. A result carrying -1 or 0 now counts as success, a result Termux never delivered is tracked separately from one that arrived reporting an error, and the same check on the execution step was fixed with it.

## v0.2.90

Imported "Run only if" guards now hold on flow-control actions at runtime, and they survive a Tasker XML export.

### Fixed

- The engine skipped its generic run-only-if check for flow-control actions, because those dispatch before the guard evaluation every ordinary action goes through. An imported "Stop If" therefore stopped its task unconditionally even though the guard survived import intact. An unmet guard now skips the control action instead: Stop falls through to the next action, For and Try skip their whole block, End For exits the loop, an If applies a guard that differs from its own test alongside that test, and a guarded Else runs as Tasker's Else If. A guard the importer copied into an If's own test field still evaluates once, and the dry-run preview simulates the same rules the engine enforces.
- Tasker XML export writes an action's run-only-if guard back as the ConditionList element real Tasker exports use, so a guarded action survives an export and re-import instead of coming back unconditional. A guard that cannot fit Tasker's single lhs/op/rhs shape (boolean chains, template expressions) is dropped with a warning rather than exported wrong, and a guard containing a secret value is omitted like every other sensitive field.

## v0.2.89

Tasker imports now keep their "Run only if" guards. The importer fix was contributed by felalex.

### Fixed

- The Tasker XML importer reads the ConditionList element that real Tasker exports attach to a guarded action. Guards used to vanish on import, so a guarded action came in unconditional and an If action's own test degraded to the literal "true" with no warning. In one real backup that touched 118 of 210 actions. The importer now covers the full known operator set (string and numeric comparisons plus Is Set and Not Set), writes the parsed condition into flow.if's editor field so it doesn't open blank, and reports a lossy warning for a multi-condition list or an unknown operator instead of staying silent.
- Imported Matches and Doesn't Match conditions evaluate through the same linear-time RE2 path and length bounds as every other runtime-built pattern, so a wildcard-heavy imported guard can't stall task execution. The condition parser also requires whitespace around the new ~ and !~ operators, keeping a literal tilde inside a value from breaking an unrelated comparison.
- A regex Matches guard (Tasker ops 4 and 5) has no regex condition operator to land on, so it imports as a plain wildcard match. That approximation now shows up as a lossy import warning rather than being applied silently.

## v0.2.88

OpenTasker now imports MacroDroid backups and makes variable or project deletion reversible. This release also adds Wi-Fi scanning and always-on display control, with reliability fixes across scheduling, plugins, imports, storage, and diagnostics.

### Fixed

- Sunrise and sunset windows fire once per occurrence instead of once a minute. The matcher now stamps a stable `eventId` on the matched window so later ticks in the same window are treated as the same pulse. An exact-minute sun context still fires on its minute.
- Locale plugin setting broadcasts fail closed when the plugin's receiver requires a permission OpenTasker does not hold. Android drops those broadcasts with no exception, and the action used to log Success anyway. Condition queries use the same delivery check. The send path still uses the one-argument `sendBroadcast(intent)` after that check; the two-argument overload would have required the plugin to hold OpenTasker's permission instead of the other way around.
- A sunrise or sunset window that wraps midnight still counts as one occurrence. Identity uses the date of the window start, so ticks after 00:00 do not start a second pulse.
- Enabling or running a profile that uses `brightness.set` or `screen.timeout` now stops at Setup when Modify system settings is missing, instead of logging a Failure row that looked like an engine bug.
- Wake and reboot no longer read arguments their editors cannot offer. Leftover `duration_sec` and `mode` values were logged or rejected as if the UI supported them.
- Imported blueprint input keys that collide with the template dialog's section-header keys are rejected, and the dialog namespaces its own row keys so Compose cannot crash on a duplicate.
- The Context Inspector empty-state pill, diagnostic share sheet, document-open errors, Run Log source labels, and import-review "N more" line resolve through string resources. `LocalizationSourceTest` now flags `label = "..."`, share-sheet subjects, and chooser titles.
- The Setup theme menu exposes radio-button semantics for the selected mode.
- The Variables summary no longer shows empty-state copy when variables already exist. A non-secret value typed in the editor survives rotation without putting secrets into saved instance state.
- Locale plugin actions no longer report Success for outcomes they never observed. A condition query whose plugin timed out or answered `RESULT_CONDITION_UNKNOWN` used to produce a green run-log row, because the unknown state fell through the same branch as a genuine "no". Unknown now fails the action, and the run-log trace distinguishes it from an unsatisfied condition. The reported state is still written to the result variable first, so an automation using continue-on-error can branch on it.
- A `timeoutMs` that isn't a whole number of milliseconds now fails a Locale plugin action instead of silently reverting to the five second default.
- A dispatched Locale setting says in the run log that delivery is unconfirmed. The Locale protocol has no acknowledgement for `FIRE_SETTING`, so nothing about the plugin acting on it was ever observed.
- Reordering an action from its overflow menu now announces which action moves. The menu items had been left with only "Move up" and "Move down" after the workspace redesign, so a screen reader gave no way to tell one row's controls from another's.
- The Run Log export buttons are reachable on narrow devices. They sit in a lazily composed row, so on a phone the ones past the right edge were never composed.
- Migrating a pre-v9 database no longer leaves a residual column default on the profiles table, which Room's schema identity check rejected.

### Security

- Tasker XML import, the XML budget preflight, and `data.read` XML parsing share one best-effort factory hardener, so a later copy cannot quietly go fatal on Android's Expat factory again.
- `org.bouncycastle` moves from 1.79 to 1.85, clearing CVE-2025-14813 (Critical), CVE-2026-5588 and CVE-2026-0636. It is a test-only dependency, so nothing reached the APK, but the advisory was live against the repository.
- SQLCipher moves from 4.15.0 to 4.17.0, picking up SQLite 3.53.3 and a fix for mlock warning-log spam on Android.

### Changed

- Macrobenchmark and the baseline-profile plugin move from `1.5.0-alpha07` to `1.5.0-rc01`, and the Compose screenshot plugin from `0.0.1-alpha15` to `0.0.1-alpha16`. Neither reaches the release runtime classpath; they are the build tooling the earlier dependency pass deliberately left alone.
- Losing the Android Keystore master key now fails with a named `DatabaseKeyUnavailableException` instead of a bare error. It is still terminal, and deliberately so: the SQLCipher key exists only inside that wrapped blob, so a Keystore reset or a restore onto another device leaves the encrypted database unreadable. Naming the failure is what lets a caller tell it apart from something worth retrying.

- The `core` modules own their sources. They used to point their source sets at files under `app/`, so the app and each module compiled the same classes and the module dependencies had to be `compileOnly` to stop R8 rejecting the duplicates. Model, logging, storage and the engine primitives now live in their own directories and the app depends on them for real. The release APK holds exactly the same classes it did before the move.
- Scenes declare only the four element types that actually draw: button, text, slider and image. The other twelve (edit text, checkbox, toggle, number picker, spinner, map, web, menu, video, oval, rectangle, doodle) were pickable in name only. The editor never offered them and the overlay drew a grey "unsupported" label, so they were removed rather than handed to translators. A scene saved with one of them still loads: the element becomes a text element, keeps its position, size, caption and task bindings, and writes the fallback the next time the scene is saved. Element types written in another case, as a hand-edited bundle may do, still resolve to the type they name rather than falling back.
- The workspace view model and app shell were split below their line ceilings. Undo/redo restore, the Modify-system-settings admission check, and the navigation chrome each moved into their own file, and the source gates that used to require a symbol to live in a named file now scan the screens package instead. Behaviour is unchanged; the point is that the next feature no longer has to start with an unrelated extraction.
- Package-archive API 35 calls keep a method-scoped NewApi suppress instead of silencing the whole helper object.
- The UnifiedPush connector moves off the `3.3.4-rc1` release candidate to `3.3.4` stable, which shipped since the pin was made. No pre-release artifact remains on the release runtime classpath.
- `androidx.tracing` 1.2.0 to 1.3.0 and `androidx.core` 1.18.0 to 1.19.0.

### Added

- Variable and project deletion now offer Undo from the success snackbar. Variable history keeps secret values encrypted, and project restore moves only the captured members while re-encrypting secrets for their restored project.
- MacroDroid full backups (`.mdr`) and single-macro shares (`.macro`) can be imported through the existing review flow. Every imported profile starts disabled. The review reports mapped actions, unsupported placeholders, trigger conversions, constraints, and lossy fields before Room is touched, and the same JSON, token, depth, entity, action, context, and string budgets used by OpenTasker bundles apply.
- A `wifi.scan` action that lists nearby access points into variables, which is the missing half of working out roughly where a device is without Play Services. It is deliberately honest about what Android allows: scans are rate-limited and `getScanResults` returns the last cached scan either way, so the action reports whether a fresh scan was even accepted and how old the newest result is, rather than implying it just looked. Results are count-capped and each field length-bounded, BSSIDs are treated as sensitive, and the run log records a count rather than the list of networks a device can see.
- An always-on display action, so "turn AOD on while a notification is showing" can be automated. It writes the secure setting through the existing Shizuku transport with a pinned argv, and then reads the value back: plenty of OEM builds accept the write and ignore it, and this reports that as a failure instead of a green run-log row. A device that has no such setting at all says so rather than pretending.
- The paste box accepts Tasker XML, not only OpenTasker JSON. Copy a task or profile out of Tasker, paste it in, and it opens the same review the document picker does: disabled by default, with the mapped, unsupported and lossy report. JSON paste is unchanged, and an oversize or DOCTYPE-carrying paste is still refused before anything reaches the database.
- Diagnostics lists every scheduled worker with how its last run ended and when. Android 16 ties WorkManager quotas to the app standby bucket, so a user who rarely opens OpenTasker can drop into `rare` or `restricted` and have automations stop with nothing to look at. Each worker now records its own outcome, and a stop the platform decided ("out of run quota for the standby bucket", "app standby bucket") reads differently from a normal finish and from a failure. A stop is also kept separately from the latest outcome, so a later run finishing normally cannot erase it. The same rows go into the diagnostic export.
- An Install section in the README, with an Obtainium badge that adds this repository as a source in one tap. It also says plainly that the `v0.3.0`, `v0.4.1` and `v0.4.2` releases are dead: they predate the current signing key, carry versionCode 1, and cannot be upgraded in place.
- An instrumented test compiles every regex literal declared in production source against Android's ICU engine. `:app:generateRegexCorpus` extracts the patterns into an androidTest asset, and the test fails on any the device rejects. Three shipped defects came from patterns that compile on a desktop JVM and do not on Android, and a JVM suite structurally cannot see them.
- `CONTRIBUTING.md`, with the build and test commands, a map of the package layout, and the source guards that fail a build before review.
- `docs/EXTERNAL_INTENTS.md` is now tracked, so the README's only documentation link resolves on github.com instead of 404ing. `:app:verifyDocumentationTruth` fails when the README links to a path that is missing or untracked.
- Store listing icon and feature graphic at the Fastlane paths IzzyOnDroid reads, rendered from the shipped adaptive icon by `tools/render-store-assets.py`. `:app:verifyFdroidMetadata` now fails when either is missing or the wrong size.
- `:app:verifyReleaseAssetName` stages the signed APK under its published name, `OpenTasker-v<versionName>.apk`, and fails if the staged file is still AGP's default `app-release.apk` or disagrees with the recorded release version.

## v0.2.87

OpenTasker's ten main workspaces now share a compact, true-black visual system designed for faster scanning and fewer competing controls.

### Changed

- Reworked Profiles, Tasks, Variables, Flow, Scenes, Inspector, Setup, Run Log, Diagnostics, and Settings around a consistent page hierarchy.
- Promoted project scope, readiness, search, and the primary page action while moving secondary commands into contextual menus.
- Standardized the five-item bottom navigation and kept advanced workspaces in a concise More menu.
- Tightened cards, permission rows, action sequences, backup controls, fallback settings, and Termux configuration to match the new density.
- Made AMOLED black the safe default for first launch and unknown stored theme values.

### Design

- Added a complete page-by-page visual reference set and side-by-side emulator comparisons.
- Verified every destination on a Pixel 7 API 35 emulator and recorded the remaining minor parity notes in `design-qa.md`.

### Added

- Scheduled configuration snapshots can now be written as encrypted `.otbackup` v2 archives to a user-selected Storage Access Framework folder. OpenTasker retains the folder grant, applies count-and-age retention only to its own archives, and reports lost access in Setup; the user-held passphrase remains required after reinstall or on another device.

### Fixed

- Speed contexts now unregister a partially registered location listener when a later provider rejects registration, preventing repeated GPS listeners and continuous battery drain.
- `screen.off` now sends Android's non-toggling sleep key instead of the power toggle, so repeating it cannot wake an already-dark display.
- Removed the exported AppFunctions service, metadata, and dormant policy prototype; the feature had no user approval path and therefore could never run, but still exposed a platform-bound component.

### Changed

- The release coverage gate now protects the production `VariableExpander` path at a 70% instruction floor instead of measuring an unreachable resilience shim, which has been removed.

## v0.2.86

Audit-driven release: a deep multi-pass audit filed 60 findings, and this ships all of them plus a critical defect the audit itself missed.

### Fixed: critical

- **Selecting any guided template crashed immediately.** The automation feature module genuinely owns its Compose input field, but `:app` declared it `compileOnly`, so the APK referenced two classes that were never packaged. The module is now a runtime dependency, both debug and minified release APKs contain the classes, and an on-device Compose test opens the template slot dialog.
- **Tasks could not run on any real device.** `TaskRunner` compiled a regex with unescaped closing braces in a class initializer. Desktop `java.util.regex` accepts that; Android's ICU engine rejects it, so the initializer threw on first use and every task execution failed: not only ones using array references. Present since 2026-08-10 and invisible to 1255 green JVM tests. Found by the new end-to-end instrumented test.
- **Deleting a project destroyed every secret variable it held.** Variables were moved by copying the stored row to the new project, but a secret's envelope authenticates its project id, so each relocated secret failed verification and decoded as empty and unavailable with no way back. Secrets are now decrypted and re-encrypted under the destination, and a secret that cannot be decrypted aborts the move rather than being relocated as dead ciphertext.
- **The database could freeze permanently.** The variable mutation lock and Room's write transaction were acquired in both orders: bundle import, variable rename and delete took the transaction first, the engine's commit path took the lock first: so the two could interleave and hold the single write connection until the process died. Lock order is now always mutation lock, then transaction.
- **Automations fired on unrelated events.** A pulse advanced whenever any event reached an EVENT context, whether or not the context was watching for it, so a profile whose expression was already true for another reason activated on unrelated traffic: `EVENT(nfc) OR STATE(wifi=Home)` ran on every notification while on that network, and two OR'd EVENT leaves turned one physical event into two runs.
- **NFC taps stacked a new copy of the app.** Manifest NFC dispatch starts a new activity unless the target is `singleTop`, so `onNewIntent` never ran and the armed tag-write editor stayed buried while its result went to an instance the user could no longer see.

### Fixed: data and execution

- A scheduled download to a fixed path replaced the good file with the 404 or 503 error body before the status was checked.
- `clipboard.get` turned Android's background-read denial into an empty string and reported success, silently feeding blank data to whatever came next.
- `sound.play` streamed arbitrary remote URLs, bypassing the private-network and cleartext policy the HTTP action enforces in code.
- `app.archive`/`app.unarchive` advertised themselves as supported but could never succeed: Android's confirmation response was treated as a terminal failure.
- The `unlocked` device state latched true after the first unlock for the rest of the service's life.
- Calendar automations ran once a minute for the whole event; a 60-minute meeting ran its task about 60 times.
- `data.read` with `format=xml` failed on every device: the same parser-parity defect as the Tasker XML import bug, in the one parser that never got the fix.
- Tasker XML export then re-import dropped every variable.
- Widget and shortcut runs ignored the concurrency limits and circuit breakers the in-app Run button respects, and a second tap started a concurrent run.
- Wake-on-LAN could target any public address; `ping` demanded local-network permission even for public hosts; a broker could make the MQTT client allocate up to 256 MB.
- A persisted cooldown was deleted when a profile was merely disabled.

### Fixed: interface

- A refused save no longer discards the whole form, and now says why: automation lint, duplicate names, and reference guards state their reason instead of "Operation failed".
- Cold start no longer blocks the main thread waiting for the database: the launch after staging a restore could freeze for up to 30 seconds.
- The Run Log keeps its entries while reloading, reports a failed load as a failure rather than "no runs match", and debounces search.
- Task widgets refresh after a rename or delete instead of showing a stale label and a run that cannot succeed.
- A widget task no longer leaves an invisible overlay covering the launcher for the duration of the run.
- Large editors ignore a stray tap on the scrim, the context-logic editor survives background writes, and the elapsed counter on a running task advances.
- Status bar icons are visible in the widget, quick-settings and Locale editors in light theme; light mode no longer flashes black on launch; warning text meets contrast; notifications use the app's own icon and accent.
- AMOLED card and section borders are visible again, disabled action-picker entries look disabled, and search announces its result to screen readers.
- Duplicated automations are named "Name (copy)" rather than "Name(copy)"; export and retention messages are proper plurals; and one term is used per concept throughout.

### Changed

- Packaging verification now parses every `classes*.dex` entry in both debug and release APKs and fails when an `Lcom/opentasker/` type is referenced but absent. The detector is wired into the local quality gate and was fault-tested against the missing automation-feature dependency.
- Verification: the Room schema gate now detects real drift rather than checking that files exist, the decoder fuzz harness can surface unexpected exceptions instead of swallowing them, the baseline profile must be recaptured for the release it ships in, capability counts are checked against the compiled runtime, and the JVM test floor no longer counts skipped tests.
- Performance: one shared Locale-plugin poll replaces one per context (previously N x N broadcasts per interval), and media polling only runs for contexts that read media state.

## v0.2.85

- Fixed the release build, which had failed since the staged module split: the core/* modules compile sources that still live under `app/`, so every shared class was compiled twice and R8 rejected the duplicate types. `:app` holds the only copy that ships and the module jars are no longer merged into the APK.
- The action editor now says where to satisfy a setup-gated action, not just what is missing: elevated device actions point at the Setup tab and Shizuku rather than leaving the manifest looking like the answer.
- Added an **AMOLED black** theme that uses true `#000000` surfaces, so an OLED panel can actually switch pixels off: the existing dark scheme is `#101211` and is unchanged, now named for what it is. Body text keeps 18.6:1 and secondary text 9.3:1 on black.
- Added an opt-in **Material You** theme on Android 12+, which follows the system light/dark setting and is not offered on older releases where it would do nothing.
- Profile and task lists reserve room for the floating action button, so the last row is no longer permanently covered, and empty states scroll: at large font scale their last action could previously sit off-screen with no way to reach it.
- Undo and Redo on profile, task, and scene cards are now disabled when there is nothing to undo or redo, and announce why to a screen reader, instead of being permanently live and answering with a snackbar. A setup item with no available action shows status text rather than a button that only reported the item was already ready.
- Profiles, Tasks, and Scenes now show a loading state until Room delivers its first snapshot, instead of flashing the first-run "Build your first automation" screen at every cold start for users who already have data.
- Profile execution slots now decide and store under one lock for every automation mode, not only QUEUED, so a second dispatch path reaching the same slot cannot start a SINGLE profile twice or leave a superseded RESTART job running untracked. The queue consumer takes the same lock order, and the invariant is covered by concurrency tests.
- The exported automation broadcast target now answers status and name-lookup requests with bounded `COUNT(*)` and indexed name queries instead of loading the whole profile and task tables inside the `goAsync()` window, and reuses one supervisor scope rather than creating one per broadcast. Name matching moved into SQLite's `COLLATE NOCASE`, which folds ASCII only: two names differing solely in the case of a non-ASCII letter are now distinct.
- Added an opt-in, coverage-guided JVM fuzz task for bundle, Tasker XML, template-expression, and structured-data decoders, with a checked-in seed and regression corpus kept out of release dependency graphs.
- Added headless Compose screenshot regression coverage for primary screens and shared states across system, light, dark, and high-contrast themes, 1×/2× font scales, and an RTL pseudolocale; reference validation is part of the local quality gate.
- Automation lint now reports shadowed, unreachable, and action/revert rules, and supports bounded device-state invariants with localized diagnostics, a reusable predicate editor, and optional bundle import/export.
- Added an opt-in, non-F-Droid release update check that reports newer GitHub releases without downloading or installing them.
- Added a disabled-by-default, API-36-only AppFunctions prototype that can submit only user-approved task IDs through the existing signature-protected execution boundary.
- Follow-up audit covered scene editing/runtime, legacy context producers, search/grouping/share dialogs, and widget/quick-settings configuration; no new defect was found, with large-font and inert-history-control follow-ups remaining tracked separately.
-Home Assistant Companion `message`/`data` notification envelopes are now first-class webhook inputs, and ntfy's documented push field names are accepted by the token-authenticated push bridge so a `broadcast` action can trigger OpenTasker without a relay app.
-Release truth now records the annotated release tag and sync target; the verification gate checks every versioned changelog release from v0.2.58 onward, rejects lightweight or mismatched tags, and requires the current release to be tagged before publishing its manifest.
-Scene canvas elements now announce their localized type, label, position, size, and selection state; screen-reader users can select, nudge, and resize them through custom actions, while the resize handle keeps a labeled 48dp target and the visible canvas text includes the element type.
- Action and context pickers now have clearable search across localized names, descriptions, and stable IDs; empty queries show a clear no-match state, and localized catalogs are rebuilt only when the configuration changes.
- Back navigation now returns from secondary destinations to Profiles and exits only from the start screen; app-wide settings have a dedicated primary destination separate from the permission checklist, with navigation state retained across recreation.
- Dismissing the first-run starter-template picker now completes onboarding, including Back/scrim dismissal and cancelling the template details step; the template browser remains available from the workspace.
- User-facing automation labels, setup requirements, capability levels, share trust, diagnostics levels, and scene element types now resolve through localized resources; unknown action IDs and operation failures use safe generic copy while raw details remain in diagnostics logs.
- Compose instrumentation now runs the Accessibility Test Framework across the primary UI flows, with regression fixtures for unlabeled controls and undersized touch targets.
- Locale verification now examines every existing locale directory, rejects empty directories by name, reports how many it examined, and documents that the current release ships English only.
- Shizuku now binds a versioned AIDL user service for the six allowlisted elevated actions, rechecks exact argv in the privileged process, keeps the persisted kill switch and fail-closed behavior, and unbinds the service during application teardown.
- UnifiedPush registration now uses the official connector service for distributor discovery, SDK-versioned identity registration, RFC 8291 decryption, endpoint persistence, failure status, and delivery acknowledgement; ntfy's standard JSON reaches `event=push`, while the bounded/redacted legacy broadcast remains compatible.
- Setup permission, preference, companion, push-token, and grant snapshots now load through an IO-backed ViewModel, so returning from system settings refreshes without blocking composition.
- Profile, task, and scene deletion now snapshots the complete entity for snackbar Undo/Redo, while action, context, and scene-element removal is immediate and undoable without a confirmation dialog.
- Exported Locale condition queries now require revocable grants bound to the selected profile, context, or variable; unauthorized queries return unknown before database access, and the receiver reuses a bounded scope.
- MQTT TLS publishes now pin the vetted TCP address while preserving the broker hostname for HTTPS endpoint verification and SNI; a mismatched certificate is rejected before credentials or payloads are sent.
- Success feedback now remains resource-backed from the call site through snackbar collection, so profile toggles and editor removals cannot fall through an English literal map and appear as errors; variable and scene messages retain their arguments until the active locale resolves them.
- Split model, common logging, storage, engine state, and automation blueprint input presentation into dependency-directed `core/*` and `feature/*` modules, with source-boundary contracts and an interim screen-size ceiling enforced by tests.
- Converted starter templates into versioned, serializable automation blueprints with typed selectors, collapsible input sections, bounded bundle validation, local installation tracking, and review-only update diffs that never overwrite instantiated profiles.
- New lint findings, lifecycle suppression reasons, admission decisions, duplicate names, semantic-diff labels and enum values, and run-status words now resolve through resource-backed presentation adapters, so localized screens no longer inherit English-only copy from the core engine.
- The WorkManager 2.12 metrics evaluation records the beta-only API's incremental stop-count, runtime, and retry evidence, its experimental dependency cost, and the stable-release trigger; OpenTasker remains on 2.11.2 until that trigger is met.
- The pinned build tuple is refreshed to Gradle 9.7.0, KSP 2.3.11, and Compose BOM 2026.06.01, with wrapper, checksum, release-truth, and documentation contracts updated together.
- The repository now carries the English F-Droid store listing, four current phone screenshots, and a versioned changelog; the release gate verifies the listing and screenshot capture version.
- The JSON bundle format document describes schema v2 rather than v1, including what importing a v1 bundle does and which versions are accepted. It had claimed v1 since the v2 migration shipped.
- The bundle's supported import range is published in `tools/release-truth.json` and checked against the codec and the format document, so narrowing or widening what OpenTasker will import can no longer happen without updating the published contract.
- Two checked-in bundle fixtures pin the compatibility contract: a v1 document and exactly the v2 document the codec produces from it.

## v0.2.84

- Upgrading a database created before OpenTasker encrypted its storage no longer discards it. The conversion copied the new empty file onto itself instead of reading the old one, leaving a database with no tables; the app then refused to open it. The migration now verifies it carried the tables across before publishing the result.
- Exported backups restore on a different device and after a reinstall. Both export paths wrote the on-disk ciphertext, which is keyed to a randomly generated key that is destroyed with the app's data and never transferred, so every exported `.otbackup` was unopenable in exactly the situations backups exist for. Exports now carry a portable copy inside the passphrase-encrypted envelope, and the staging copy is shredded afterwards. First-class secret values still stay on the device that created them.
- Startup recovery no longer marks a just-started execution as interrupted. Recovery runs alongside engine startup, so a boot-triggered run that reached the journal first was reported as interrupted, written to the run log twice, and then blocked from recording its real result.
- Applying a staged restore and converting a legacy database now happen off the main thread, so a large database cannot stall app start or exhaust the boot broadcast's time budget.
- The exported Locale condition receiver answers a variable comparison only for a variable the user explicitly exposed. It is exported without a permission by contract, and its bundle is supplied by the caller, so any app could previously name any non-secret variable and read its contents a comparison at a time from the result code. Choosing a variable in the condition editor now mints a read grant bound to that variable. Conditions configured before this change report unknown until the variable is re-selected.
- Simulating a trigger from the profile editor now simulates what is on screen and leaves the editor open behind it. It previously closed the editor, discarded the pending edits, and reported the values from the last save.
- Testing a synthetic event from the context editor keeps the editor open, so a fully configured context is no longer lost the moment its simulation is dismissed.
- A simulation dialog survives rotation instead of vanishing.
- Nodes changed by an undo or redo stay highlighted on the Flow tab after the review dialog closes. The highlight was tied to the dialog that covered Flow, so the dialog's own "highlighted in Flow" note pointed at something no one could ever see.
- `verifyDocumentationTruth` now checks the CHANGELOG and release-truth files it already declared as inputs, so a release with no matching changelog section fails instead of passing silently.
- The F-Droid release verifier's tag check accepts the repository's documented two-commit release flow, so it no longer has to be skipped on every release. An always-true assertion in the F-Droid readiness check is gone.
- Changed nodes on the flow canvas carry the same "Changed" pill as the list, and a screen reader now hears that a node changed along with the variables it produces. The canvas previously marked a change with border colour alone.
- The home-screen widget follows the app's palette and the light/dark setting instead of shipping the retired purple-on-navy colours in permanent dark.
- One name for the simulation feature, "Profile lifetime" instead of "Automation lifetime" beside the other profile settings, and real plurals in place of "(s)" in the diff, lint, and admission summaries.
- The locale completeness gate no longer counts non-locale resource folders such as values-night as untranslated languages.
- Tasker XML export is reachable: the workspace card has an Export Tasker XML button. The exporter shipped with no caller at all, so the changelog announced a feature nobody could run and its redaction path never ran outside tests.
- A JSON export now redacts an action argument that contains a literal copy of a secret variable's value, matching what the Tasker XML exporter already did. `docs/OPEN_JSON_BUNDLE.md` describes what export redaction does and does not cover.
- Secret variables are encrypted with the project bound into the authenticated data, so an envelope can no longer be moved between two same-named secrets in different projects and still decrypt. Existing secrets keep working and are rewritten in the new format the next time they are saved.
- Opening the trigger simulation no longer reads preferences on the UI thread, and long diff or import lists render only the rows on screen.
- Execution journal progress writes are rate-bounded instead of one database update per action, which a long flow could repeat tens of thousands of times in a single run.
- The import boundary corpus covers the DOCTYPE internal-subset scanner, the share-intent payload boundary, and the secret-variable refusal in the Locale condition receiver.
- Setup can turn on automatic local snapshots: a scheduled local database snapshot with a bounded count and age, a status line showing the last result and disk use, and a retention choice. Snapshots never leave the device and are never applied automatically - restoring one still goes through the existing review.
- Backups no longer leave orphaned `-wal`/`-shm` files beside them, and deleting or pruning a backup takes its sidecars with it.
- Two tasks in Wait mode that run each other no longer deadlock the engine. Each held a per-task lock for its whole run and acquired them in opposite orders, and because a sub-task returns before the per-action timeout nothing bounded the wait; the stuck runs then held admission leases until the engine's global cap wedged.

## v0.2.83

- The pre-unlock time trigger setting is now a single toggleable row, so a screen reader announces its name along with its state instead of an unnamed on/off switch.
- Trigger lint findings name their severity in text, and the profile editor's option groups expose radio-button selection state, so neither depends on colour alone.
- The trigger simulation dialog's cooldown and admission copy comes from string resources, with a proper plural for the countdown.
- The localization gate scans every file in the screens package instead of a hand-written list, which had let new dialogs ship uncovered.
- Run now and Replay ignore repeat taps while a run is in flight.
- Cleartext MQTT now requires every address a host resolves to be private, and connects to the address it vetted. Accepting a host because any one record was private, then letting the socket re-resolve the name, could send the connect packet and its credentials to a public address.
- `url.open` rejects a URI with no scheme instead of passing it through the scheme allowlist unchecked.
- Pasting something that is not an OpenTasker bundle now explains that in plain language instead of showing the JSON parser's own message and echoing the pasted text back.
- Out-of-range profile priority and grace period now show an error naming the valid range. Both fields accepted more digits than they allow, so a value like 500 quietly disabled Save with nothing on screen explaining why.
- An expired profile reports the date it expired rather than a raw millisecond timestamp.
- A kept run-log entry is labelled "Kept" instead of repeating the "Unkeep" button's own label.
- Diagnostics shows profile names instead of raw database ids.
- Dialogs and dropdown menus now use the app's own surfaces in all three themes. The surfaceContainer roles were never defined, so Material's purple-tinted baseline showed through on every editor dialog - lavender against the light theme's cream, and purple-grey against the dark theme's graphite.
- `flow.try` retries now require every action in the try body to be retry-safe, not just the one that failed. A retry restarts the whole body, so a body such as send-message then fetch-URL re-sent the message on each attempt.
- `%FLOW_ERROR_CAUGHT` is now `true` inside a `flow.catch` handler; it was always `false` because the catch marker that records it was skipped.
- Text such as `%count-1` expands the `%count` variable again and keeps the rest. Allowing hyphens in variable names had made the whole token scan as one undefined name and collapse to an empty string; a variable that really is named with a hyphen still wins.
- Re-delivering an external command whose id has aged out of the dedupe ledger is acknowledged as a duplicate delivery instead of reported as a failed execution.
- A global fallback task that no longer exists is cleared on load rather than left dangling.
- Held run-log entries and the execution journal are now bounded. Held rows were skipped by retention so a pending replay survived, but nothing else ever removed them - one entry per admission rejection, each up to 16 KB - and the journal was only trimmed at process start, so it grew for as long as the foreground service stayed up.
- Replaying a held entry consumes it, so the Replay action can no longer run the same held execution repeatedly.
- Manual runs and held replays are now admitted by the engine's live admission controller instead of a separate in-memory one, so they respect a saturated profile and an open circuit breaker. A failure during a manual run now reports an error instead of crashing.
- The F-Droid distribution builds unsigned again, restoring the `app-release-unsigned.apk` artifact its build recipe and the reproducibility harness both name; adding the repo-owned signing fallback had silently renamed it to `app-release.apk`. The metadata gate now derives the expected artifact path from the signing decision and refuses to run against a non-F-Droid distribution instead of reporting a pass.
- Device orientation now follows the Android sensor convention: a normally held phone reports `portrait` rather than `portrait_upside_down`, and the landscape sub-values are no longer swapped.
- Tethering state now reports the union of Wi-Fi hotspot and interface tethering and publishes an initial value on registration. Any delivery of the legacy tethering broadcast previously latched the state to on, so it stayed on after tethering stopped, and a `tethering=false` predicate could never match.
- A state context whose predicate cannot be parsed no longer starts GPS, telephony receivers, and every sensor for a context that can never match.
- Step cadence is published in buckets, so walking no longer re-evaluates every profile on each step.
- Roaming and phone-call state presets are hidden on distributions that do not declare the Phone permission, where their Setup row cannot exist.
- The new roaming, call-state, and tethering receivers register as not-exported, matching every other dynamic receiver in the app.
- Project rows in Manage projects no longer break the project name across two lines; rename is now an icon button matching the reorder and delete controls, leaving the name room to render in full.
- Duplicating a profile now keeps its review requirement. An imported profile awaiting risk review could previously be duplicated into a copy that enabled without ever showing the risk disclosure.
- Fresh installs now start with the Default project. It was only ever created by the schema 8 to 9 migration, so a new install had no project row and the first project the user created silently took the reserved default id, becoming an undeletable workspace that owned every existing task and profile.
- Missed-trigger detection no longer reports healthy minute ticks as missed. The ledger's persisted state is now the single state of record shared by the service scheduler, the tick receiver, and the watchdog, and a tick is only counted as dropped once the following tick is also overdue.
- Profiles now only yield to a *strictly* higher-priority profile. Equal-priority profiles run concurrently, so a long-lived matched profile no longer suppresses every other matching profile through a profile-ID tie-break; when a higher-priority profile deactivates, every profile it was outranking is released.
- Causal loop protection now ends when the parent execution finishes and no longer treats a profile's own exit task as a loop back into itself, so exit tasks and ordinary re-triggers within 30 seconds are no longer skipped as "Causal profile cycle stopped".
- The `state.temporary` action now captures the current setting before applying a reversible change; it previously read the target action id out of the target's own arguments and failed every invocation with "current state is unavailable".
- The local release gate now reports observed JVM tests separately from the configured release floor, and the README uses explicit threshold-versus-observed wording instead of duplicating a suite count.
- Admitted executions now persist a bounded journal with source/lineage and last known step, reconcile process-death interruptions into one visible Interrupted run-log outcome, and never retry interrupted work automatically.
- Import boundaries now share a deterministic hostile-input corpus covering OpenTasker JSON, Tasker XML, external intents, Locale bundles, and Termux payloads; variable and condition contracts fail closed with bounded diagnostics.
- Exported diagnostics, run logs, OpenTasker bundles, and Tasker XML now share field-aware redaction for action metadata, secret-derived templates, credentials, URLs, and query values, with explicit secret re-entry warnings.
- State contexts now cover orientation, proximity, physical activity, speed, roaming, tethering, and phone-call state through demand-gated platform sensors and callbacks; Setup exposes Activity Recognition, location, and Phone access only when needed, and unsupported or ungranted sources fail closed with Inspector guidance.
- OpenTasker now exposes a Locale/Tasker condition-plugin target for profile-active, context-satisfied, and non-secret variable comparisons; edit/query bundles are typed and bounded, live matcher state returns satisfied/unsatisfied/unknown, and malformed or secret-bearing inputs fail closed.
- Debug builds now detect Android 17 implicit URI grants; configurable URI dispatch rejects missing explicit read/write grants, and the Sharesheet receiver checks content-URI readability off the main thread with visible failure feedback.
- Built-in actions now use one typed declaration catalogue for runtime factories, categories, retry safety, capability resolution, editor metadata binding, and release-truth counts; adding an action without a canonical declaration is rejected by the action base contract.
- The build toolchain now uses Gradle 9.6.1 and AGP 9.3.1; Kotlin 2.4.10 remains on the stable line with local-cache-only guidance for its build-cache advisory, and the release graph rejects Netty/grpc-netty components.
- jsoup is upgraded to 1.23.1, the patched release for [GHSA-pmhh-3w7g-xqp8](https://github.com/advisories/GHSA-pmhh-3w7g-xqp8); the release SBOM now reports no unapproved OSV advisories.
- Undo/redo and OpenTasker bundle import review now show typed semantic diffs for profiles, contexts, tasks, actions, scenes, and variables with sensitive values masked; changed Flow nodes are highlighted during review.
- Profiles, tasks, and scenes can now be duplicated from their overflow menus with collision-safe names, fresh nested IDs, disabled profile copies, remapped self-bindings, and undo/redo support.
- Add an opt-in Direct Boot path for the app-owned minute time trigger: a device-protected DataStore arms one bounded pending pulse before first unlock, while profiles, tasks, Room, secrets, and all other trigger families remain post-unlock only; Setup now discloses the exact scope.
- Diagnostics now reports API 36 pending-job reason history and API 37 aggregate pending durations where available, labels expanded WorkManager stop reasons including abandoned timeouts, and explains standby buckets as delivery consequences with explicit unavailable states on older Android versions.
- Active tasks now request Android 16+ promoted ongoing notifications with short status text when eligible; the standard foreground notification remains the fallback when promotion is unavailable, denied, or unsupported.
- Action rows, flow nodes, and preflight cards now render localized one-line parameter summaries through a shared redaction-aware formatter; every built-in action is covered by a summary completeness guard.
- Action metadata now declares typed outputs; action-editor text fields offer compatible outputs from earlier steps, event data, and globals as variable chips that persist as ordinary `{{ }}` templates, with array references preserved for loop/join inputs and flow nodes showing produced names.
- Unhandled task failures can now run a per-profile or global fallback task with bounded structured error variables, terminal diagnostics, and non-recursive recovery; retry state is cleared when an action eventually succeeds.
- Profiles can now override active and burst execution admission limits within bounded ranges, choose logged/held or silent overflow behavior, and surface structured rejection counts plus circuit-breaker trip state in Diagnostics and shared reports.
- Profiles now support deterministic priority arbitration, symmetric activation/deactivation grace periods, and never/date/once lifetimes with expiry and one-shot persistence; the editor, profile cards, Inspector, bundle codec, and Tasker mapping expose the policy and explain suppressions.
- Undo/redo now validates every Task, Profile, and Scene snapshot before the Room write; malformed or wrong-entity history fails closed and leaves the current record untouched.
- Release truth now derives and verifies SDK/toolchain, capability, bundle, Room schema, README, and F-Droid artifact claims from shipped sources; the generator also derives engine-handled actions from FlowControl and subtask execution.
- `flow.try` now classifies all built-in actions for retry safety, previews retryable and non-retryable actions in the editor, and records skipped-retry reasons in the run log.
- Dependency verification now requires signatures, explicit trusted keys, and independently checked upstream provenance for every checksum; Gradle-generated origins and blanket trust are rejected by the local release gate.
- Profile-triggered executions now carry causal parent/depth metadata; repeated profile chains stop with named CAUSAL_LOOP terminal reasons in Run Log, Diagnostics, and the Context Inspector.
- Diagnostics now pairs stale engine heartbeats with Android 11+ historical process-exit reasons, timestamps, and downtime gaps, with an explicit unsupported-platform state below API 30.
- Scheduled triggers now persist expected fire times; the watchdog records overdue delivery once with delay, standby bucket, and battery/exact-alarm remediation in the Run Log.
- Admission-rejected executions are now retained as HELD rows with bounded redacted trigger data and the rejecting policy; Run Log offers linked manual replay, and held or user-starred rows are exempt from retention pruning.
- Profile editors and the Context Inspector can now simulate triggers with pinned synthetic events for every context family, showing predicate, expression, cooldown, and admission decisions without running a task or writing production run-log state.
- Automation lint now reports missing reversals, repeated state triggers, conflicting setting writers, and inter-profile loops during saves, imports, flow inspection, and Context Inspector review; equal-priority writer conflicts block while other findings remain actionable warnings.
- Variable renames now rewrite legacy and template references across action arguments, conditions, profile context bindings, and scenes atomically; referenced variables cannot be deleted without showing their dependent sites.
- The local quality gate now emits a JaCoCo debug report, enforces per-area instruction floors for scheduling, resilience, receivers, and UI utilities, and ratchets the JVM test floor to 1,049 passing tests; the newly covered areas include exact-alarm decisions, graceful degradation, time-event routing, notification message formatting, side-effect-free trigger simulation, automation lint, and profile lifecycle policy.
- Locale packaging now uses AGP's generated per-app language configuration with an explicit `en-US` default; incomplete locale placeholders are removed, and the release gate rejects alternate locales below the documented 80% translated-string threshold.
- Record the current evaluation decisions to wait on Glance and Navigation3 stability, and to keep unrestricted accessibility automation out of the product.

## v0.2.82

- Declare `ACCESS_NOTIFICATION_POLICY` so OpenTasker appears on the Do Not Disturb / Modes access settings page and DND access can actually be granted; `dnd.set`, `zen.rule.set`, and `zen.rule.clear` were dead on every device without it. Adds a manifest contract test. (#4)
- Fix Tasker XML import failing on-device with "disallow doctype decl": Android's Expat-backed parsers reject the Apache secure-parsing feature URI the importer treated as mandatory, so every import failed regardless of file content. The feature is now best-effort, benign DOCTYPE prologs in real Tasker exports are stripped in text before parsing, and doctypes with entities or external DTD references are still rejected (XXE-safe). (#5)
- Cover the importer with instrumentation tests that run on Android's own XML parser. The JVM
  suite cannot see this class of defect at all: desktop Xerces accepts the feature URI Android
  rejects: so #5 shipped with every unit test green. The new tests were checked against the
  pre-fix importer: four of six fail there, including the plain no-doctype import, with the same
  message users saw.
- Sign published APKs with a signing key kept in the repository instead of the machine-global
  Android debug keystore. That file is regenerated by the SDK tooling, and the key that signed
  v0.2.79 was lost that way. **Upgrading in place over v0.2.79 or earlier will fail with a
  signature mismatch: uninstall the old build first.** This is a one-time break; the new key is
  checked in, so future releases upgrade normally.

## v0.2.81

- Add an explicit Android predictive-back callback bridge with a legacy-compatible root-screen fallback and lifecycle-safe teardown.
- Add adaptive navigation regression coverage for compact/medium/expanded widths, large-font scaling, resize/fold state restoration, and accessible navigation semantics.

## v0.2.80

- Add a build-gated `event=sms_received` SMS/MMS trigger with sanitized sender/body metadata, sender/body filters, Android 17 OTP-delay disclosure, and Play-artifact exclusion.
- Add Android 16 Advanced Protection live detection, diagnostics/setup banners, and `event=advanced_protection` enabled/disabled transitions with reflection-safe callback teardown.

- Add Android 16 Bluetooth `bluetooth_key_missing` and `bluetooth_encryption_change` event contexts with device/security metadata, editor presets, permission setup copy, and low-SDK no-op gating.

- Add owned Android 15+ Zen rule set/clear actions with grayscale, dim-wallpaper, and night-mode effects, plus transient DND fallback on older Android versions.

- **App archive actions**: added SDK-gated `app.archive` and `app.unarchive` actions with bounded PackageInstaller status handling, package validation, explicit self-archive refusal, and fail-closed permission/installer errors.

- **Screen-recording trigger**: added Android 15/API 35-gated visibility callbacks, visible/not-visible editor presets, and setup guidance for `event=screen_recording` without capturing screen contents.

- **Bluetooth aggregate trigger**: added a tracked final-disconnect transition as `event=bluetooth_all_disconnected`, an editor preset, and multi-device sequence tests.

- **Offline bundle import**: added bounded clipboard/QR-text JSON import that reuses the existing disabled-by-default review before any database write, with malformed and oversized input rejection.

- **Companion presence trigger**: added user-confirmed CompanionDeviceManager association and revocation setup, OS-managed presence callbacks, and `event=companion_presence` present/absent matching without a scan loop.

- **Matcher pulse continuity**: profile matcher rebuilds now carry event-pulse sequence state across edits and suppress replayed push/share/boot deliveries per context slot, preventing reconcile-time drops and duplicate runs.

- **Room migrations and writes**: additive schema transitions now use Room 2.8 auto-migrations backed by the exported schema history, while semantic rewrites remain explicit; profile and variable writes use `@Upsert` to remove insert/update races.

- **Performance evidence**: added a validated baseline-profile artifact and a separate Macrobenchmark module covering cold start and first navigation, with explicit API 35+ device-run commands and no hosted-CI requirement.

- **Documentation truth**: the local quality gate now checks current README release claims and reports stale version, schema, and capability claims in historical research snapshots without treating those snapshots as current product documentation.

- **USB trigger**: tasks can now react to filtered USB/input-device attach and detach events with sanitized device identity metadata.

- **Shortcut publishing**: tasks can publish dynamic launcher shortcuts or request pinned shortcuts with bounded labels, stable IDs, and explicit task bindings.

- **Progress notifications**: added an Android 16 `Notification.ProgressStyle` action with ordered segments and a standard progress-bar fallback on older releases.

- **Adaptive shell**: medium and expanded windows now use a scrollable navigation rail with every destination visible, while compact windows keep bottom navigation; the layout switches from live window width so rotation and resize preserve the selected screen.

- **Scene authoring safety**: image drafts no longer save placeholder sources; picker-selected images must decode before save, invalid imports are rejected, accessibility descriptions/decorative state flow into overlays, and shared slider/image config validation is enforced by editor, import, diagnostics, and runtime.

- **Failure recovery**: added structured Try/Catch/End Try flow blocks with bounded exponential retry for explicitly idempotent actions, redacted `FLOW_ERROR_*` handler variables, and honest validation of retry bounds and block nesting.

- **Monitor and editor coverage**: callback monitor registration now has explicit retryable lifecycle semantics, AppUsage permission loss is covered as a pause/resume policy, shake debounce and exact-alarm fallback are locked down, and Compose task-editor drafts are tested across saved-instance restoration.

- **Build toolchain**: synchronized Kotlin 2.4.10, KSP 2.3.10, Compose BOM 2026.06.00, Lifecycle 2.11.0, Coroutines 1.11.0, and stable immutable collections 0.5.1. Gradle remains pinned at 9.4.1, with refreshed SHA-256 verification metadata for the complete resolved graph.

- **Foreground precision**: application contexts can now constrain a package to an exact or bounded glob Activity component. UsageStats class names flow through the Context Inspector, while missing OEM component data is shown as unavailable and never degrades a configured component match to package-only.

- **Database privacy**: OpenTasker now encrypts the complete Room database with the F-Droid-compatible SQLCipher Community Edition. A random database key is wrapped by Android Keystore, existing plaintext files migrate atomically before Room opens, managed backup validation understands encrypted files, and incorrect keys fail closed.

- **Native release safety**: the local release gate now inspects every packaged ELF library and fails if any PT_LOAD segment is below 16 KB alignment. The current SQLCipher, DataStore, and Compose native payloads pass the audit.

- **Predictive back**: audited the target-37 activity and Compose surfaces. The manifest is opted into `OnBackInvokedCallback`, `MainActivity` has no legacy `onBackPressed()` override, and dialogs consistently use Compose `onDismissRequest`, preserving both gesture and 3-button dismissal paths through AndroidX.

- **Media context**: added a level-triggered `media_active` state with optional active-package matching. It observes local audio playback and accessible media sessions, emits stop transitions, and fails closed when notification-listener access is unavailable.

- **Keyboard actions**: added `ime.info` for current/enabled input-method variables and `ime.set` for enabled-target validation plus the system picker. Android does not allow a normal app to silently choose another keyboard, so that limitation is reported explicitly.

- **Temporary state**: added a bounded `state.temporary` action for brightness, volume, ringer mode, and DND. It captures the prior value, applies the requested setting, and uses a replaceable unique WorkManager job to restore it after a duration, including after process death.

- **Edit history**: task, profile, and scene edits now keep a bounded five-step undo/redo stack. Undo moves one revision at a time without discarding newer history, redo restores newer revisions, and saving after an undo starts a new branch. Scene edits are snapshotted with the full scene so dimensions and names recover as well as elements.

- **Global search**: added a live cross-project search for profiles, tasks, actions, variables, and scenes. Results include task/profile/scene references and deep-link into the matching editor or library surface without indexing secret variable values.

- **Personal-data actions**: added bounded clipboard get/set actions and a contacts lookup action. Clipboard and contact outputs are marked sensitive and never written to run-log messages; unattended contact lookup requires an explicit Setup grant, while Android 17+ picker mode uses a field-scoped selection with a timeout.

- **Quick Settings tiles**: added four active per-task tile slots with long-press configuration, persistent task/label/subtitle/icon/state settings, direct task dispatch, slot-aware tile events, and a functional `tile.set` action.

- **Visual system**: the command-center shell now uses a calmer sage/graphite palette, larger readable type, tighter spacing tokens, compact headers, border-light navigation, and text-led status indicators. Profile/task summaries and action/context rows rely on grouping and hairline rhythm instead of nested outlined boxes. The nine-page redesign reference is preserved at `design/mockups/opentasker-command-center-v2.png`.

- **Execution reliability**: service-owned task runs now pass through a persisted admission controller with global and per-profile active limits, burst windows, and a circuit breaker. Rejected runs are recorded as skipped with an explainable reason, while accepted leases are released safely even when cancellation unwinds the coroutine tree.
- **Execution safety**: statically provable `task.run` cycles are surfaced during imported-profile review, and newly created or edited enabled profiles are held disabled until the feedback-loop risk is explicitly reviewed.

- **Setup clarity**: the checklist now separates engine baseline, enabled-automation requirements, optional integrations, and reliability guidance. Permission rows are derived from enabled profiles and reachable task actions, so an empty workspace no longer reports unrelated automation blockers.

- **Execution identity**: profile, manual, widget, shortcut, notification, Locale, Scene, and external runs now share a bounded structured envelope and idempotent command ledger. Run Log entries persist one execution ID, producer, causal parent, and redaction-safe terminal reason; active execution state and external dispatch preserve the same identity across admission, cancellation, and terminal projection.

- **Diagnostics**: engine health now aggregates timestamped Loading/Ready/Stale/Error evidence instead of treating one old observation as current. Heartbeat freshness, matcher failures, standby throttling, exact-alarm fallback, watchdog failures, scheduler constraints, advanced-protection warnings, and active/pending executions expose concrete reasons in Diagnostics and the redacted report.

- **Context observation**: calendar and sun event pulses now use one demand-counted hot bus shared by engine matchers and the visible Context Inspector. Calendar queries start only when a calendar event is requested, and the Inspector releases its collectors when it leaves the screen while showing Loading/Ready/Stale/Error observation health and age.

- **Localization coverage**: secondary navigation, workspace notices, ViewModel messages, Context Inspector, Run Log, Diagnostics, and Flow graph copy now resolve through Android resources, with source-contract tests preventing new hardcoded visible English.

- **Locale compatibility**: host component discovery and broadcast transport are injectable for a deterministic synthetic setting/condition plugin fixture. Instrumentation coverage now exercises configuration, fire/query dispatch, request-query events, result codes, and bundle-argument redaction without depending on a third-party plugin installation.

- **Home Assistant bridge**: added a bounded outbound webhook action using the existing HTTP/LAN policy. HTTPS is the default, webhook URLs and JSON payloads are redacted, payloads are capped at 16 KB, and only transient failures receive capped exponential retry.

- **MQTT bridge**: added `mqtt.publish` with an in-app MQTT 3.1.1 QoS 0/1 client, platform TLS, retained messages, bounded payloads/timeouts, optional credentials, and private-LAN gating for cleartext. No new client dependency is required for F-Droid.

- **Push trigger spike**: added an authenticated `event=push` bridge for a de-googled UnifiedPush distributor, with a per-install Setup token, bounded payloads, duplicate suppression, and redacted event metadata.

- **Tasker migration**: expanded XML import/export mappings for safe notification, variable, speech/vibration, volume/brightness/timeout, torch/media, app/URL, screenshot, and structured flow actions. Unsupported actions remain explicit, and lossy notification/volume fields are listed in the migration report.

- **Profile sharing**: added an editable local community-share preview for workspace and imported bundles. The preview accepts bounded screenshot attachments with local thumbnails, renders trust and safety findings, exposes the computed bundle import plan, and hands off to the existing variable-conflict review before any Room write.

- **Preflight runner**: added side-effect-free task/profile previews with bounded synthetic event variables, flow branch decisions, expanded arguments, setup gaps, intended effects, and explicit blockers for unsupported actions. The review surface never invokes runtime actions or persists variables.

- **Intent dispatch**: generalized `intent.launch` into bounded activity, explicit broadcast, and explicit service delivery with URI/MIME support, allowlisted flags, capped primitive extras, ordered-broadcast result capture, and exported-target checks. Unsafe URI schemes, parcelable-style extras, ambiguous or non-exported external targets, and unapproved implicit broadcast/service dispatch fail closed.

- **Local projects**: added a first-class Room-backed workspace boundary with atomic Default-project migration, project-scoped runtime variables, shared filtering across automation surfaces, project-preserving bundle import/export, cross-project reference warnings, and explicit variable-safe reassignment before deletion.

- **Structured data**: `data.read` now supports bounded HTML parsing with CSS selectors and normalized element text. The jsoup dependency is pinned, checksum-verified, MIT-licensed, and performs no network I/O in the action.

- **Received Share trigger**: OpenTasker now appears in Android's Sharesheet for bounded text, URLs, MIME-typed content, and single or multiple file/content URIs. Share filters can match MIME, text, URI, and multiplicity; sanitized `share_*` variables reach the selected task, while oversized and arbitrary Parcelable extras fail closed.

- **Nested context logic**: profiles can now persist and edit recursive ALL/ANY/NOT expressions over their existing context leaves. Legacy implicit-AND profiles remain unchanged, the Inspector explains the evaluated tree, OpenTasker bundles preserve grouping, and Tasker XML export reports its unavoidable flattening.

- **Flow validation**: added a complex graph fixture covering multiple contexts, conditional branch labels, subflow markers, missing-task repair targets, continuation semantics, and screen-reader summaries. The Flow surface keeps zoom/pan and picker-backed add commands, while direct drag/drop persistence remains deferred until those editor paths have broader UI coverage.

- **Release trust**: a generated `tools/release-truth.json` manifest now owns version/code, Android SDK, dependency, capability-count, bundle/Room schema, and immutable artifact-commit claims. The local quality gate validates the manifest against shipped source, README, and F-Droid metadata with configuration-cache-safe verification.

- **Execution authoring**: profile editing can now select or clear an exit task; task editing exposes the previously stored collision policy; action editing exposes conditions and continue-after-failure; and task cards provide accessible move-up/down controls backed by a transactional history snapshot. Task collision admission now runs at the shared execution boundary for profile, manual, nested, widget/shortcut, notification, and external requests: Abort new logs a skipped run, Abort existing cancels the active coroutine tree, Wait serializes requests, and Run both permits overlap. Profile re-trigger mode remains the earlier profile-specific decision, while the referenced task's collision policy is the global last-mile rule.

- **App authoring**: application, notification, app-launch/kill, intent-launch, and Locale plugin package fields now share a searchable installed-app picker. Results show icon, label, and package from Android's scoped package visibility, Application contexts can reuse the latest package observed by the Inspector, and validated manual entry remains available without requesting `QUERY_ALL_PACKAGES`.

- **Build supply chain**: the Gradle 9.4.1 binary distribution is now pinned with Gradle's published SHA-256, and the stale 8.13-era executable wrapper JAR has been regenerated from 9.4.1. The local release gate verifies the configured version/URL, distribution pin, and checked-in JAR against independent expected hashes before it invokes Gradle, records both hashes in its report, and has a release-truth contract that fails if the property, gate pin, or binary drifts. The documented wrapper-upgrade procedure treats the URL, distribution hash, JAR, JAR hash, scripts, and test as one rollback-friendly change. The gate's Room-schema Git check also resolves a mapped drive to its canonical share and supplies that exact safe-directory identity, so it works from the repository's supported `Z:\` location without weakening Git ownership checks globally.

- **Bundle data integrity**: schema-1 OpenTasker exports now pass through an explicit, deterministic schema-1→2 migration with a checked-in golden fixture, while future schemas fail before domain decoding. Import review enumerates every same-name variable and requires an explicit keep, deterministic rename, or replace decision; safe programmatic imports preserve existing values by default, and replacing an existing secret changes its value without declassifying it. Task IDs are remapped across profile enter/exit links, scene gestures, `task.run`, and notification buttons inside one Room transaction, with rollback tests covering a conflict failure after task insertion. The first on-device secret replacement test also exposed and fixed Android Keystore rejecting the secret codec's caller-supplied GCM IV; encryption now lets the Keystore generate the nonce and stores it in the authenticated envelope.

- **External task dispatch**: Locale setting-plugin fires and scene tap/long-press bindings now construct `RUN_TASK` requests through one explicit protocol-v2 builder instead of duplicating raw intent strings. Both paths had omitted `PROTOCOL_VERSION=2`, so the hardened external receiver rejected them before enqueue. The shared builder validates task IDs and variable names, bounds values/counts, preserves a canonical source label into the run log, and is protected by a source contract that rejects new raw internal `RUN_TASK` producers; unknown or legacy external protocol versions remain fail-closed.

## v0.2.79

- **Backup & restore**: selecting a database to restore now opens a review instead of staging it immediately. Selection previously replaced the pending-restart journal outright, so a user could not inspect the candidate, could not tell it apart from a restore staged earlier, and had no way to back out. The review reports the source, size, schema version, compatibility, and profile/task/scene/variable/run-log counts, names the staged restore it would replace, and stages nothing until Stage is pressed. Setup gained a "Cancel staged restore" action that removes only the validated pending journal: backups, the live database, and the pre-restore snapshot are untouched: and the pending banner now describes what is actually queued (including a staged file that has since become unreadable).

- **Run logs**: the Run Log now shows what the engine is running *right now*: task, origin, current step, and elapsed time: with a Cancel button for each. Previously the service tracked its jobs privately and the UI showed only completed runs, so a runaway automation (a long wait, a hung request, an accidental loop) was invisible and unstoppable short of force-stopping the app. Cancel unwinds the whole run including nested "run sub-task" steps and any bounded blocking action suspended inside it, and records a terminal **Cancelled** outcome: a distinct state from Skipped, which means the run never started.

- **External intents (breaking, protocol v2)**: `RUN_TASK` is now asynchronous. It previously held the broadcast open with `goAsync()` until the whole task finished and returned its terminal success: but Android expects broadcast work to complete in roughly 10 seconds while an OpenTasker task can wait up to 30 minutes, so the reply reported an outcome that had not happened yet and the system could kill the receiver mid-run with no run-log entry. The receiver now authenticates, validates, hands the run to the foreground engine service, and replies immediately with `ACCEPTED` plus an execution ID; callers poll terminal status with the new `com.opentasker.action.QUERY_EXECUTION`. Results are retained for the 64 most recent executions and survive a process restart, and a run that was in flight when the engine died resolves to `FAILED` rather than leaving a caller polling forever. Callers must declare `com.opentasker.extra.PROTOCOL_VERSION=2`; a request without it is refused with an explicit error naming the required extra rather than being silently reinterpreted. `SET_PROFILE_ENABLED` and `QUERY_STATUS` are unchanged. See `docs/EXTERNAL_INTENTS.md`.

- **Run logs**: each step now records the variables it actually set. Traces previously showed only the values that went *into* an action, so a finished run never answered "what did this task write?". Every step captures its task, global, and array deltas (added vs. updated; a rewrite of the same value is not recorded), and the Run Log renders them in an expandable per-step inspector next to the existing expression debugger. Secret-derived values are redacted at the serialization boundary, so the raw value never reaches the stored log.

- **Engine**: the automation-mode dispatch rules (SINGLE suppression, RESTART preemption, the QUEUED cap, exit tasks never consuming cooldown) and cooldown reservation are now pure, directly tested components that the foreground service delegates to, instead of logic reachable only through a running service. Cooldown check-and-reserve is locked against the check-then-write race where two contexts matching the same profile in the same instant both start a run.

- **Actions**: the capability contract is now total and fails closed. An action that was registered but never classified used to report itself as "Ready" by default; every action now needs an explicit contract entry, and an unreviewed one is Unsupported. `app.kill` is marked Unsupported (force-stopping another app has always been impossible without privileged access, but it advertised itself as working and failed only at run time). `screen.timeout` is correctly gated on Write Settings, which the app now actually declares: `WRITE_SETTINGS` was missing from the manifest, so `Settings.System.canWrite()` could never become true and both it and Set brightness were permanently broken while claiming to be one grant away. Setup gained a "Modify system settings" row with a working deep link. Wake-on-LAN is gated on local network access on Android 17+. The README's action counts are now derived from the registry and verified (they had drifted to 58/59 against an actual 60).

- **Data integrity**: deleting or renaming a task is now reference-safe. Deletion previously checked only a profile's enter/exit task columns, so a task referenced by another task's "run sub-task" step, a notification button, or a scene tap/long-press gesture could be deleted out from under them; a rename silently broke every reference that named the task. Deleting a referenced task now lists every dependent object and requires an explicit choice: reassign them all to another task, or clear the optional ones: applied in a single transaction (a profile's enter task cannot be cleared, so only reassignment is offered when one is present). A rename pins name-based references to the task's stable id first. The same rewriter also fixes OpenTasker bundle import, which never remapped task-to-task references inside imported actions: an imported "run sub-task" step pointed at whatever local task happened to own the exporter's id.

- **Security**: stored action arguments are now redacted everywhere they are displayed, not just in runtime traces. The task list and the flow graph previously joined raw arguments into their subtitle, so an HTTP `authorization` header, request `body`, or query string typed into an action appeared on screen, in screenshots, and in accessibility semantics. A single shared formatter (`ActionArgumentSensitivity`) now decides what is masked, driven by an explicit `sensitive` flag on the action's registered field metadata and backed by an argument-name heuristic so unregistered actions and forward-compatible keys fail closed. `var.set` masks its value when the variable it writes is itself named like a secret, and a source guard prevents any new surface from rendering raw arguments.

- **Diagnostics**: the engine-health panel now shows a "Scheduled jobs blocked by" row that reports, in plain language, why the app's deferrable jobs (watchdog, log pruning) are still pending: app standby bucket, no connectivity, not charging, out of run quota, device thermal/power state, and so on: using Android 14+ `JobScheduler.getPendingJobReason`. It answers the common "why hasn't my scheduled automation fired" question; below Android 14 or when nothing is blocking, it reads "Nothing blocking".
- **Security**: secret/taint flags on global and array variables are now monotonic: once a variable is marked sensitive it stays sensitive for the life of the run. A concurrent plain write from another parallel profile run can no longer race the flag off and leak the value into a later log or trace (the flag is set before the value is published and never cleared by a subsequent write).
- **Run logs**: an action that fails while consuming a secret-derived argument now records its real error class and location (e.g. `threw: request failed for <redacted>`) instead of the opaque blanket "details redacted" message. The raw secret value is scrubbed from the message and the throwable cause is dropped, so failures stay debuggable without leaking the secret.

## v0.2.78

- **Diagnostics**: the engine-health panel now flags a throttled app-standby bucket (`RARE`/`RESTRICTED`) with an explicit warning pointing to the battery-optimization exemption, and the overall health indicator treats any throttled bucket (not just `RESTRICTED`) as unhealthy.
- **Contexts**: Location conditions gain a "Match when outside" toggle for geofence-exit automations ("fire when NOT at a place"), with dwell measured outside the radius. Existing inside-only geofences are unchanged, and the FOSS evaluator, matcher, and dwell tracker all honor the mode through the shared evaluator.
- **UI**: selected filter chips and rows now use an opaque composited fill (`selectedContainerColor`) instead of a translucent alpha-on-alpha wash, so the selection reads as a distinct solid fill in both the AMOLED and light themes rather than being distinguishable only by its border.
- **Security**: the exported external-trigger receivers (`AutomationTargetReceiver`, `LocaleSettingFireReceiver`) now declare `android:intentMatchingFlags="enforceIntentFilter"` so incoming intents must match their declared filters (blocking mismatched-action redirection), and debug builds enable StrictMode `detectUnsafeIntentLaunch()`.
- **Networking**: the ACCESS_LOCAL_NETWORK (Android 17+) policy is now a pure, unit-tested function covering the below-37 no-op, granted, and denied/revoked paths; LAN actions continue to fail closed with a clear "grant it in Setup" message.
- **Tasker import**: the `Wait` action now reads Tasker's five fixed time fields (ms/seconds/minutes/hours/days) by their argument index instead of a dense list, fixing imported waits that were mis-scaled by up to 1000× when zero fields were omitted from the export.
- **Diagnostics**: detects Android 16 Advanced Protection Mode (API 36+, read defensively via reflection so it fails closed and is a no-op below 36) and surfaces its state plus a graceful-degradation note in the engine-health panel: OpenTasker's triggers keep working since it uses no Accessibility service, but privileged extensions may be limited while it is on.

## v0.2.77

### Roadmap drain (correctness, security, and consolidation)

- **Engine**: sub-task (`task.run`) input variables are now scoped to the child invocation, so lowercase inputs no longer leak into the parent task's later actions. QUEUED-mode retriggers arriving while a task is running now queue instead of being dropped as "cooldown active": the cooldown is reserved only when a fresh run actually starts. Notification-button taps run inside the foreground `AutomationService` instead of the receiver's ~10 s `goAsync` window, so long tasks (e.g. `flow.wait` up to 30 min) complete and log reliably.
- **Actions**: the `download` action now delegates to the shared `HttpRequestAction` transport (same-origin redirects, atomic fsync'd writes, the 50 MB cap, cleartext-private DNS, and the LAN-permission gate) instead of a parallel OkHttp path; downloads land in the shared `user_files` sandbox so `file.*` actions can read them. `FileActions` reads/writes with no-follow (`O_NOFOLLOW`) semantics and rejects symlinked path components, closing a TOCTOU sandbox-escape window. Notification-button PendingIntents use a collision-free request-code allocator so a newer notification can no longer overwrite an older button's intent.
- **Import/validation**: `InputValidation` field limits (name length, task priority, non-empty actions, blank action type, profile name/cooldown) are now enforced at the OpenTasker bundle import boundary and on profile save, instead of being an unenforced module.
- **Scenes**: a scene slider bound to a task now fires it on release with the value exposed as a variable; task-firing overlay controls (button and bound slider) drop obscured touches (`filterTouchesWhenObscured`) as a tapjacking guard. The multi-selection is reconciled when the element list changes and is preserved while dragging a selected member. The resize handle and Run Log expression-debugger expand control now meet the 48 dp touch-target minimum.
- **UI/polish**: flow-canvas connectors are drawn in density-correct dp (were raw px, misaligned at density ≠ 1×) and the sub-task badge keys off a structural node flag rather than an English literal. Removed the dead `PremiumComponents` module and the deprecated edge-to-edge status/navigation bar color setters.

## v0.2.76

### Deep audit fixes (2026-07-17)

- **Engine**: exit tasks now run on their own job slot and never consume the profile cooldown, so a cooldown, SINGLE-mode in-flight enter task, or RESTART can no longer silently drop the exit task. Closed a QUEUED lost-task race where a retrigger could be enqueued into a queue whose consumer had already decided to exit.
- **Engine**: plugin conditions no longer flap: the shared Locale plugin poll source multiplexes every subscription, so the matcher now holds state for results addressed to a different plugin/bundle instead of driving every plugin context true→false→true each 30 s cycle. The internal `sun_tick` minute pulse can no longer satisfy a generic/blank-filter EVENT context (previously firing imported event profiles every minute); blank-event/blank-filter specs fail closed.
- **Contexts**: all-day calendar events match on the local day instead of the raw midnight-UTC bounds (they were shifted by the zone offset). The Context Inspector is now read-only and no longer resets the engine's persisted location dwell timers, and its match explanations honor OR groups like the engine. Serialized the two-thread state-source merge and synchronized camera/mic AppOps start/stop against a watcher leak.
- **Data**: added indexes on `run_logs(timestamp)` and `edit_history(entityType, entityId)` (schema v8, migrated + instrumented).
- **Actions**: `download` runs on OkHttp with a policy-DNS hook so the cleartext private-LAN rule and the API 37 `ACCESS_LOCAL_NETWORK` gate are enforced against the addresses actually connected to (closing a DNS-rebinding TOCTOU), fails on non-2xx instead of saving a redirect stub over a good file, and fsyncs before the atomic rename. `tile.set` fails honestly and is capability-gated Unsupported instead of reporting a no-op Success. `screen.timeout` rejects the "0 = never" value that actually turns the screen off immediately. `flow.wait` at its 30-minute maximum no longer always times out; `sound.play`/`tts.speak` get a 10-minute budget and TTS queue failures fail fast. `datetime.*` zone typos fail closed; `data.read` CSV supports RFC 4180 quoted fields. Added missing editor fields for ping, download, sound.play, and media.mute.
- **UI**: fixed a Diagnostics crash from duplicate log keys (now keyed on a monotonic sequence) and stopped its polling while backgrounded. The one-time NFC write is disarmed on dialog close, expires after 60 s, and runs its tag I/O off the main thread. `deleteVariable` reports real success/failure instead of an optimistic toast; undo no longer reports false success; `updateTask`/`updateProfile` are transactional. Editing an unknown action type shows a message instead of a dead tap. The context editor blocks saving garbled TIME windows and out-of-range coordinates. New profiles default to disabled, the enter-task selection no longer resets mid-edit, and backup state loads off the main thread.
- **Theming**: scene warning text follows the applied theme's luminance (was near-invisible in Light-app-on-dark-system), the Locale plugin edit activity honors the persisted theme, run-log detail lines no longer render twice, and the scene overlay is clamped on screen so it can't be dragged fully offscreen. Removed dead duplicate helpers.

### Prior unreleased work

- **Diagnostics**: added a secondary Diagnostics destination with live engine heartbeat, active foreground-service types, app-standby bucket, exact-alarm delivery, last matcher failure, WorkManager watchdog stop reason, bounded process logs, and redacted crash previews. Shared diagnostic reports now include that health snapshot, up to 100 ring-buffer entries, and bounded crash excerpts; Authorization/Bearer credentials are redacted in addition to existing secret patterns.
- **Background reliability**: time/day contexts now consume AlarmManager wake pulses in addition to an aligned in-process minute clock, so a Doze wake reaches the matcher instead of only producing a log line. Inexact fallback alarms use `setAndAllowWhileIdle`; a persisted service heartbeat and 15-minute WorkManager watchdog re-arm dropped ticks, and foreground-service timeout leaves a recovery alarm armed before shutdown.
- **Variable reliability**: global write-back now compares each run against its hydrated snapshot under a process-wide mutation coordinator and publishes accepted rows as one Room batch. Concurrent runs merge disjoint globals without loss; stale same-global writers preserve the first committed value and add an explicit conflict note to the run log instead of silently clobbering it.
- **Variables**: unified variable-name normalization across runtime writes, `var.persist`, the Variable vault, durable storage, and Tasker XML imports. Any uppercase letter now consistently identifies a global; all-lowercase names stay task-local, explicit lowercase persistence targets are promoted instead of silently disappearing, invalid targets fail visibly, and root-local event values cannot leak into durable snapshots.
- **Networking**: replaced the split HTTP GET/POST editor actions with one cancellable `http.request` transport on OkHttp 5.4.0. It supports GET/HEAD/POST/PUT/PATCH/DELETE/OPTIONS, bounded structured query/header/auth input, inline or file bodies, status/header/body variables, atomic response files, per-stage timeouts, and explicit no-redirect/same-origin redirect policy. TLS bypasses and cross-origin redirects fail closed, cleartext remains private-LAN-only, header traces redact credentials, and stored GET/POST IDs remain hidden compatibility aliases.
- **Release/docs**: expanded the release-truth contract from README-only checks to source-derived capability and version checks across architecture, dependency, F-Droid, scenes, visual flow, Shizuku, Termux, and Locale documentation. The gate excludes explicitly historical dependency logs and includes a deterministic stale-document failure example.
- **Backup reliability**: encrypted `.otbackup` exports now use chunked format v2, authenticating each bounded 64 KiB frame plus an explicit terminal frame before validated restore staging is atomically published; v1 backups remain restorable. Wrong passphrases, corruption, truncation, cancellation, write failures, and interrupted staging clean temporary plaintext without replacing an existing pending restore, while startup restore keeps its pending journal through same-directory atomic database replacement.
- **Battery/reliability**: production Wi-Fi, connectivity, app-usage, shake, camera/mic, package, and Bluetooth context monitors now start only while an enabled profile depends on them and stop after the final dependent profile is disabled or deleted. Profile edits reconcile reference counts without duplicate registrations, and an explicit subscription barrier prevents a newly activated pulse source from firing before its matcher is listening. Camera/mic AppOps pulses are now also wired into event-context matching.
- **Security**: every built-in action now has an explicit data-access, external-transmission, device-control, destructive, or local-only classification. Bundle schema v2 carries a computed task/profile power manifest and flags potential data-to-external chains; unknown actions fail before import or task side effects. Imported profiles persist a review-required state, stay outside the engine registry, and require an in-app acknowledgement before their first enable.
- **Security**: global variables can now be explicitly stored as Android Keystore-backed AES-256-GCM secrets. Secret provenance survives legacy/template expansion and derived writes, redacts nonsensitive argument fields, action logs, traces, and failures, and keeps values out of ordinary OpenTasker/Tasker exports. Cross-device or key-loss restores fail closed with a deliberate re-entry flow.
- **Maintainability**: split the scene library's list/cards, interactive canvas, element dialogs, and overlay controls into focused Compose modules; the public screen is now a 160-line coordinator protected by source-boundary, localization, accessibility, and scene behavior tests.
- **Security**: Termux scripts now require a Setup-managed SHA-256 allowlist and a matching Termux-side preflight hash before every run. The app declares and requests `RUN_COMMAND`, requires Termux 0.109+ result support, receives results through a non-exported one-shot callback, bounds arguments/stdin/timeouts/stdout/stderr/pending commands/rate-limit state, redacts captured content from logs, and can map stdout, stderr, exit code, and original lengths to variables.
- **Data safety**: completed the fail-closed stored-payload boundary across manual, widget, notification, external-intent, export, inspector, and widget-configuration paths; corrupt rows stay untouched for database recovery and skipped runs now record the reason. Edit-history pruning is also entity-scoped, so trimming one task can no longer delete another task, profile, or scene history.
- **Scenes**: overlay rendering now uses the editor's authored scene projection and exact element bounds, supports overlapping elements and bounded local-image decoding from persistable content URIs, and reads both legacy slider `progress` and current `value` deterministically.
- **Reliability**: notification action buttons now bind to immutable task IDs through a task picker. Renames preserve bindings, deleted tasks fail visibly, and legacy name bindings migrate only when the name is unique; duplicates never select an arbitrary task.
- **Scenes**: multi-selected elements now move as one rigid, edge-clamped mutation with one transactional undo snapshot; resize gestures use independent horizontal and vertical canvas scales and stay within scene bounds.
- **Onboarding**: first-run template onboarding now completes only after an explicit skip or successful install and resumes after dismissal or recreation. Runtime permission results update Setup immediately, repeated denial routes to app settings, and grant/revocation resets recovery state.
- **Accessibility**: scene overlays now use a 48dp close target, expose screen-reader move actions, and retain a proper touch click path. Profile switches, task/profile actions, nested action/context controls, run-log filters, and expression details now expose specific names and authored state without duplicate decorative icon announcements.
- **Platform**: Android 17 audio hardening is now eligibility-aware instead of disabling every audio action. Visible task launches and while-in-use-eligible automation services attempt sound, TTS, volume, ringer, mute, and media-key operations; boot/background runs fail before side effects with recovery guidance, while exact-alarm access is honored only for alarm-stream changes.
- **Security**: Shizuku permission can no longer promote elevated capabilities or route commands through an ordinary app-UID `ProcessBuilder`. The kill switch is persisted and defaults on, Setup distinguishes stopped/permission/unavailable/disabled states, and every elevated action remains `Unsupported` until a privileged user-service transport exists.
- **Security**: OpenTasker JSON and Tasker XML imports now enforce shared entity/action/context/scene/string budgets, plus streaming token/node and nesting preflights before model or DOM allocation. Named violations fail before the Room transaction.
- **Release**: added one local quality/release gate covering blocking lint, the JVM test floor, Room schema drift, Android-test compilation, resolved dependency/repository/checksum policy, a CycloneDX SBOM with OSV advisory results, configuration-cache reuse, and Play/F-Droid release assemblies. Enabling permission lint also caught and fixed the missing manifest permission for the shipped vibrate action.
- **i18n**: moved action/context catalogs, setup and backup copy, capability diagnostics, widget plurals, and scene-overlay labels to Android resources; seeded Spanish setup translations, enabled `en-XA`/`ar-XB` debug pseudolocales, and expanded localization guards.
- **Reliability**: Wake-on-LAN now rejects MAC addresses with mixed `:`/`-` separators (e.g. `AA:BB-CC:...`); a consistent separator is required.
- **Actions**: added a text/regex action pack: **Match Text** (`text.match`, captures become an array), **Replace Text** (`text.replace`, `$1` group refs), **Split Text** (`text.split`, literal or regex), **Join Text** (`text.join`), and **Substring** (`text.substring`). Regex uses the linear-time RE2 engine with bounded pattern/input sizes, so patterns can't hang the runner.
- **Actions**: added date-time actions: **Format Date/Time** (`datetime.format`), **Parse Date/Time** (`datetime.parse`), and **Add to Date/Time** (`datetime.add`). Convert between epoch milliseconds and formatted strings with optional time zones, and do calendar-aware date arithmetic (seconds through years), all deterministic and offline. Fixed units are exact zone-independent deltas; months/years honor calendar length.
- **Actions**: added a **Read Data** action (`data.read`) that parses JSON, CSV, or XML into variables entirely on-device: ideal for turning HTTP responses and file contents into usable automation data. Supports JSON path selectors (`items[0].name`), CSV column/cell selection, and XML element paths (`root/item/name`), sets an array plus a `%var_count`, is size-bounded, hardened against XML external entities, and fails closed on malformed input or an unresolved selector.
- **Security**: the external-automation broadcast target now bounds the number of supplied variable extras (64) in addition to the existing per-value length cap, name validation, and signature permission.
- **Interoperability**: OpenTasker bundle import now tolerates hand-edited JSON: `//` comments, trailing commas, and case-insensitive enum values decode cleanly, while unknown keys and oversized bundles are still rejected. Export output is unchanged.
- **Reliability**: task execution now runs off the main thread. Every run path (manual, profile trigger, widget/shortcut, notification action, Locale/external) executes actions on `Dispatchers.IO`, and the automation service's matching/dispatch runs on `Dispatchers.Default`. Previously blocking actions (HTTP GET/POST, download, ping, Wake-on-LAN, file I/O) launched from the main thread threw `NetworkOnMainThreadException` and failed silently. Debug builds now install StrictMode to flag any accidental main-thread disk/network I/O.
- **Privacy**: SMS recipient numbers are now masked in run logs (e.g. `***6789`) instead of stored in full: run-log redaction does not otherwise scrub phone numbers.
- **Reliability**: hardened smaller action/import edge cases: the Termux script action no longer passes a spurious empty argument when `arguments` is blank or double-spaced; `file.list` reports a clean "invalid file name pattern" failure instead of leaking a raw Java exception for a bad glob; and OpenTasker bundle import no longer counts updated variables as newly inserted.
- **Reliability**: hardened the variable engine. A `var.set` targeting a huge array index (e.g. `%X[2000000000]`, reachable from an imported/shared profile) no longer tries to grow a multi-billion-entry list: out-of-range writes fail closed. Array storage now evicts the genuinely least-recently-used array at its cap instead of an arbitrary one, and is synchronized for concurrent tasks. Ternary conditions whose test contains parentheses (e.g. `(%A(+1) > 5) ? a : b`) are now parsed correctly instead of silently falling through.
- **Reliability**: event/notification text matching with `regex=true` now uses the linear-time RE2 engine (as variable regex already does) instead of the JDK backtracking engine, so a pathological user pattern can no longer hang the matcher on an incoming event.
- **Correctness**: battery-level triggers now normalize `EXTRA_LEVEL` against `EXTRA_SCALE`. On devices that report a non-100 scale (some report 255), `battery_level` thresholds previously never/always matched.
- **Security**: the exported Locale fire receiver now requires a revocable execution grant. Any app could previously broadcast a chosen task id to the receiver and have OpenTasker run it. Configuring the plugin now issues a high-entropy token bound to the selected task; the receiver dispatches only when the incoming bundle carries a token that is still stored and bound to that exact task, so forged, missing, mutated, revoked, and deleted-task grants are rejected without dispatch. Grants are revoked automatically when their task is deleted.
- **Networking**: cleartext HTTP to LAN/private hosts now actually works. The network-security config previously listed private ranges as `<domain>` hostnames (Android has no CIDR support there), which silently blocked every literal LAN IP. Cleartext is now gated solely by the runtime policy: HTTPS stays the default, `allow_http` is an explicit opt-in, and any host not resolving to a loopback/link-local/site-local/IPv6-ULA address is rejected before a connection opens. IPv6 Unique Local Addresses (`fc00::/7`), previously misclassified as public, are now recognized.
- **Variables**: global (`%UPPERCASE`) variables and `var.persist` values are now genuinely durable. Every execution path (manual run, profile trigger, widget/shortcut, notification action, Locale/external intent) hydrates persisted globals before running and commits any globals changed during the run to the database before reporting success, so they survive across runs and process restarts. Local (lowercase) variables still never escape their invocation, and the Variables vault now reflects real global state.
- **Reliability**: the running automation engine now reconciles itself from the profiles table. Creating, editing, enabling, disabling, or deleting a profile rebuilds matchers and plugin subscriptions live, without needing a service restart, while leaving any in-flight task run untouched. Purely cosmetic edits (name, group) no longer thrash the engine.
- **Data safety**: corrupt stored automation payloads now fail closed. Task, profile, and scene rows whose JSON no longer decodes are surfaced with the exact record and field, cannot be executed (profiles skip them with a run-log note and `task.run` refuses corrupt sub-tasks), and cannot be overwritten by the normal editors (the raw bytes are preserved for undo/backup recovery). Scene edits now also snapshot to edit history, and stored payloads decode through a shared codec that tolerates unknown additive fields.
- **Release**: refreshed the draft F-Droid metadata pin and local fdroidserver lint/build evidence for `0.2.75`/`77`.
- **Release**: added the Kotlin/Gradle dependency verification hashes needed by clean fdroidserver source checkouts.
- **Reliability**: hardened database backup creation so local backups wait for a complete WAL checkpoint, publish only schema-validated copies, clean up failed temporary files, and keep backup UI state from getting stuck after failures.
- **Testing**: added Compose instrumentation coverage for setup onboarding, task/profile editor validation, action/context required-field validation, scene creation, and incompatible import review states.
- **Accessibility**: added repeatable source gates for setup, profile/task editors, action/context editors, scenes, destructive dialogs, and run-log states; converted remaining app-shell and setup semantic labels to string resources.
- **i18n**: completed the core active automation, editor, flow, scene, and premium-state string-resource extraction pass; added a JVM source guard for hardcoded Compose strings and valid Weblate locale targets.
- **Reliability**: routed remaining direct platform log calls through `AppLogger` and added a source-level regression guard so `android.util.Log` stays isolated to the logging wrapper.
- **Maintainability**: finished the active-automation shell split into owned view-model, list, editor, action, and context modules while keeping `ActiveAutomationUi.kt` under 1,500 lines.
- **Release**: synced draft F-Droid metadata and the PowerShell release verifier with the current `0.2.75`/`77` Gradle release contract.
- **Docs**: added a release-truth contract test so README release values and shipped-feature claims stay aligned with Gradle metadata and current backend docs.

## v0.2.75 - 2026-06-19

Scene editor finishing pass and visual flow editor authoring.

- **Feature**: scene overlay launch via `SYSTEM_ALERT_WINDOW`: each scene card shows a "Show" button (when overlay permission is granted) that displays the scene as a draggable floating window with dark-themed element views and tap-to-run-task bindings.
- **Feature**: scene element multi-select: drag-starting an element selects it (highlighted border); when multiple elements are selected, dragging one applies the delta to all selected elements as a group.
- **Feature**: alignment guides on scene canvas: elements snap to canvas edges, center lines, and other element edges/centers during drag. Dashed guide lines render during the gesture with a 6dp threshold.
- **Feature**: flow canvas pinch-zoom (0.5x-2.5x) and pan gestures for the lane overview.
- **Feature**: flow edge routing: vertical connectors between lanes and horizontal connectors between nodes drawn as Canvas lines with endpoint dots.
- **Feature**: branch and subflow markers: action nodes with sub-task references show a Subflow pill; conditional actions show a Branch pill with the if-condition text.

## v0.2.74 - 2026-06-19

i18n bootstrap, engine v3, dependency upgrade, encrypted backup, Shizuku/Termux backends, and Locale interop.

- **i18n**: expanded `strings.xml` from 49 to 170+ string resources covering all major UI surfaces. Converted ImportReviewDialogs, VariablesScreen, RunLogScreenContent, ContextInspectorScreen, and SceneLibraryScreen to use `stringResource()`. Created locale skeleton directories for 13 languages. Added contributor translation workflow docs to README.
- **Feature**: `var.set` now supports dotted and bracketed path syntax (`config.theme`, `items[0]`, `Data.user.profile.name`) for nested JSON writes via `VariableStore.setAtPath()`. Array indices auto-grow with empty-string padding.
- **Feature**: Run-Log expression traces now render in an expandable debugger surface with per-expression arg name, scope source, monospace expression→value mapping, and warning highlights.
- **Feature**: encrypted database backup/restore using AES-256-GCM with PBKDF2-derived keys (600k iterations). `.otbackup` file format with 4-byte magic, salt, IV, and authenticated ciphertext.
- **Feature**: Shizuku elevated backend with real API 13.1.5 integration. Checks Shizuku service state (ping, permission), exposes Ready/PermissionNeeded/Disabled/ManagerInstalled states. ShizukuShellRunner validates commands against a strict allowlist. Kill-switch toggle. ActionCapabilities dynamically promotes elevated actions when Shizuku is active.
- **Feature**: Termux RUN_COMMAND dispatch with executable path, arguments, working directory, and background execution. SHA-256 script hash pinning for allowlist verification. 1-second per-script frequency cap. Output-to-variable mapping via capture prefix.
- **Feature**: Tasker XML export for the mappable action subset (notify, wait, log, var.set). Exports Time, Day, Application, State, and Event contexts. Reports skipped actions and unmappable contexts.
- **Feature**: Locale plugin target bridge: OpenTasker now appears as a Locale-compatible setting plugin for Tasker/MacroDroid. Edit activity shows task picker; fire receiver dispatches tasks through the existing automation pipeline.
- **Feature**: scene element resize handles on the canvas preview. Drag the bottom-right handle to resize elements within scene bounds.
- **Dependencies**: upgraded Compose BOM from 2026.04.01 to 2026.05.00 with updated dependency verification checksums.
- **Style**: adopted DesignSystem spacing and radius tokens across 5 major UI screen files.

## v0.2.73 - 2026-06-17

Hardening, test coverage, and expression engine improvements.

- **Security**: applied Android 17+ `ACCESS_LOCAL_NETWORK` permission guard to Ping and Wake-on-LAN actions; all LAN socket actions now enforce the same gate.
- **Security**: extended the Android 17+ local-network guard to HTTPS requests targeting private, loopback, or link-local hosts so URL-backed LAN actions cannot bypass Setup permission state.
- **Reliability**: added Room schema v5 drift gate: CI now fails if any schema version file is missing; added migration tests for 2→3, 4→5, and full 1→5 path.
- **Supply chain**: enabled Gradle dependency verification with SHA-256 checksums for all resolved artifacts.
- **Feature**: added `var.persist` action to promote local variables to global scope across task invocations.
- **Testing**: broadened action guard coverage for file, settings, app, and notification-channel operations; expanded retention policy boundary tests.
- **i18n**: centralized common UI strings (navigation, dialogs, setup, empty states) in `strings.xml`.
- **Docs**: updated Setup permission copy to list all guarded network actions.
- **Safety**: `AutomationService.onDestroy()` now snapshots job collections before cancelling to prevent `ConcurrentModificationException` during service teardown.
- **Safety**: `reloadProfiles()` cleans up stale queued tasks for deleted/disabled profiles, preventing memory accumulation.
- **Safety**: `ArrayStore` now caps at 500 entries to prevent unbounded growth from `%var(split:...)` operations within a single task run.
- **Safety**: `OpenTaskerBundleCodec.decode()` now rejects JSON payloads larger than 16 MB to prevent OOM from malicious imports.
- **Safety**: capped `file.write` and `file.append` payloads at the existing 1 MB file-action boundary and fail before creating or expanding files beyond that limit.
- **Safety**: bounded imported database restore staging to 100 MB and remove temporary files if the import stream fails or exceeds the limit.
- **Safety**: `WiFiNetworkMonitor` and `ConnectivityMonitor` handle null `ConnectivityManager` gracefully instead of crashing on devices where the system service is unavailable.
- **Reliability**: serialized persisted Location dwell-state read/modify/write operations so concurrent matchers cannot lose inside-since updates.
- **Reliability**: cleaned up LocationManager listener registration on provider-set changes and partial registration failures to avoid duplicate callback chains.
- **Safety**: surfaced corrupted profile/task JSON as visible UI warnings while keeping safe fallback domain objects.
- **Safety**: hardened database backup validation with current schema-shape checks, required-table row-count reads, and a consumed WAL checkpoint before copy.
- **Maintainability**: split run-log rendering and import-review dialogs out of `ActiveAutomationUi.kt`, with source tests guarding the screen ownership boundary.
- **Reliability**: made `flow.wait`, `vibrate`, and `screen.timeout` fail clearly on missing, malformed, or out-of-range durations instead of silently defaulting or clamping.
- **Fix**: implemented deterministic `file.list` filename glob filtering and added the missing action editor field for `pattern`.
- **Security**: tightened import validation by rejecting oversized/DOCTYPE Tasker XML before parsing and blocking OpenTasker bundles with duplicate task IDs or variable names.
- **UX**: bounded long Tasker/OpenTasker import review dialogs so warnings and mapped-action lists stay scrollable on compact screens.
- **Reliability**: replaced API 33-only service receiver registration with AndroidX compatibility calls and gated camera/mic active watchers to Android 11+ APIs.
- **Safety**: made downloads write to a temporary file and replace the destination only after a complete bounded copy succeeds.
- **Performance**: reduced scene-canvas drag recomposition overhead by using primitive float state and lambda offsets.
- **UX**: polished first-run onboarding, labeled create actions, the widget task picker, and the home-screen widget treatment so setup and secondary flows feel more guided and intentional.
- **Reliability**: made widget and launcher-shortcut task runs close with clear feedback even when task execution throws, avoiding stranded translucent runner activities.
- **Reliability**: guaranteed external automation broadcast pending-results finish even if ordered-result publication fails.
- **Cleanup**: removed duplicate `ArrayStore.joinWith` method (identical to `join`).

## v0.2.72 - 2026-06-16

Setup and secondary-state polish pass.

- **Setup**: replaced the theme button grid with an accessible radio-style selector that exposes selected/not-selected state and avoids no-op selected buttons.
- **Backup**: tightened backup/restore copy, added a calm state banner, and changed secondary backup actions into compact side-by-side controls so the card scans better on compact screens.
- **Permissions**: normalized setup action button shape for a more consistent control language.
- **Flow/Scenes/Inspector**: upgraded sparse empty states into framed, explanatory surfaces with status cues and clearer next-step copy.
- **Docs**: bumped app metadata and README/roadmap state for v0.2.72.

## v0.2.71 - 2026-06-16

Premium UX polish pass.

- **Navigation**: promoted Run Log into the primary bottom navigation, clarified destination labels, and tightened selected-state geometry for more stable compact-screen behavior.
- **Theme**: synced the navigation bar color with the AMOLED/light/high-contrast theme selection so edge-to-edge chrome feels intentional.
- **Profiles/Tasks**: made status and secondary action rows horizontally safe on compact screens, added filtered no-match notices, and kept long mode/group/collision labels from crowding primary content.
- **Run Log**: moved outcome and duration chips below the run header so diagnostics keep readable width with long task names and trace detail.
- **Variables**: upgraded the Variables tab into a summary-driven variable vault with metrics, clear search, polished empty states, consistent cards, and explicit sensitive-value masking labels.
- **Design system**: added reusable screen spacing and opacity tokens to reduce hardcoded visual decisions across Compose surfaces.

## v0.2.70 - 2026-06-16

Profile organization and diagnostic sharing.

- **Profile groups**: profiles carry an optional group, set from a new editor field, shown as a pill badge on profile cards and offered as filter chips in the profile list once any group exists. Room migration v4→v5 adds the column, and the field carries through JSON export/import.
- **Diagnostic share**: the Run Log summary card can share a redacted diagnostic bundle: app version, device info, recent run logs, and permission state: through the Android share sheet, with regression coverage for the redaction.
- **Action guard coverage**: missing-argument validation tests for `ReadFile`, `WriteFile`, `PlaySound`, `LaunchApp`, `SetVariable`, and `SayAction`'s text length cap, plus the expanded `OpenUrl` scheme allowlist (`tel`, `mailto`, `geo`, `data`, `blob`).
- **Supply chain**: pinned the then-current GitHub Actions from mutable `v4` tags to full commit SHAs.

## v0.2.69 - 2026-06-16

Locale condition plugin context UX (N7).

- **Feature**: added `ContextType.PLUGIN` for Locale/Tasker condition plugins as first-class profile context predicates; users can pick a condition plugin, configure it, and have profiles activate/deactivate based on the plugin's satisfied/unsatisfied state.
- **Feature**: added `LocalePluginConditionContextSource` that polls subscribed condition plugins every 30 seconds with last-known-state caching through the existing `LocalePluginConditionStateCache`.
- **Feature**: added Plugin context row in the context picker with package, config JSON, description, and timeout fields.
- **Feature**: Context Inspector shows plugin condition source health, config summary (package + blurb), and match state.
- **Engine**: `AutomationService` registers plugin subscriptions when enabled profiles are loaded and clears them on destroy.
- **Tests**: added evaluator tests for plugin matching, package/bundle validation, inversion, and inspector config summary.

## v0.2.68 - 2026-06-16

Safety and correctness patch.

- **Safety**: replaced legacy Java/Kotlin regex worker threads in variable `%regex` and `%replace` operators with RE2/J linear-time matching, eliminating leaked `regex-eval` threads from pathological user-authored patterns.
- **Safety**: unsupported advanced regex syntax now fails closed for variable regex operations instead of attempting cancellable backtracking.
- **Correctness**: fixed `torch.set` toggle semantics by reading the current torch state through `CameraManager.TorchCallback`; if Android cannot report the state, toggle now fails honestly and tells users to use explicit `on`/`off`.
- **Correctness**: torch actions now select a camera that actually reports flash availability instead of using the first camera id.

## v0.2.67 - 2026-06-15

Deep engineering, security, and UX audit pass.

- **Thread safety**: made `ArrayStore` concurrent-safe with `ConcurrentHashMap` to prevent `ConcurrentModificationException` when tasks run in parallel automation mode.
- **Thread safety**: upgraded `VariableStore` local scope maps to `ConcurrentHashMap` to prevent race conditions between concurrent coroutines reading/writing the same scope.
- **Thread safety**: marked `WiFiNetworkMonitor.lastState` and `ConnectivityMonitor.lastState` as `@Volatile` since `NetworkCallback` methods fire on binder threads.
- **Thread safety**: marked `CameraMicContextEvents` camera/mic callback fields as `@Volatile` to prevent races between `start()` and `stop()` on different threads.
- **Resource leak**: added `CameraMicContextEvents.stop()` call in `AutomationService.onDestroy()` to unregister `AppOpsManager` watchers that were previously leaked.
- **Data corruption**: fixed HTTP response `readBounded` to collect bytes into `ByteArrayOutputStream` before UTF-8 decode, preventing multi-byte character corruption when a character straddles an 8KB read boundary.
- **Correctness**: fixed `BrightnessAction` auto mode to set `SCREEN_BRIGHTNESS_MODE` to automatic instead of writing `-1` to the brightness value. Manual brightness values now explicitly set the mode to manual first.
- **Correctness**: fixed `ScreenTimeoutAction` to clamp the timeout value to 0 to 30 minutes, preventing `Long`-to-`Int` truncation on large values.
- **Correctness**: fixed `SunEventCalculator` DST offset to use the offset at the approximate event time instead of noon, preventing sunrise/sunset times from being off by 1 hour on DST transition days.
- **Correctness**: seeded `battery_level` and `charging` in `StateContextSourceImpl.seedInitialState()` from the sticky `ACTION_BATTERY_CHANGED` broadcast so battery-based profile conditions evaluate correctly immediately after service start.
- **Crash fix**: `FlowGraphCard` now uses `firstOrNull()` instead of `first()` for the profile node, preventing `NoSuchElementException` if graph data is corrupted.
- **Crash fix**: TTS `SayAction` now guards continuation resume with `AtomicBoolean` to prevent double-resume if TTS callbacks race.
- **Safety**: capped vibration duration to 10 seconds to prevent extended uncontrolled vibration.
- **Safety**: capped queued task depth per profile to 50 in QUEUED automation mode, preventing unbounded memory growth from rapid triggers.
- **Safety**: changed database backup WAL checkpoint from `FULL` to `TRUNCATE` for safer backup consistency.
- **Safety**: fixed notification button `PendingIntent` request codes to use hash-based IDs instead of `notifId * 10 + i`, preventing integer overflow for large notification IDs.
- **Memory**: `ShakeDetector` now uses `applicationContext` to prevent potential `Service`/`Activity` context leak.
- **UX**: fixed `disabledAlpha` modifier to use `Modifier.alpha()` instead of a semi-transparent black overlay, which broke disabled element appearance in light theme.
- **UX**: warning color in scene validation now uses warm amber/peach instead of green (tertiary), which was confusing since green implies success.
- **UX**: added `contentDescription` to navigation bar icons for screen reader accessibility.
- **Design system**: added `Radii.xxl` (18dp) token and `SemanticColor.warningDark`/`warningLight` to the design system. Replaced ~11 hardcoded `RoundedCornerShape(18.dp)` instances across all screens with the design token.

## v0.2.66 - 2026-06-15

Shell navigation and scene control polish.

- Reworked the app shell's navigation surface and the scene library's control layout and state handling.

## v0.2.65 - 2026-06-15

Scene editor state and destructive affordances.

- Clarified scene editor state transitions and destructive-action affordances; aligned the Variables surface with the same treatment.

## v0.2.64 - 2026-06-15

Form state and accessibility polish.

- Improved form state handling and accessibility across the active automation shell and the scene library, and fixed compact-width navigation layout.

## v0.2.63 - 2026-06-15

Release-polish pass.

- Added IME padding to the main Compose scaffold so focused forms have safer keyboard behavior.
- Reduced bottom-navigation crowding by showing labels only for the selected destination.
- Added confirmation before deleting global variables and preserved variable search/edit/delete dialog state across recreation.
- Made widget task rows explicit button-role targets with minimum row height and long-text ellipsis.
- Added button roles to clickable flow-graph nodes.
- Preserved task/profile/action editor drafts with saveable state across configuration changes.

## v0.2.62 - 2026-06-15

Action editor compatibility and UI polish.

- Aligned dynamic action form metadata with runtime argument keys for brightness, screenshots, file read/write/append/list, and HTTP GET/POST actions.
- Kept legacy saved-action keys working (`level`, `filename`, `variable`, `content`, and `body`) so older automations still prefill and execute correctly after the metadata correction.
- Replaced full-round badge geometry with bounded 8dp corners and removed the unused full-round radius token.
- Changed action/template/context picker lists from fixed heights to adaptive max-height constraints for better small landscape and split-screen behavior.
- Made checkbox action fields full-row switch targets with explicit switch role and on/off state descriptions.
- Added regression coverage for metadata field keys and legacy HTTP POST body handling.

## v0.2.61 - 2026-06-14

Security hardening, platform readiness, and new actions/functions.

- **Target SDK 36**: raised `targetSdk` from 35 to 36 for Android 16 platform compliance.
- **HTTP POST body bound**: POST bodies are now capped at 1 MB and use fixed-length streaming mode before the network connection opens.
- **Regex match timeout**: user-authored regex operations in variable expansion now have a 2-second wall-clock timeout to prevent ReDoS.
- **Network Security Config**: added platform-level scoping that blocks public-host cleartext while permitting LAN/private-range HTTP (forward-compat with Android 17 `usesCleartextTraffic` deprecation).
- **android:allowBackup=false**: explicitly declared for privacy-first posture.
- **Android 17 audio gating**: `sound.play` and `tts.speak` now fail honestly on Android 17+ when background audio requires a media FGS type the engine does not hold; capability registry updated.
- **Hilt shrinker cleanup**: removed stale `Hilt_OpenTaskerApp` and `dagger.hilt.android.HiltAndroidApp` keep rules from proguard-rules.pro.
- **Theme toggle**: added DataStore-backed System/Dark/Light theme preference with a toggle card in the Setup screen; wired into MainActivity and widget config.
- **Wake-on-LAN action** (`wol`): sends a magic packet to wake devices on the local network with MAC validation, configurable broadcast IP/port, and unit tests.
- **Date template function**: added `{{ value | date:'pattern' }}` for epoch-millis formatting with bounded patterns, Locale.ROOT output, and fail-closed rejection of invalid patterns or non-numeric input.
- **Registry-metadata parity test**: bidirectional contract test ensuring every runtime action has UI metadata and vice versa.
- **Action guard tests**: new `ActionGuardsTest` covering POST body cap, URI scheme allowlist, wait duration cap, HTTP policy, ping host validation, missing-argument failures, and WoL packet construction.

## v0.2.60 - 2026-06-14

- Fixed State context matching so battery, charging, headphones, and screen facts persist across partial broadcasts instead of replacing one another.
- Added State context aliases and fail-closed numeric predicate handling for malformed thresholds.
- Added `lintDebug` to the normal GitHub Actions build workflow.
- Fixed Event context matching so repeated identical one-shot events can retrigger profiles while level contexts keep activation/deactivation semantics.
- Fixed boot Event context truthfulness by routing manifest boot starts through `AutomationService` into a replay-safe `event=boot_completed` pulse, and removed unsupported SMS-received trigger advertising from the active event source.
- Removed the legacy parallel automation engine, second `automation.db` Room database, legacy Hilt provider module, dead minimal activity, shell-capable legacy action, and dead battery/geofence manifest receivers. Active app, WiFi, and time monitors now publish into core context bridges; rebuilt APKs shrank from 22,321,836 to 21,799,321 bytes (debug) and 2,107,361 to 2,041,684 bytes (release unsigned).
- Added configurable Run Log retention with short, standard, and extended presets. The standard default keeps 30 days or 1,000 entries, prunes on service/UI startup and hourly after inserts, and includes DAO pruning coverage.
- Added Setup-tab database backup and restore controls. Backups checkpoint and export the active Room database through Android's document picker; imported backups are validated, staged for the next startup, applied before Room opens, and roll back to the previous database if restore fails.
- Added Profiles-tab OpenTasker JSON bundle export/import. Exports use Android's document picker, imports preview schema/version/counts/warnings/capability requirements before confirmation, and imported profiles are always disabled for review.
- Added a Play distribution manifest policy gate that omits SMS and phone-state permissions, hides SMS setup, and marks the SMS action unsupported while keeping standard/F-Droid SMS behavior intact.

## v0.2.59 - 2026-05-05

Dependency modernization, visual flow, scene editor, and navigation polish.

- Added typed graph-node targets to the pure automation flow model so profile, context, task, action, and missing-reference nodes can route back to existing editors.
- Made Flow tab nodes selectable and wired them into the current profile/task/action/context edit dialogs, with stale-target feedback if the underlying Room data changes.
- Added first-class conditional action metadata to the flow graph so conditional steps render with `if ...` edge labels and compact conditional markers instead of being hidden inside generic action details.
- Added a compact, horizontally scrollable Flow lane overview for profile/context/enter/exit lanes as the first read-only canvas interaction before drag/drop editing.
- Added deterministic Flow graph accessibility summaries and node labels, then wired them into Compose semantics for screen readers and UI automation.
- Added Flow-tab mutation shortcuts for adding contexts to a graph profile and adding steps to enter/exit task lanes through the existing context and action pickers.
- Added Scene-tab element creation/editing for button, text, slider, and image controls, with tap and long-press task binding pickers plus removable element rows.
- Replaced the Scene card text-only preview with a scaled canvas projection that renders element positions and sizes against the scene dimensions.
- Added drag-to-move editing on the scaled Scene canvas, converting preview offsets back to bounded scene dp coordinates before updating Room.
- Shortened bottom navigation labels from `Inspector` to `Inspect` and `Run Log` to `Log` so compact navigation items align consistently.
- Upgraded Hilt/Dagger from `2.46` to the intermediate `2.52` line while leaving Kotlin, KSP, AGP, Room, and runtime startup wiring unchanged.
- Verified the Hilt batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, and the F-Droid release profile.
- Upgraded Room from `2.6.1` to `2.8.4` on the existing `androidx.room` artifact line after the Kotlin/KSP/compiler batch; Room 3.0 remains a separate future migration because it uses the new `androidx.room3` group.
- Verified the Room batch with connected migration instrumentation tests on `SM-S938B`.
- Upgraded WorkManager from `2.9.1` to `2.11.2`; no active workers are registered yet, so this batch is dependency/build compatibility only and passed the standard dependency gate.
- Upgraded the stable Compose/AndroidX UI dependency set within the current API 35 / AGP 8.7 constraints: Compose BOM `2025.07.00` and Activity Compose `1.10.1`; newer Activity/Navigation lines are deferred because they require API 36 and AGP 8.9.1, while Compose BOM `2025.08.01+`, Hilt Navigation Compose `1.3.0`, and Lifecycle `2.9.x+` are deferred because they resolve Lifecycle lint checks that need a newer AGP/Kotlin analysis stack.
- Upgraded the runtime-support dependency subset to Core KTX `1.18.0`, DataStore `1.2.1`, Coroutines `1.10.2`, Kotlinx Serialization JSON `1.11.0`, and Gson `2.14.0`.
- Upgraded the compiler alignment set to Kotlin/Compose plugin `2.3.21` and KSP `2.3.7`, migrating Gradle configuration from deprecated `kotlinOptions` to `compilerOptions`.
- Resolved the earlier Kotlin `2.3.21`/KSP `2.3.7` blocker by moving Hilt/Dagger from `2.52` to `2.59.2` after the AGP 9 batch.
- Upgraded the Android build toolchain to Gradle wrapper `8.13`, AGP `8.13.2`, compile SDK `36`, and Build Tools `36.0.0`, while keeping target SDK `35`.
- Verified the AGP/API 36 batch with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`; the previous release R8 Kotlin metadata warnings are gone.
- Upgraded the API 36-unblocked AndroidX stable dependency set: Core KTX `1.18.0`, Compose BOM `2026.04.01`, Activity Compose `1.13.0`, Lifecycle `2.10.0`, Navigation Compose `2.9.8`, and Hilt Navigation Compose `1.3.0`.
- Verified the AndroidX follow-up with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Upgraded the AGP 9 compatibility stack to Gradle wrapper `9.4.1`, AGP `9.2.1`, Hilt/Dagger `2.59.2`, Kotlin/Compose plugin `2.3.21`, KSP `2.3.7`, and Kotlinx Serialization JSON `1.11.0`.
- Added temporary AGP 9 compatibility flags for the explicit Kotlin plugin path: `android.builtInKotlin=false` and `android.newDsl=false`; these keep the build green now but must be removed before AGP 10 by migrating to built-in Kotlin and Android Components/new DSL APIs.
- Verified the AGP 9 stack with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Migrated AGP 9 to built-in Kotlin and the new DSL by removing the explicit `org.jetbrains.kotlin.android` plugin, deleting the temporary AGP 9 opt-out flags, and replacing the deprecated androidTest asset source-set mutation.
- Verified the built-in Kotlin/new DSL migration with debug Kotlin/unit tests, debug androidTest compile, debug APK/lint, F-Droid release profile with metadata checks, and install/start smoke on `SM-S938B`.
- Reviewed Room 3 and deferred migration because the new `androidx.room3:room3-*` artifact group is alpha-only (`3.0.0-alpha03`) and would touch both persisted databases plus migration tests.

## v0.2.58 - 2026-05-05

Tasker XML import UI and F-Droid release verification.

- Added a user-facing Tasker XML import flow to the Profiles screen using Android's document picker.
- The preview reads selected XML with a bounded 4 MB limit, parses it through the existing secure Tasker importer, and shows source counts, import counts, mapped/unsupported actions, migration warnings, and capability review notes.
- Confirmed imports now reuse the existing Room-backed OpenTasker bundle repository and create imported Tasker profiles disabled by default for review.
- Added a pure `TaskerImportPlanner` for preview summaries and disabled-by-default confirmed bundles with JVM coverage.
- Synced the draft F-Droid metadata to version `0.2.58` / code `60` and pinned it to release source commit `40d0daef29b4ab9b6ee9bc6fc395722bb58fd9c9`.
- Added `:app:verifyFdroidMetadata` plus CI/release workflow coverage so F-Droid metadata version fields, commit pinning, Gradle properties, preassemble hooks, changelog URL, and unsigned APK output stay in sync.
- Added `tools/verify-fdroid-release.ps1` for release-tag checks, F-Droid lint/build execution, and signature-agnostic APK payload comparison against a signed upstream APK.
- Verified local `fdroid lint` and WSL fdroidserver 2.4.4 `fdroid build --no-tarball com.opentasker.app:60` with Java 17 and Android SDK 35.

## v0.2.57 - 2026-05-05

Calendar and sun device smoke evidence.

- Added `tools/collect-calendar-sun-evidence.ps1` to capture adb calendar/sun smoke evidence.
- The harness launches OpenTasker, optionally grants Calendar access, captures package/service/provider evidence, and can require Calendar permission, CalendarProvider access, and foreground `AutomationService` state.
- Verified the debug app on API 36 device `SM-S938B` with evidence `build/device-evidence/calendar-sun/20260505-152622`.
- The smoke run confirmed `READ_CALENDAR` was granted, CalendarProvider calendar and instance queries succeeded, and `AutomationService` was foreground after app launch.
- Patched the new adb evidence scripts for Windows PowerShell 5.1 process-argument compatibility.

## v0.2.56 - 2026-05-05

Calendar and sun context presets.

- Added reusable Event context presets for during-meeting, before-meeting, all-day busy, at sunrise/sunset, and offset sunrise/sunset windows.
- Added preset controls to the Event context editor when `event=calendar`, `event=sunrise`, or `event=sunset` is selected.
- Presets preserve unrelated filters such as calendar allowlists while replacing the state/window fields they own.
- Added JVM coverage for calendar preset coverage, sun offset windows, and preset application behavior.

## v0.2.55 - 2026-05-05

NFC write-helper flow.

- Added an NFC tag write session that arms a one-time NDEF text-record write and consumes the next scanned tag while armed.
- Supports writable and formattable NDEF tags with size/read-only failure messages surfaced through the write session.
- Added an NFC write helper card to the Event context editor when `event=nfc` is selected.
- MainActivity now gives armed writes priority over normal NFC trigger publication.
- Added JVM coverage for NFC write-label normalization and payload-size estimation.

## v0.2.54 - 2026-05-05

Locale plugin validation harness.

- Added `tools/validate-locale-plugin.ps1` to capture adb evidence for an installed Locale/Tasker-compatible plugin package.
- The harness records package path, `dumpsys package`, resolver command output, contract-action checks, and a structured `summary.json`.
- Supports required setting/condition contract checks and an optional synthetic `REQUEST_QUERY` broadcast to OpenTasker.
- Documented the harness as the repeatable sample-plugin validation path for X3 follow-up testing.

## v0.2.53 - 2026-05-05

Locale request-query event handling.

- Added a foreground-runtime listener for Locale `ACTION_REQUEST_QUERY` broadcasts from condition plugins.
- Emits sanitized `event=locale_request_query` context events with the requested condition activity class and deterministic bundle JSON.
- Rejects blank or malformed activity class names and reuses primitive-only bundle sanitization for request-query payloads.
- Added package visibility for `REQUEST_QUERY` and JVM coverage for request-query event construction.

## v0.2.52 - 2026-05-05

Locale plugin configuration result handling.

- Added explicit edit-setting and edit-condition intent resolution for Locale-compatible plugin configuration activities.
- Fails closed when a plugin package exposes no matching configuration activity or multiple ambiguous activities.
- Added guarded configuration result parsing that accepts only primitive bundle values and emits deterministic JSON plus bounded blurb text.
- Reused the same string-only bundle safety policy for plugin-returned configuration data, rejecting null, nested, parcelable, and arbitrary object values.
- Added JVM coverage for deterministic bundle JSON encoding and primitive-only configuration result sanitization.

## v0.2.51 - 2026-05-05

Locale condition unknown-state handling.

- Added a bounded in-memory last-known-state cache for Locale condition plugin query results.
- Resolves `RESULT_CONDITION_UNKNOWN` to the last known state for the same plugin package and guarded bundle.
- Treats unknown condition results without history as unsatisfied instead of exposing an ambiguous success path.
- Added JVM coverage for last-known fallback, no-history behavior, and bundle-scoped cache keys.

## v0.2.50 - 2026-05-05

Locale condition plugin query baseline.

- Added `plugin.locale.query` to issue explicit `QUERY_CONDITION` ordered broadcasts to Locale/Tasker-compatible condition plugin receivers.
- Added guarded parsing for Locale condition result codes: satisfied, unsatisfied, unknown, and unrecognized-result fail-closed handling.
- Hardened Locale setting execution to resolve a single explicit receiver component before dispatch instead of broadcasting to an entire package.
- Extended Locale plugin discovery metadata with setting/condition receiver permissions for future disclosure UI.
- Added package-visibility queries for Locale execution receivers and JVM coverage for condition result-code mapping.

## v0.2.49 - 2026-05-05

Day schedule polish.

- Added a shared `DaySchedule` parser for day contexts with canonical weekday order, weekday/weekend/daily aliases, numeric day tokens, and inclusive day ranges such as `MON-FRI`.
- Updated Day context matching to use the shared parser so imported, typed, and UI-created schedules evaluate consistently.
- Replaced raw Day context editing with quick presets, individual day toggles, canonical save output, and validation that blocks invalid day schedules before saving.
- Improved profile and inspector summaries so Day contexts show human-readable labels such as `Weekdays`, `Weekends`, or `Every day`.
- Added JVM coverage for day aliases, wrapped ranges, numeric tokens, and ContextMatchEvaluator day matching.

## v0.2.48 - 2026-05-05

Post-reconnect unplugged evidence checks.

- Extended `tools/collect-location-evidence.ps1` with `-RequireRecentUnpluggedHistory`, `-MinimumUnpluggedHistorySeconds`, and `-MaximumUnpluggedHistoryAgeMinutes` for workflows where USB ADB is unavailable while the phone is unplugged.
- Added recent unplugged interval parsing from `dumpsys battery` power and battery-change history so post-reconnect runs can fail closed on duration.
- Captured post-reconnect API 36 evidence `build/device-evidence/location/20260505-125057`; the device history showed a recent unplugged interval from `2026-05-05T12:48:23.598` to `2026-05-05T12:50:14.389`, about 111 seconds, which was below the 600-second roadmap threshold.
- Captured follow-up API 36 evidence `build/device-evidence/location/20260505-143254`; the recent unplugged interval from `2026-05-05T14:21:53.052` to `2026-05-05T14:32:08.107` lasted 615.055 seconds and satisfied the 600-second post-reconnect history gate with GPS/network provider cadence evidence present.

## v0.2.47 - 2026-05-05

Location durability evidence gates.

- Extended `tools/collect-location-evidence.ps1` with structured battery parsing for plug state, charge counter, current, voltage, and sample deltas.
- Added `-RequireUnpluggedSample` so future battery evidence fails closed if the device is connected to USB/AC/wireless/dock power before or after the sample.
- Added `-RequireProviderCadenceEvidence` so Location evidence can assert that `dumpsys location` contains expected OpenTasker GPS/network cadence registrations or historical aggregates.
- Verified the new collector gates on connected API 36 device `SM-S938B` with evidence `build/device-evidence/location/20260505-120448`; the run correctly detected USB power and GPS/network cadence, so it is tooling evidence only, not an unplugged battery reliability claim.

## v0.2.46 - 2026-05-05

Background Location delivery evidence.

- Verified the installed/enabled `Location evidence log` template on connected API 36 device `SM-S938B` with the app sent home.
- Used a shell-owned GPS test provider to deliver the template coordinates while `AutomationService` stayed foreground with `specialUse|location`.
- Captured Room evidence under `build/device-evidence/location/20260505-085413` showing a successful `Location evidence log Task` run log after evidence collection started.
- Extended `tools/collect-location-evidence.ps1` so `-RequireRunLogMessagePattern` can match the recent run-log message, task name, or the triggered task's action JSON.

## v0.2.45 - 2026-05-05

Location event evidence assertions.

- Extended `tools/collect-location-evidence.ps1` to snapshot the debug app's Room database through `run-as`.
- Writes `room-summary.json` with profile, task, and recent run-log counts/details when local Python/SQLite support is available.
- Added optional `-RequireRunLogMessagePattern` and `-RequireLogcatPattern` checks so a background Location run can fail closed unless execution evidence is present.
- Kept database capture non-fatal for non-debug or non-`run-as` builds while preserving foreground-service validation.

## v0.2.44 - 2026-05-05

Location evidence template.

- Added a disabled-by-default `Location evidence log` profile template for configuring a test radius with latitude, longitude, radius, max-accuracy, and dwell slots.
- The template installs as a normal Location context plus a log action, so future device smoke work can verify actual Location event delivery without manual context construction.
- Kept the template setup-required with explicit foreground/background location and device Location prerequisites.
- Added JVM coverage for the template catalog entry and generated Location context config.

## v0.2.43 - 2026-05-05

Location device evidence harness.

- Added `tools/collect-location-evidence.ps1` to collect adb-backed foreground-service, permission, location, logcat, and battery snapshots for Location/geofence verification.
- The harness writes timestamped JSON summaries and raw evidence files under ignored `build/device-evidence/location/`.
- Supports optional permission grants and an app-to-home sample to verify the foreground automation service remains active while the app is backgrounded.
- Verified the harness against connected API 36 device `SM-S938B`; a 10-second home/background sample kept `AutomationService` foreground with `specialUse|location` and recorded battery snapshots.

## v0.2.42 - 2026-05-05

Foreground service launch repair.

- Started `AutomationService` from `MainActivity` using `ContextCompat.startForegroundService`.
- Kept boot receiver startup intact while ensuring app launch also activates the automation engine.
- Logged foreground-service startup failures from the activity path.
- Added a JVM source contract test for the activity-to-service startup path.
- Verified on a connected API 36 device that app launch starts the foreground service with the `specialUse|location` type after foreground/background location permissions and device location are enabled.

## v0.2.41 - 2026-05-05

Location policy disclosures.

- Added shared Android location policy disclosure copy for Setup and Context Inspector.
- Explains that Android 11+ background location is granted from app settings instead of the foreground permission dialog.
- Explains that approximate foreground access limits background precision.
- Adds Android 14+ foreground-service location gating copy when foreground and background location prerequisites are ready.
- Added JVM coverage for the location disclosure policy text.

## v0.2.40 - 2026-05-05

Geofence cadence tuning.

- Added a balanced location provider request policy for the FOSS `LocationManager` source.
- Requests GPS updates less aggressively than network updates to reduce baseline location polling pressure.
- Added cadence metadata to the waiting-for-location setup event for inspector/debug visibility.
- Extended location setup rechecks from 30 seconds to 60 seconds.
- Added JVM coverage for cadence defaults and validation.

## v0.2.39 - 2026-05-05

Geofence dwell cleanup.

- Added profile-scoped persisted dwell-state cleanup for deleted profiles.
- Cleared a profile's persisted Location dwell keys when its context list changes, preventing removed or reindexed geofences from retaining stale timers.
- Routed the active automation view model through the application context so profile edits can maintain location dwell storage.
- Kept enable/disable and profile metadata edits from resetting dwell timers when contexts are unchanged.

## v0.2.38 - 2026-05-05

Context Inspector dwell detail.

- Added per-profile Location observation enrichment in the Context Inspector using the same persisted dwell state as runtime matching.
- Added location check rows that show inside, outside, accuracy-blocked, or unknown dwell status with elapsed time against configured dwell duration.
- Kept source cards raw while profile check rows display geofence-specific dwell metadata for the selected profile/context.
- Added regression coverage for transformed Location observations during profile inspection.

## v0.2.37 - 2026-05-05

Persisted geofence dwell state.

- Added profile/context-scoped Location dwell keys with config hashes so edited geofences do not reuse stale inside-since state.
- Added a pure dwell-state tracker that preserves `insideSinceEpochMs` across accurate inside samples and clears it when a sample leaves the radius.
- Persisted dwell state in app-local preferences so dwell timers can survive process restarts.
- Wired ProfileMatcher to enrich Location context events with persisted dwell metadata before FOSS geofence evaluation.
- Added regression coverage for first-entry persistence, dwell carry-forward, outside clearing, low-accuracy preservation, and stable key hashing.

## v0.2.36 - 2026-05-05

Live FOSS location source baseline.

- Added a registered `location` context source backed by Android `LocationManager`, with GPS/network providers and last-known-fix seeding.
- Added fail-closed source events for missing permissions, disabled providers, unavailable services, and source errors.
- Declared the Android 14+ location foreground-service contract while keeping background geofence reliability gated behind background location and device verification.
- Updated Setup and Context Inspector copy for foreground, approximate, precise, and background location states.
- Added regression coverage for location event metadata, runtime source registration, and manifest foreground-service location declarations.

## v0.2.35 - 2026-05-05

Template regex policy.

- Made regex-like template functions (`match`, `matches`, `regex`, and `replace`) explicitly unsupported.
- Preserved fail-closed behavior by keeping the original template token when regex-like functions are used.
- Kept existing bounded legacy `%var(regex:...)` behavior separate from the new template engine.
- Added regression coverage for explicit regex-template rejection.

## v0.2.34 - 2026-05-05

Template condition expansion.

- Added bounded `{{ ... }}` expansion to action conditions before legacy predicate evaluation.
- Preserved legacy `%var` condition behavior and applied template expansion only when a condition contains template tokens.
- Made template condition warnings fail closed by skipping the action instead of running on an unsafe or unknown expression.
- Added regression coverage for template conditions, JSON path conditions, and warning-based condition skips.

## v0.2.33 - 2026-05-05

Per-expression template diagnostics.

- Persisted bounded per-expression template trace lines beneath action trace summaries.
- Parsed template trace lines back into structured run-log diagnostics with argument name, source scope, expression, value, and optional warning.
- Rendered individual template expressions in Run Log trace rows, including source scope and redacted values for sensitive arguments.
- Added regression coverage for persisted template trace lines, sensitive expression redaction, and structured parsing.

## v0.2.32 - 2026-05-05

Template run-log diagnostics.

- Parsed template expansion details out of action trace messages into structured run-log diagnostics.
- Added per-step expanded argument summaries and template warning counts to the Run Log UI.
- Preserved ordinary parenthesized failure messages while recognizing generated template detail suffixes.
- Added regression coverage for parsing expanded argument details, warning counts, and normal parenthesized messages.

## v0.2.31 - 2026-05-05

Runtime template argument expansion.

- Wired action argument expansion through the bounded `TemplateExpressionEngine` after legacy `%var` expansion.
- Added `VariableStore` template snapshots for task-local, event, global, and array scopes.
- Added sanitized expanded-argument summaries, template warnings, and per-argument expansion traces to `ActionExecutionTrace`.
- Redacted sensitive argument names such as tokens, keys, secrets, cookies, and passwords from run-log summaries.
- Added regression coverage for runtime template expansion, event scope lookup, array lookup, warning propagation, and summary redaction.

## v0.2.30 - 2026-05-05

Template expression engine baseline.

- Added a pure `TemplateExpressionEngine` for bounded `{{ ... }}` template expansion.
- Added task/event/global scope precedence, explicit scope prefixes, array indexing/count/join support, and JSON path reads from scoped values.
- Added safe string and math pipe functions with traces and warnings for debugging expansion behavior.
- Added fail-closed limits for template length, expression count, function chains, resolved value size, output size, and unknown functions.
- Documented the template expression baseline and added regression coverage for scope, defaults, string/math transforms, JSON paths, arrays, and expansion limits.

## v0.2.29 - 2026-05-05

FOSS geofence evaluator baseline.

- Added a pure `FossGeofenceEvaluator` with Haversine distance, radius checks, optional max accuracy, and dwell-time evaluation.
- Wired active Location context matching through the FOSS evaluator without adding Play Services dependencies.
- Added Location editor fields for max accuracy and dwell seconds.
- Reused the same evaluator for the older geofence trigger distance path and added regression coverage for radius, accuracy, dwell, and active context matching.

## v0.2.28 - 2026-05-05

Profile sharing manifest baseline.

- Added a pure profile-share manifest model for OpenTasker bundles with stable slugs, counts, trust state, and submission metadata.
- Added safety findings for unsupported/setup-required actions, schema warnings, lossy import warnings, and missing screenshots.
- Added GitHub Discussions submission markdown generation without adding network publishing or verified-template claims.
- Documented the sharing baseline and added unit coverage for manifest counts, blockers, slug validation, and submission text.

## v0.2.27 - 2026-05-05

Termux script readiness baseline.

- Added a gated `script.termux.run` action with metadata and a runtime failure path that does not execute scripts.
- Added Termux and Termux:Tasker package visibility and optional setup status detection.
- Added Setup checklist copy for the Termux script bridge while excluding it from required readiness progress.
- Documented the non-executing scripting baseline and added tests for package constants, manifest queries, and capability gating.

## v0.2.26 - 2026-05-05

Shizuku readiness baseline.

- Added package visibility and runtime status detection for the Shizuku manager without linking the Shizuku API.
- Added an optional Setup checklist row for Shizuku power mode that is excluded from required readiness progress.
- Added elevated-action hints for Shizuku candidates while keeping restricted actions blocked.
- Documented the safe readiness scope and added tests for status, action hints, and manifest package visibility.

## v0.2.25 - 2026-05-05

Scene library baseline.

- Added a Room-backed Scenes tab that lists persisted scenes and supports safe scene creation/deletion.
- Added scene validation for positive dimensions, empty scenes, element bounds, and missing tap/long-press task bindings.
- Added scene cards with canvas summaries, element/binding previews, overlay-permission readiness status, and validation messages.
- Documented the scene baseline and updated roadmap/version metadata for L2.
- Added unit coverage for scene validation warnings, geometry errors, missing task references, and valid bounded elements.

## v0.2.24 - 2026-05-05

Visual flow baseline.

- Added a pure automation flow graph model that maps profiles to contexts, enter/exit tasks, actions, edges, and warnings.
- Added an optional Flow tab that renders read-only per-profile graphs from the active Room data without replacing the list/form editor.
- Added graph warnings for missing tasks, empty contexts, and empty task lanes.
- Documented the visual flow baseline and updated roadmap/version metadata for L1.
- Added unit coverage for enter chains, exit chains, missing task references, and empty-context warnings.

## v0.2.23 - 2026-05-05

Dependency modernization baseline.

- Added a Gradle version catalog for Android, Kotlin, Compose, Room, WorkManager, Coroutines, Hilt, Gson, and test dependency versions.
- Converted root and app Gradle plugin/dependency declarations to catalog aliases without changing dependency versions.
- Documented the staged dependency modernization order, risk rules, and verification gates for future upgrade batches.
- Updated F-Droid draft metadata and version metadata for the centralized dependency baseline.

## v0.2.22 - 2026-05-05

F-Droid readiness baseline.

- Added an `openTaskerDistribution=fdroid` Gradle profile without changing existing Android variant names.
- Pinned Android build tools to `35.0.0` and exposed `BuildConfig.DISTRIBUTION`.
- Added `verifyFdroidReadiness` to block common proprietary dependency families from the F-Droid profile.
- Added CI coverage for the F-Droid release profile.
- Added F-Droid readiness docs and a draft fdroiddata metadata file for `com.opentasker.app`.

## v0.2.21 - 2026-05-05

Tasker XML import baseline.

- Added a secure Tasker XML parser that converts common task/profile/variable structures into an OpenTasker JSON bundle.
- Added a migration report model with mapped actions, unsupported Tasker action placeholders, skipped profile/context warnings, variable counts, and scene exclusions.
- Added an explicit unsupported imported Tasker action runtime failure path and capability metadata.
- Documented the supported import surface and updated roadmap/README/version metadata for X10.
- Added regression tests for action mapping, unsupported action preservation, profile skipping, variable import, scene warnings, and Wait conversion.

## v0.2.20 - 2026-05-05

Calendar and sun trigger baseline.

- Added a local CalendarProvider event bridge that emits redacted `event=calendar` metadata for busy current or upcoming events.
- Added sunrise/sunset matching with user-provided latitude/longitude, offset minutes, and bounded trigger windows.
- Added Calendar access onboarding, Event context editor fields for calendar/sun filters, and Inspector setup copy.
- Promoted the meeting-mode calendar template from planned to setup-required installation.
- Updated roadmap/docs/version metadata and regression tests for calendar filtering, sun calculations, and template installation.

## v0.2.19 - 2026-05-05

NFC tag trigger baseline.

- Added an NFC event bridge that accepts tag/tech/NDEF discovery intents and emits `event=nfc` context events with normalized tag IDs.
- Routed cold-start and foreground NFC intents through `MainActivity` into the existing Event context source.
- Added NFC tag ID filtering to Event contexts and exposed an NFC tag ID field in the context editor.
- Promoted the nightstand NFC sleep template from planned to setup-required installation.
- Updated inspector/setup copy, roadmap/docs/version metadata, and regression tests for NFC matching and template installation.

## v0.2.18 - 2026-05-05

Notification listener trigger baseline.

- Added a `NotificationListenerService` event bridge that emits `event=notification` context events without logging notification text.
- Merged notification events into the existing Event context source for profile matching and context inspection.
- Added package allowlists, title/body filters, bounded regex matching, and fail-closed invalid-regex behavior for Event contexts.
- Expanded the context editor for notification event filters and updated docs/version metadata for the X7 baseline.

## v0.2.17 - 2026-05-05

Context inspector baseline.

- Added an Inspector tab with live registered context-source health, latest observed values, setup status, and source errors.
- Added per-profile match explanations that show whether enabled profiles currently match and which context blocks activation.
- Added a reusable context-inspection model with tests for source health, missing events, all-context matching, and inverted contexts.
- Updated roadmap, project notes, README metadata, and app version metadata for the X6 baseline.

## v0.2.16: 2026-05-04

Automation mode baseline.

- Added per-profile automation modes: single, restart, queued, and parallel.
- Added a Room v1-to-v2 migration that persists `automationMode` on profiles.
- Added profile editor mode selection and profile cards showing the current mode.
- Updated `AutomationService` dispatch so re-triggers can be skipped, restarted, queued, or run in parallel.
- Added unit coverage for profile entity automation-mode round trips and legacy fallback.

## v0.2.15: 2026-05-04

External automation target baseline.

- Added a permission-scoped exported receiver for documented external automation intents.
- Added external actions to run tasks, enable/disable profiles, query automation status, and pass task variables.
- Persisted external task runs to the Room run log with action trace summaries.
- Added manifest permission strings and security documentation for external callers.
- Added unit coverage for external variable-name validation and documented variable extra names.

## v0.2.14: 2026-05-04

Locale plugin host baseline.

- Added Locale/Tasker-compatible setting plugin dispatch through a new `plugin.locale.fire` action.
- Added explicit package validation, string-only JSON bundle decoding, bundle size limits, blurb handling, and timeout wrapping.
- Added plugin discovery metadata for Locale edit-setting/edit-condition packages and requested permission disclosure.
- Added manifest package visibility queries for Locale-compatible plugin discovery.
- Documented the supported plugin host surface and added parser/trust-boundary unit tests.

## v0.2.13: 2026-05-04

Open JSON bundle baseline.

- Added schema-versioned OpenTasker JSON bundle models for profiles, tasks, actions, contexts, variables, scenes, and metadata.
- Added deterministic export ordering and capability requirement metadata for setup-required or unsupported actions.
- Added import planning/reporting with warnings for unsupported actions and lossy missing-reference handling.
- Added Room-backed export/import repository logic with task ID remapping, variable upsert, profile remapping, and scene element link remapping.
- Documented the v1 JSON bundle format and added unit coverage for sorting, capability metadata, validation, and JSON round trips.

## v0.2.12: 2026-05-04

Profile template baseline.

- Added an on-device profile template catalog with eight roadmap-backed starter patterns.
- Added slot substitution for template names, context configs, and action arguments.
- Added a Compose template picker and slot form that installs templates as disabled profiles with starter tasks.
- Gated planned calendar, NFC, and external-intent templates so they are visible but cannot create broken profiles yet.
- Added unit coverage for catalog completeness, unsupported-action gating, slot expansion, and planned-template blocking.

## v0.2.11: 2026-05-04

Public documentation truthfulness pass.

- Corrected README action counts and active runtime-context claims to match the compiled APK.
- Clarified that plugin hosting, Tasker XML import/export, day schedules, and location/geofence runtime support are planned or still being hardened rather than shipped.
- Updated architecture docs to describe the current foreground-service trigger monitors and action capability gates.
- Removed stale audit/checkpoint documents that overclaimed completion against older source snapshots.

## v0.2.10: 2026-05-04

Regression-test hardening pass.

- Hardened cron step/range parsing so malformed expressions fail closed instead of throwing.
- Added tests for malformed cron steps and valid minute/hour cron matching.
- Added tests for variable scope shadowing and missing-variable expansion.
- Updated README/roadmap metadata for the expanded regression coverage.

## v0.2.9: 2026-05-04

Run log tracing baseline.

- Added action execution traces with index, label, action type, duration, status, and message.
- Persisted summarized action traces in task run-log messages.
- Expanded run-log cards to show multi-line action trace summaries.
- Added unit coverage for trace summary formatting.

## v0.2.8: 2026-05-04

Capability gating baseline.

- Added a central action capability registry for supported, setup-required, and unsupported actions.
- Annotated task action rows and action picker cards with setup/unsupported status.
- Disabled unsupported privileged or unimplemented actions in the add-action flow.
- Added warning copy in action configuration dialogs for actions that require setup.
- Added unit coverage for capability gating defaults.

## v0.2.7: 2026-05-04

Runtime registry and stub-failure hardening pass.

- Registered built-in action implementations and context sources during app startup.
- Aligned runtime action IDs with the action metadata IDs saved by the Compose editor.
- Replaced success-shaped action stubs with real behavior where practical and explicit unsupported failures where Android requires privileged access.
- Implemented notification, intent launch, SMS send, volume, media-key, HTTP POST, and HTTPS download execution paths.
- Removed unused placeholder context source files and stopped silently swallowing application-context polling errors.
- Added unit coverage to ensure every UI action metadata ID has a runtime action implementation.

## v0.2.6: 2026-05-04

App-open trigger hardening pass.

- Removed the unused plain background `AppOpenService` and its manifest entry.
- Added a foreground-service-owned `AppUsageMonitor` that polls `UsageStatsManager` only when usage access is granted.
- Added opened/closed `AppEvent` dispatch when the foreground package changes.
- Shared usage-access detection between setup UI and the app-open monitor.
- Added focused unit coverage for foreground package selection.
- Updated README/roadmap metadata for app-open monitoring.

## v0.2.5: 2026-05-04

WiFi trigger hardening pass.

- Replaced the manifest `CONNECTIVITY_CHANGE` receiver with a lifecycle-owned `ConnectivityManager.NetworkCallback`.
- Added WiFi event dispatch from the foreground automation service with duplicate-state suppression.
- Added Android 13 nearby WiFi devices permission metadata and setup checklist coverage.
- Added SSID normalization tests for quoted and unknown platform values.
- Updated README/roadmap metadata for platform-safe WiFi monitoring.

## v0.2.4: 2026-05-04

Exact alarm hardening pass.

- Removed `USE_EXACT_ALARM` so OpenTasker no longer declares the alarm-clock/calendar-only permission.
- Added an app-owned time tick scheduler that uses exact `AlarmManager` delivery when allowed and inexact `setWindow()` fallback when exact alarms are denied.
- Replaced the manifest `TIME_TICK` dependency with an internal scheduled receiver and exact-alarm permission-change rescheduling.
- Added focused unit coverage for minute-boundary scheduling.
- Updated setup text and README/roadmap metadata for exact-alarm fallback behavior.

## v0.2.3: 2026-05-04

Permission onboarding pass.

- Added a Setup tab with live status for Android runtime permissions and special access gates.
- Added direct request/open-settings actions for notifications, exact alarms, battery optimization, usage access, notification access, overlay access, foreground/background location, Bluetooth, SMS, and DND access.
- Added Bluetooth scan permission metadata for Android 12+ Bluetooth setup.
- Updated README/version metadata for the setup checklist.

## v0.2.2: 2026-05-04

Active UI reintegration pass.

- Replaced the launcher-only status screen with a live Compose management UI.
- Added profile creation, editing, enable/disable toggling, deletion, and context attachment backed by Room.
- Added task creation, editing, deletion, and action add/edit/delete flows driven by the action metadata registry.
- Restored run-log browsing inside the active APK.
- Registered built-in action metadata during app startup so dynamic action forms are populated.
- Updated README/version metadata to reflect the active UI state.

## v0.2.1: 2026-05-04

Production hardening pass.

- Fixed Windows and Linux Gradle bootstrap scripts so builds work from paths containing `--`.
- Aligned app version metadata and README badge to the shipped APK version.
- Re-enabled release minification and resource shrinking while keeping unsigned release builds possible without local secrets.
- Consolidated release CI and added a push/PR build workflow.
- Removed tracked local build artifacts and machine-specific configuration from the repository.
- Replaced broken Hilt runtime entrypoints with the active non-Hilt application singleton wiring.
- Hardened shell, intent, file, network, notification, settings, geofence, receiver, backup, and JSON parsing paths.
- Added Room schema export and focused validation unit tests.
- Improved shared Compose component semantics and light-theme error contrast.

## v0.2.0: 2026-05-04

Full UI layer with database integration and action editor.

- **Database integration:** Room DAOs with StateFlow live updates for profiles and tasks
- **Profile CRUD:** Create, edit, delete profiles with persistence
- **Task CRUD:** Create, edit, delete tasks with action lists
- **Action editor:** Dynamic form generation for registered action definitions based on metadata registry
- **Context picker:** Multi-select context families with predicate configuration (app, time, day, location, state, event)
- **Action metadata system:** Comprehensive metadata for all built-in actions with field types and validation
- **Task list screen:** Dedicated view to browse and manage all tasks
- **Profile enable/disable toggle:** Toggle profiles on/off with database update
- **Gradle 8.9 toolchain:** Updated from 8.7 for AGP 8.7.2 compatibility
- **Lint baseline:** Suppressed MissingPermission and CoarseFineLocation warnings

## v0.1.0: 2026-05-03

Initial scaffold.

- Project skeleton: Kotlin 2.0 + Jetpack Compose + Material 3
- Core data model: Profile / Context / Task / Action / Scene / Variable
- AMOLED-black default theme
- Architecture document (`docs/ARCHITECTURE.md`)
- Roadmap (`ROADMAP.md`) tracking parity with Tasker feature surface
- MIT license, shields.io badges
