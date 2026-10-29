# Implementation Backlog

Tracks concrete work items against `docs/IMPLEMENTATION_PLAN.md` phases and `docs/TRACEABILITY_MATRIX.md` requirement IDs. Status values match the traceability matrix (`Planned`, `In progress`, `Verified`, `Blocked`, `Out of scope`).

## Phase 1 — Greenfield foundation (this pass)
- [x] Create Kotlin Android project, Gradle Kotlin DSL, version catalog (`app/`, `gradle/libs.versions.toml`). — supports PDF-002
- [x] Define `PdfEngine` adapter interface + platform `PdfRenderer`-backed implementation (`core` package, pre-module-split per ADR-004). — PDF-002
- [x] App shell: single Activity, Compose, theme, navigation stub, ViewModel + StateFlow for reader state. — PDF-002, PDF-012 (off-main-thread requirement)
- [x] Open a bundled fixture PDF and render page 1 to a Bitmap, displayed in Compose `Image`. — PDF-002
- [x] One Robolectric JVM smoke test: open fixture → render page 1 → assert bitmap dimensions (ADR-006; no device/emulator available in this environment). — Test Strategy §1 "Device: smoke test" (partially — real-device run still required, see Cross-cutting)
- [x] Verify clean build + test execution and record actual command output in the milestone report.
- [ ] CI pipeline (lint, unit test, instrumented test, dependency/license scan) — not yet created; no CI system identified in the workspace. **Blocked on**: decision of CI provider (not in docs). Tracked as follow-up.
- [ ] Hilt/DI migration — deferred per ADR-003 until module count grows.

## Phase 2 — Reader MVP (in progress)
- [x] SAF document picker integration (`ActivityResultContracts.OpenDocument`, `application/pdf`), persistable read-permission grant on the returned Uri. — PDF-001
- [x] Share-intent (`ACTION_SEND`) and file-association (`ACTION_VIEW`, `content`/`file` scheme, `application/pdf`) handling via `MainActivity.onNewIntent`/`onCreate`. — PDF-001
- [x] Single-page navigation (next/previous/jump, clamped to bounds) and pinch-to-zoom/pan (`ZoomablePdfPage`, reset per page). — PDF-002
- [x] Thumbnail strip (tap to jump) and a continuous-scroll layout, toggled from the top bar. Both render pages on demand through a small per-(page,width) cache in `ReaderViewModel`. — PDF-002
- [x] Recent documents (Room-backed: `data/recents`), upsert on open, clear-history, per-item delete; document-properties dialog (name/page count/size via `ContentResolver`). Bundled fixture is excluded from recents. — PDF-004
- [ ] Malformed/encrypted/Unicode-filename fixture tests. — PDF-001, PDF-013 (not yet added — needs additional fixtures)
- [ ] Fit-width/fit-page auto-sizing (zoom is currently manual pinch only, no auto-fit toggle). — PDF-002
- [ ] Full-screen mode, outline/bookmark navigation. — PDF-002, PDF-004 — outline needs a text/structure-capable engine (see PDF-003 note below); `PdfRenderer` has no outline API.
- [ ] **Blocked**: text search with match navigation and result count. — PDF-003. `android.graphics.pdf.PdfRenderer` has no text-extraction/search API at all (bitmap rendering only) — this needs the same gated PDF-engine decision as annotations/signing (docs/ARCHITECTURE.md §2, planning/ADR.md ADR-002). Not started; no stub added.
- [ ] Lifecycle/process-death state restoration tests. — PDF-002, PDF-012 (Compose `rememberSaveable` covers in-process rotation for zoom state only; full document-state restoration after process death not yet implemented)
- [ ] Minor lint cleanup carried forward (not release-blocking): migrate Room's kapt annotation processor to KSP; add `android:dataExtractionRules` (replaces deprecated `allowBackup`); add monochrome adaptive-icon variant; split the manifest's multi-attribute `<data>` tags per lint's `IntentFilterUniqueDataAttributes` suggestion.
- [ ] Minor test-log noise, not a failure (all tests still pass, 0 failures): `PdfReaderViewModelSmokeTest` intermittently logs a Robolectric `CloseGuard` "resource acquired but never released" warning for a `SQLiteConnection`/other `Closeable`, even after adding `ViewModelStore.clear()` + explicit `RecentDocumentsDatabase.close()` teardown in `@After`. Root cause not yet isolated — worth another look (possibly a Robolectric SQLite shadow quirk with in-memory DBs, or an unrelated Closeable from the PDFBox POC test running in the same JVM) before relying on clean test logs for CI noise budgets.

