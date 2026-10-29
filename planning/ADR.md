# Architecture Decision Record — Vertical Slice (Phase 1)

Scope: decisions needed to produce a compiling, testable vertical slice (app shell, open a PDF, render a page). Full-feature decisions (annotation/signing engine, cloud backend) remain gated per `docs/DECISIONS_AND_OPEN_QUESTIONS.md` and are listed as open below, not decided here.

## ADR-001 — minSdk / targetSdk / compileSdk for the slice
- Status: Proposed (needs product sign-off per `docs/DECISIONS_AND_OPEN_QUESTIONS.md`)
- Decision: `minSdk = 26` (Android 8.0), `targetSdk = 34`, `compileSdk = 34`.
- Rationale: `android.graphics.pdf.PdfRenderer` (used for baseline rendering, PDF-002) has existed since API 21, but API 26 covers >98% of active devices (per Android Studio distribution data at time of writing — reverify before release) and lets us use modern Compose/Material3 and `ContentResolver` APIs without excessive compat shims. This is a starting point for the vertical slice only; final minSdk is still an open decision (see Open Questions).
- Consequences: no API 21-25 support; revisit if a lower floor is required by stakeholders.

## ADR-002 — Vertical-slice PDF rendering: Android `PdfRenderer`
- Status: Proposed for Phase 1/2 only. Does NOT satisfy the engine-selection gate in `docs/ARCHITECTURE.md` §2.
- Decision: Use the platform `android.graphics.pdf.PdfRenderer` behind a `core:pdfengine` adapter interface (`PdfEngine`) to open a document and render page bitmaps for the vertical slice.
- Rationale: Zero license cost, zero extra dependency, sufficient to prove the open→render path end-to-end and unblock UI/architecture work. `PdfRenderer` cannot do annotations, page merge/reorder, forms, or signing — those require the separate PDF-engine proof-of-concept mandated by `docs/ARCHITECTURE.md` §2 before PDF-005/006/007/008/009 are implemented.
- Consequences: the `PdfEngine` interface is designed so the platform renderer can be swapped for (or composed with) a commercial/open-source engine later without changing call sites in `feature:reader`.
- Open follow-up: commercial-SDK POC (Apryse/Nutrint) vs. vetted open-source combination, per `docs/ARCHITECTURE.md` §2 and `docs/DECISIONS_AND_OPEN_QUESTIONS.md`.

## ADR-003 — Dependency injection: manual DI for the slice
- Status: Proposed.
- Decision: Use manual constructor injection via a small `AppContainer` for Phase 1. Defer Hilt adoption until module count justifies it (per `docs/ARCHITECTURE.md` §1: "Hilt for multi-module app, or manual DI for a small MVP").
- Consequences: migration to Hilt is mechanical later; no behavior depends on it yet.

## ADR-004 — Module layout for the slice
- Status: Proposed.
- Decision: Start as a single Gradle module (`app`) containing the slice's UI, a `pdfengine` package (adapter + platform implementation), and a `model` package, rather than pre-splitting into the full module list in `docs/ARCHITECTURE.md` §3. Package-level boundaries (`com.pdfreader.app.pdfengine`, `com.pdfreader.app.model`, `com.pdfreader.app.reader`) mirror the eventual module names so extraction to Gradle modules (`core:pdfengine`, `core:model`, `feature:reader`) is a low-risk refactor once annotation/pageops/signature/cloud features arrive and justify the build-time cost of multi-module separation.
- Rationale: avoids premature multi-module ceremony for a one-screen slice while keeping the eventual boundaries visible in the package structure.

## ADR-005 — UI toolkit and architecture pattern
- Status: Confirmed per `docs/ARCHITECTURE.md` (not a new decision).
- Decision: Jetpack Compose + Material 3, single-Activity, MVVM/UDF with `ViewModel` + `StateFlow`.

## ADR-006 — Test strategy for the slice
- Status: Proposed; adjusted for this environment (see note).
- Decision: One JVM-executable smoke test using Robolectric (`org.robolectric:robolectric`) that opens a bundled fixture PDF from app assets through the `PdfEngine` adapter, renders page 1, and asserts a non-empty bitmap of the expected dimensions. Robolectric lets `./gradlew testDebugUnitTest` actually execute this against a simulated Android environment without a device/emulator.
- Environment note: no Android emulator or physical device is available in this execution environment (no AVD images, no connected device). A true instrumented test (`androidTest`, run via `connectedAndroidTest`) could not be executed here and must not be reported as passing. The Robolectric unit test is the automated smoke test actually run and verified in this pass; a real-device/emulator instrumented test is carried forward as backlog (see `IMPLEMENTATION_BACKLOG.md`).
- Rationale: satisfies `MASTER_PROMPT.md` workflow step 3 ("plus one automated smoke test") and step 9 ("Never claim tests passed unless executed") simultaneously — the test is both automated and actually runnable in this environment.

## Open questions carried forward (not decided in this pass)
See `docs/DECISIONS_AND_OPEN_QUESTIONS.md` for the full list. Items directly blocking later phases:
- Final PDF engine for annotations/page-ops/forms/signing (commercial vs. open-source) — blocks PDF-005, PDF-006, PDF-009.
- Digital-signature profile (certificate formats, PAdES level, timestamps, revocation, trust roots) — blocks PDF-008.
- Cloud provider/region/retention/E2EE requirement — blocks PDF-011.
- App applicationId/package name, distribution channel — currently using placeholder `com.pdfreader.app`; must be finalized before release packaging.
