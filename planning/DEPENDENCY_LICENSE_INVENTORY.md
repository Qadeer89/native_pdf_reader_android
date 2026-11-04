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
| AndroidX Room (runtime, ktx, compiler, testing) | 2.6.1 | Apache-2.0 | Google (AndroidX) | Local recent-documents metadata (PDF-004); compiler via KSP | Stores only Uri/name/page-count/timestamp — never PDF bytes, per docs/ARCHITECTURE.md §1 |
| KSP (Kotlin Symbol Processing) | 1.9.24-1.0.20 | Apache-2.0 | Google/JetBrains | Build-time annotation-processor plugin (replaces kapt) for Room's compiler | Build tooling only — ships nothing into the app artifact |
| PDFBox-Android | 2.0.27.0 | Apache-2.0 | [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android) (Android port of Apache PDFBox) | PDF-005/PDF-006 annotation/page-ops engine | **Approved as DEC-001** (`docs/DECISIONS_AND_OPEN_QUESTIONS.md`) after a passing POC. Promoted from `testImplementation` to `implementation` in `app/build.gradle.kts` once approved. **Maintenance caution carried forward**: latest release dated 2023-01-02 (~3.5 years stale as of this writing), 113 open issues / 12 open PRs at time of check — license is clean, freshness is not; revisit if a security advisory surfaces upstream. |
| BouncyCastle (`bcprov-jdk15to18`, `bcpkix-jdk15to18`, `bcutil-jdk15to18`) | 1.72 | MIT | [bouncycastle.org](https://www.bouncycastle.org/) | **Transitive** dependency pulled in automatically by PDFBox-Android (not declared directly in `app/build.gradle.kts`) | Confirmed via `./gradlew :app:dependencies --configuration debugRuntimeClasspath`. Android Lint flags a `TrustAllX509TrustManager` pattern (empty `checkClientTrusted`/`checkServerTrusted`) inside `bcpkix-jdk15to18-1.72.jar` itself — a known pattern in BouncyCastle's bundled utility classes, not something this app's code invokes, but worth tracking: (1) this is now the de facto crypto provider available for PDF-008 (certificate signing) rather than something to add separately, so re-evaluate "BouncyCastle, not yet evaluated" below as partially overtaken by events; (2) pin and monitor this transitive version for advisories before relying on it for signing, since it did not go through its own dedicated vetting pass. |
| Google Services Gradle Plugin | 4.4.2 | Apache-2.0 | Google | Reads `app/google-services.json`, makes Firebase config available to the SDKs | Added 2026-11-05 for PDF-011 (DEC-003). Build tooling only — ships nothing into the app artifact. |
| Firebase BOM | 33.1.2 | Apache-2.0 | Google | Version alignment for Firebase Auth/Firestore/Storage artifacts | Added 2026-11-05. Since BOM 32+, Kotlin extensions are merged into the main artifacts — no separate `-ktx` dependency needed. |
| Firebase Auth | via BOM | Apache-2.0 | Google | PDF-011 cloud sync — user identity | Added 2026-11-05, gated behind DEC-003 (real Firebase project `pdf-reader-73abf` created by the user). SDK wired into the build only — no `FirebaseAuth` calls exist in app code yet; sign-in method not yet confirmed (see `docs/DECISIONS_AND_OPEN_QUESTIONS.md`). |
| Firebase Firestore | via BOM | Apache-2.0 | Google | PDF-011 cloud sync — document metadata sync | Added 2026-11-05, same gating as Firebase Auth above. No Firestore calls in app code yet; owner-scoped security rules and SEC-002 cross-user-denial tests not yet designed. |
| Firebase Storage | via BOM | Apache-2.0 | Google | PDF-011 cloud sync — PDF file upload/download | Added 2026-11-05, same gating as Firebase Auth above. No Storage calls in app code yet. Pulls in `io.grpc:grpc-android`/`grpc-okhttp` (Apache-2.0) and `com.google.protobuf:protobuf-javalite` (BSD-3-Clause) transitively — confirmed via `:app:dependencies`, no license or version conflicts with existing dependencies. |

Room's annotation processor now runs via KSP (`com.google.devtools.ksp`, version `1.9.24-1.0.20` — matches this project's Kotlin version, Apache-2.0, JetBrains/Google) instead of kapt; migrated per the non-blocking cleanup item previously tracked in `IMPLEMENTATION_BACKLOG.md`.

Android `android.graphics.pdf.PdfRenderer` is a platform API, not a separate dependency — no separate license entry required (Android Open Source Project, Apache-2.0, bundled with the OS).

## Planned, not yet added (re-verify version/license before adding)

| Dependency | Candidate purpose | Phase | Status |
|---|---|---|---|
| Hilt | DI once module count grows | Phase 2+ | Deferred per ADR-003 |
| DataStore | Preferences (no secrets) | Phase 2 | Not yet selected version |
| WorkManager | Background sync | Phase 5 | Not yet selected version |
| Apryse / Nutrient (commercial PDF SDKs) | Annotation/page-ops/forms/signing, alternative to PDFBox-Android | Phase 3/4, gated | **Not actionable by this agent** — quote-only pricing, requires a sales/trial engagement the user would need to initiate themselves. See DEC-001. |
| iText | Possible PDF manipulation | Gated | AGPLv3/commercial — AGPL incompatible with a closed-source app without disclosing full source; commercial tier is quote-only. Requires legal/business review before any use; not added. See DEC-001. |
| Android Keystore APIs | App-managed key protection | Phase 4 | Platform API, no new dependency, but design/review required before use |
| BouncyCastle — dedicated signing evaluation | Certificate-based PDF signing (PDF-008) | Phase 4, gated | BouncyCastle itself is already present transitively (see the "In use" table above, pulled in by PDFBox-Android) — but only for whatever PDFBox already uses it for internally. Actually *using* it directly for PDF-008 signing still needs its own design/review pass per docs/SECURITY_PRIVACY.md §5 ("no custom crypto... no homemade CMS/PAdES implementation"); not done yet. |

## Governance notes
- All versions above are pinned via a Gradle version catalog (`gradle/libs.versions.toml`); no `+` ranges.
- No dependency in the "Planned" table may be added without updating this inventory with its actual license, source, and maintenance status at the time of addition, per `docs/ARCHITECTURE.md` §5.
- Dependency vulnerability scanning is now wired into CI via `.github/dependabot.yml` (gradle + github-actions ecosystems, weekly, GitHub-native — no Gradle plugin needed). `.github/workflows/ci.yml` dumps the resolved dependency tree as a build artifact on every run to feed this document's manual license-audit process; a fully automated license-compliance report (e.g. `com.github.jk1.dependency-license-report`) was not added in this pass — see `planning/IMPLEMENTATION_BACKLOG.md` Phase 6.
- Commercial distribution requires qualified legal counsel review of all licenses before release, per `docs/ARCHITECTURE.md` §5. Not performed in this pass.
