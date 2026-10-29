# Dependency and License Inventory — Vertical Slice

Only dependencies actually added for the Phase 1 vertical slice are listed with locked versions below. Dependencies anticipated for later phases are listed separately as "Planned, not yet added" and must be re-verified (current stable version, license, advisories) at the time they are actually introduced, per `docs/ARCHITECTURE.md` §5. No dynamic (`+`) versions are used; all versions are pinned in `gradle/libs.versions.toml`.

## In use (Phase 1 vertical slice)

| Dependency | Version | License | Source | Purpose | Notes |
|---|---|---|---|---|---|
| Kotlin | 1.9.24 | Apache-2.0 | JetBrains/kotlinlang.org | Primary language | Matches AGP/Compose compiler compatibility |
| Android Gradle Plugin | 8.5.2 | Apache-2.0 | Google/Android Open Source Project | Build tooling | Pinned in `build.gradle.kts` |
| Gradle | 8.9 | Apache-2.0 | Gradle Inc. | Build system | Wrapper committed; `gradlew`/`gradlew.bat` |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 | Google (AndroidX) | Kotlin extensions for platform APIs | |
| AndroidX Lifecycle (runtime, viewmodel-compose) | 2.8.4 | Apache-2.0 | Google (AndroidX) | Lifecycle-aware ViewModel/StateFlow | |
| AndroidX Activity Compose | 1.9.1 | Apache-2.0 | Google (AndroidX) | Compose host activity integration | |
| Jetpack Compose BOM | 2024.06.00 | Apache-2.0 | Google (AndroidX) | Compose UI, foundation, material3 version alignment | |
| Jetpack Compose Material3 | via BOM | Apache-2.0 | Google (AndroidX) | Material 3 components | |
| Kotlin Coroutines Core/Android | 1.8.1 | Apache-2.0 | JetBrains/Kotlin | Off-main-thread PDF open/render, structured concurrency | Required by Security baseline (PDF ops off main thread) |
| JUnit4 | 4.13.2 | EPL-1.0 | JUnit team | Unit test framework | |
| Robolectric | 4.13 | MIT | robolectric.org | JVM-executable Android smoke test (no device/emulator in this environment; see ADR-006) | |
| AndroidX Test (core, junit, runner) | 1.6.1 / 1.2.1 / 1.6.2 | Apache-2.0 | Google (AndroidX) | Robolectric test runner glue + instrumented-test scaffolding (`src/androidTest`) | |
| AndroidX Espresso Core | 3.6.1 | Apache-2.0 | Google (AndroidX) | Instrumented-test scaffolding (`src/androidTest`, not yet executed — no device here) | |
| AndroidX Room (runtime, ktx, compiler, testing) | 2.6.1 | Apache-2.0 | Google (AndroidX) | Local recent-documents metadata (PDF-004); compiler via kapt | Stores only Uri/name/page-count/timestamp — never PDF bytes, per docs/ARCHITECTURE.md §1 |

Room's annotation processor runs via `org.jetbrains.kotlin.kapt` (bundled with the Kotlin distribution already in use, so no separate version/license entry beyond Kotlin's own). Lint flags that Room also supports KSP, which is faster; migrating is tracked in `IMPLEMENTATION_BACKLOG.md` as non-blocking cleanup.

Android `android.graphics.pdf.PdfRenderer` is a platform API, not a separate dependency — no separate license entry required (Android Open Source Project, Apache-2.0, bundled with the OS).

## Under evaluation (testImplementation only — not shipped in the app)

| Dependency | Version | License | Source | Purpose | Notes |
|---|---|---|---|---|---|
| PDFBox-Android | 2.0.27.0 | Apache-2.0 | [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Android port of Apache PDFBox) | Phase 3 engine POC (annotate/save/reopen, merge, encrypted-PDF handling) — see `docs/DECISIONS_AND_OPEN_QUESTIONS.md` DEC-001, `planning/ADR.md` ADR-009 | **Maintenance caution**: latest release dated 2023-01-02 (~3.5 years stale as of this writing), 113 open issues / 12 open PRs at time of check. License is clean; freshness is not. Scoped to `testImplementation` in `app/build.gradle.kts` specifically so it cannot ship until DEC-001 is approved. |

## Planned, not yet added (re-verify version/license before adding)

| Dependency | Candidate purpose | Phase | Status |
|---|---|---|---|
| Hilt | DI once module count grows | Phase 2+ | Deferred per ADR-003 |
| DataStore | Preferences (no secrets) | Phase 2 | Not yet selected version |
| WorkManager | Background sync | Phase 5 | Not yet selected version |
| Apryse / Nutrient (commercial PDF SDKs) | Annotation/page-ops/forms/signing, alternative to PDFBox-Android | Phase 3/4, gated | **Not actionable by this agent** — quote-only pricing, requires a sales/trial engagement the user would need to initiate themselves. See DEC-001. |
| iText | Possible PDF manipulation | Gated | AGPLv3/commercial — AGPL incompatible with a closed-source app without disclosing full source; commercial tier is quote-only. Requires legal/business review before any use; not added. See DEC-001. |
| Firebase Auth / Cloud Storage / Firestore | Cloud identity/sync | Phase 5, gated | Not added; requires cloud provider decision (open question) |
| Android Keystore APIs | App-managed key protection | Phase 4 | Platform API, no new dependency, but design/review required before use |
| BouncyCastle (or equivalent vetted crypto provider) | Certificate-based PDF signing (PDF-008), likely paired with whichever PDF engine is selected | Phase 4, gated | Not evaluated yet — explicitly out of scope for this POC pass; docs/SECURITY_PRIVACY.md §5 requires a vetted library, never custom crypto |

## Governance notes
- All versions above are pinned via a Gradle version catalog (`gradle/libs.versions.toml`); no `+` ranges.
- No dependency in the "Planned" table may be added without updating this inventory with its actual license, source, and maintenance status at the time of addition, per `docs/ARCHITECTURE.md` §5.
- Dependency vulnerability/license scanning (e.g. OSS Review Toolkit, Gradle `dependencyCheckAnalyze`, or Google's `./gradlew :app:dependencies` + manual license audit) is not yet wired into CI — tracked as a Phase 1 hardening backlog item (see `IMPLEMENTATION_BACKLOG.md`).
- Commercial distribution requires qualified legal counsel review of all licenses before release, per `docs/ARCHITECTURE.md` §5. Not performed in this pass.
