# Release Checklist — Phase 7 (`planning/IMPLEMENTATION_BACKLOG.md`)

Tracks what a real Play Store release of this app needs. Marked `[x]` only for what has
actually been done in this repository; everything else is `[ ]` and says exactly what is
missing and who/what has to do it — several items are structurally impossible for an agent
working in this environment (no JDK/Android SDK, no device/emulator, no Play Console account,
no ability to pay for or register a developer account) and are called out as such rather than
silently left unchecked with no explanation.

## 1. Signed build
- [x] Release signing config scaffold in `app/build.gradle.kts`: reads `RELEASE_KEYSTORE_PATH`/
  `RELEASE_KEYSTORE_PASSWORD`/`RELEASE_KEY_ALIAS`/`RELEASE_KEY_PASSWORD` from `local.properties`
  (gitignored, never committed — see `local.properties.example` for the expected keys). Falls
  back to the debug signing config when those keys/file are absent, so a local
  `./gradlew assembleRelease`/`bundleRelease` doesn't just fail outright — but that fallback
  build is **not** suitable for Play Store upload (debug-signed).
- [ ] **Blocked on a human step**: generating an actual release keystore (`keytool -genkeypair
  -v -keystore release/pdf-reader-release.jks -alias pdf-reader -keyalg RSA -keysize 2048
  -validity 10000`) and filling in `local.properties` with its real path/passwords. This agent
  cannot generate and then *keep secret* a real signing key in this environment — the key must
  be created by, and stay only with, whoever will own this app's Play Console listing (losing
  it means losing the ability to ship updates to the same app, ever).
- [ ] **Not verified at all**: no JDK/Android SDK on this machine to actually run
  `./gradlew bundleRelease` — the signing config above has never been exercised, not even with
  the debug-signing fallback path.
- [ ] `isMinifyEnabled` is still `false` for the release build type (unchanged from the vertical
  slice default). Turning on R8 shrinking/obfuscation for a real release is reasonable but
  risks breaking reflection-based libraries here (Room's generated code, PDFBox-Android,
  BouncyCastle) without carefully written/tested `proguard-rules.pro` entries — not attempted
  in this pass since there is no way to build-and-test the result on this machine.

## 2. License notices
- [x] `NOTICE.md` (repo root) lists every shipped dependency's license, generated from
  `planning/DEPENDENCY_LICENSE_INVENTORY.md` (the authoritative source — update that file
  first, then this one, when a dependency changes). All licenses in use are permissive
  (Apache-2.0, MIT, EPL-1.0-but-test-only) — no AGPL/GPL/copyleft dependency is shipped.
- [ ] No in-app "Open source licenses" screen yet (a `ReaderScreen`-style dialog listing the
  same table `NOTICE.md` has) — `NOTICE.md` exists at the repo level but isn't surfaced to an
  actual end user inside the app. Not implemented in this pass.
- [ ] Legal counsel review of all licenses before any commercial release, per
  `docs/ARCHITECTURE.md` §5 — explicitly **not** performed; this agent is not a substitute for
  that review, only for assembling the inventory counsel would review.

## 3. Play policy review
- [ ] **Data Safety form** (Play Console): based on `docs/SECURITY_PRIVACY.md` and this app's
  actual current behavior — no network calls exist yet (cloud sync, PDF-011, is still only a
  not-configured stub per the traceability matrix), no analytics/crash-reporting SDK is
  integrated, and the only data this app persists is 100% local (recent-documents metadata in
  Room, saved signature images/placements in app-private files/SharedPreferences, never synced
  or uploaded). The honest Data Safety answer *today* is "this app does not collect or share
  any user data" — but **this still has to be filled in and submitted through the actual Play
  Console UI by the account holder**, which this agent has no access to.
- [ ] **Permissions declared vs. used**: `AndroidManifest.xml` currently declares no runtime
  permissions at all (SAF/`ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT` need none) — worth
  re-checking this line item once cloud sync (PDF-011) actually lands, since that will likely
  add `INTERNET` at minimum.
- [ ] **Target API level compliance**: `targetSdk = 34` in `app/build.gradle.kts` — meets
  Play's current minimum target API requirement as of this writing; re-verify against Play's
  requirement at actual submission time, since Google updates this annually.
- [ ] **Content rating questionnaire, store listing (title/description/screenshots/feature
  graphic), privacy policy URL, and app category** — none of these exist yet. A privacy policy
  *document* could reasonably be drafted from `docs/SECURITY_PRIVACY.md`'s content, but Play
  requires it be hosted at a public URL the account holder controls, which this agent cannot
  provision or own on the user's behalf. Screenshots need a real running app on a real
  device/emulator, neither of which exists in this environment.
- [ ] **Play Console developer account enrollment and the one-time registration fee** —
  entirely a human/billing step; not actionable by this agent under any circumstances.

## 4. Reproducible build
- [x] `gradle/libs.versions.toml` pins every dependency to an exact version (no `+` ranges),
  and the Gradle wrapper (`gradlew`/`gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties`)
  is committed — both already true before this pass, re-confirmed here.
- [ ] No documented, tested "clean checkout → `./gradlew bundleRelease` → byte-identical AAB"
  reproducibility pass has been done — would need a real JDK/Android SDK environment (twice,
  ideally on two different machines) to actually verify, which this environment can't provide.

## Summary
Of the four sections above, (1) and (2) have real, if partial, progress — signing is scaffolded
and the license notice exists. (3) and most of the unchecked parts of (1)/(4) are either
genuinely blocked on a human with Play Console access and a JDK/device, or on this project
reaching further phases (cloud sync) before some answers (Data Safety, permissions) are even
final. This checklist should be revisited, not re-derived, the next time release work resumes.
