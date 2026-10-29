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
| AndroidX Test (core, junit) | 1.6.1 / 1.2.1 | Apache-2.0 | Google (AndroidX) | Robolectric test runner glue | |

Android `android.graphics.pdf.PdfRenderer` is a platform API, not a separate dependency — no separate license entry required (Android Open Source Project, Apache-2.0, bundled with the OS).

## Planned, not yet added (re-verify version/license before adding)

| Dependency | Candidate purpose | Phase | Status |
|---|---|---|---|
| Hilt | DI once module count grows | Phase 2+ | Deferred per ADR-003 |
| Room | Local metadata/annotation index | Phase 2/3 | Not yet selected version |
| DataStore | Preferences (no secrets) | Phase 2 | Not yet selected version |
| WorkManager | Background sync | Phase 5 | Not yet selected version |
| Commercial PDF SDK (e.g. Apryse, Nutrient) or vetted OSS combination | Annotation/page-ops/forms/signing | Phase 3/4, gated | **Not selected** — requires POC, license review, and legal sign-off per `docs/ARCHITECTURE.md` §2 before adding. Do not add to the build until that gate passes. |
| iText | Possible PDF manipulation | Gated | AGPLv3/commercial — requires legal review before any use; not added |
| Firebase Auth / Cloud Storage / Firestore | Cloud identity/sync | Phase 5, gated | Not added; requires cloud provider decision (open question) |
| Android Keystore APIs | App-managed key protection | Phase 4 | Platform API, no new dependency, but design/review required before use |

## Governance notes
- All versions above are pinned via a Gradle version catalog (`gradle/libs.versions.toml`); no `+` ranges.
- No dependency in the "Planned" table may be added without updating this inventory with its actual license, source, and maintenance status at the time of addition, per `docs/ARCHITECTURE.md` §5.
- Dependency vulnerability/license scanning (e.g. OSS Review Toolkit, Gradle `dependencyCheckAnalyze`, or Google's `./gradlew :app:dependencies` + manual license audit) is not yet wired into CI — tracked as a Phase 1 hardening backlog item (see `IMPLEMENTATION_BACKLOG.md`).
- Commercial distribution requires qualified legal counsel review of all licenses before release, per `docs/ARCHITECTURE.md` §5. Not performed in this pass.