## Phase 3 — Annotations and page operations (gate: POC done, engine decision pending)
- [x] **PDF-engine proof of concept** (`docs/ARCHITECTURE.md` §2): PDFBox-Android (Apache-2.0) evaluated and POC-tested — annotate/save/reopen round trip, merge, and encrypted-PDF open/reject all pass (`app/src/test/java/com/pdfreader/app/poc/PdfBoxAndroidEnginePocTest.kt`, 3/3 tests). Recorded as DEC-001 in `docs/DECISIONS_AND_OPEN_QUESTIONS.md` — **Proposed**, not yet **Approved**; this is a licensing/cost decision for the user/business, not something decided unilaterally here. Dependency is `testImplementation`-only until approved (does not ship in the app).
- [ ] **Still blocked on DEC-001 approval** before any production annotation/page-ops code is written: promoting the dependency to `implementation`, designing the adapter surface, and implementing the features below.
- [ ] Annotation tools (highlight/underline/strike/pen/eraser/note/textbox/shapes), undo/redo, save/reopen round trip. — PDF-005
- [ ] Rotate/reorder/extract/delete/insert/merge with preview, progress, cancellation, temp-file-then-commit writes. — PDF-006
- [ ] Malformed-PDF corpus/fuzz pass against the chosen engine before it processes untrusted user documents (docs/SECURITY_PRIVACY.md §6) — not done in the POC pass, which only used the project's own hand-built fixture.

## Phase 4 — Signature workflows (blocked)
- [ ] Visual signature draw/type/import, placement, private local storage, excluded from sync by default. — PDF-007
- [ ] Certificate-based signing/validation via vetted library once selected; tamper/trust/expiry/revocation test cases. — PDF-008
- [ ] **Gate not yet passed**: digital-signature profile open questions in `docs/DECISIONS_AND_OPEN_QUESTIONS.md` (certificate formats, PAdES level, timestamps, revocation, trust roots) must be answered before design.

## Phase 5 — Cloud accounts and sync (blocked)
- [ ] **Gate not yet passed**: cloud provider/region/retention/E2EE decision in `docs/DECISIONS_AND_OPEN_QUESTIONS.md`.
- [ ] Auth, owner-scoped Firestore/Storage rules (or chosen backend), emulator-based cross-user denial tests before any production cloud resource is enabled, per `MASTER_PROMPT.md` workflow step 6. — PDF-011, SEC-002
- [ ] Offline queue, retry/backoff, conflict resolution, deletion propagation. — PDF-011

## Phase 6 — Hardening (not started)
- [ ] Malformed PDF corpus/fuzz testing. — SEC-001
- [ ] Dependency vulnerability/license scan wired into CI.
- [ ] Log/crash-report inspection for sensitive data leakage. — SEC-003, SEC-004
- [ ] Performance baselines (render/navigation/search/merge/upload) on representative devices — no real device/emulator available in this environment; must be measured on actual hardware before committing to budgets.

## Phase 7 — Release (not started)
- [ ] Signed AAB/APK, Play policy review, privacy disclosures, license notices, reproducible build doc.

## Cross-cutting backlog items
- [ ] Replace placeholder `applicationId` (`com.pdfreader.app`) once branding/package-name decision is made (open question in `docs/DECISIONS_AND_OPEN_QUESTIONS.md`).
- [ ] No emulator/physical device was available in this environment; the smoke test was run via Robolectric-style/instrumented config appropriate to the actual execution environment — see milestone report for exactly what was executed and what still needs real-device verification.
