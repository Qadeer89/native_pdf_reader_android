# Third-Party Notices

This app is built with the open-source components listed below. This file is generated from
`planning/DEPENDENCY_LICENSE_INVENTORY.md` (the authoritative, maintained inventory — update
that file first when a dependency changes, then regenerate this one) and is intended to satisfy
each license's attribution/notice requirement and Google Play's own expectation that an app
disclose the open-source licenses of components it ships. All licenses below are permissive
(Apache-2.0, MIT, EPL-1.0); none require this app's own source to be disclosed.

| Component | Version | License |
|---|---|---|
| Kotlin | 1.9.24 | Apache-2.0 |
| Android Gradle Plugin | 8.5.2 | Apache-2.0 |
| Gradle | 8.9 | Apache-2.0 |
| AndroidX Core KTX | 1.13.1 | Apache-2.0 |
| AndroidX Lifecycle (runtime, viewmodel-compose) | 2.8.4 | Apache-2.0 |
| AndroidX Activity Compose | 1.9.1 | Apache-2.0 |
| Jetpack Compose (BOM, UI, Material3) | 2024.06.00 | Apache-2.0 |
| Kotlin Coroutines (Core/Android) | 1.8.1 | Apache-2.0 |
| AndroidX Room (runtime, ktx, compiler, testing) | 2.6.1 | Apache-2.0 |
| PDFBox-Android (TomRoush/PdfBox-Android, Android port of Apache PDFBox) | 2.0.27.0 | Apache-2.0 |
| BouncyCastle (bcprov/bcpkix/bcutil-jdk15to18) | 1.72 | MIT |

Test-only dependencies (JUnit, Robolectric, AndroidX Test, Espresso) and build-only tooling (KSP)
are not shipped inside the app artifact and so carry no runtime attribution obligation, but are
listed in `planning/DEPENDENCY_LICENSE_INVENTORY.md` for completeness.

Full license texts for Apache-2.0 and MIT are standard and widely available
(https://www.apache.org/licenses/LICENSE-2.0, https://opensource.org/license/mit/); this project
does not vendor a copy of either text in this file, following the common practice of linking to
the canonical text rather than reproducing it per-dependency.

**Not yet done** (tracked in `docs/RELEASE_CHECKLIST.md`): an in-app "Open source licenses"
screen surfacing this same list, and legal counsel review of all licenses before any commercial
release (per `docs/ARCHITECTURE.md` §5) — this file is a developer-maintained notice, not a
substitute for that review.
