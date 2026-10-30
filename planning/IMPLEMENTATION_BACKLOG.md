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

## Phase 3 — Annotations and page operations (engine approved, first annotation increment done)
- [x] **PDF-engine proof of concept** (`docs/ARCHITECTURE.md` §2): PDFBox-Android (Apache-2.0) evaluated and POC-tested — annotate/save/reopen round trip, merge, and encrypted-PDF open/reject all pass (`app/src/test/java/com/pdfreader/app/poc/PdfBoxAndroidEnginePocTest.kt`, 3/3 tests). **Approved as DEC-001** (`docs/DECISIONS_AND_OPEN_QUESTIONS.md`) by the user on 2026-10-30. Dependency promoted from `testImplementation` to `implementation`.
- [x] `PdfEditEngine`/`PdfBoxEditEngine` adapter: open, add a text-note annotation, count annotations, write-validated-temp-copy. `PdfBoxEditEngineTest` exercises it directly (not just the gate-evidence POC).
- [x] Minimal annotation UI: "Annotate" mode toggle (disables pinch-zoom while active, by design — see planning/ADR.md ADR-010), tap-to-place a note, Compose `Canvas` overlay markers, "Export copy" action using `ACTION_CREATE_DOCUMENT` (never overwrites the original) with a Saving/Success/Failure banner. — PDF-005, PDF-010
- [ ] Annotation types beyond a plain text note (highlight/underline/strike/pen/eraser/textbox/shapes). — PDF-005
- [ ] Undo/redo and annotation deletion — not implemented; currently annotations can only be added, never removed, within a session.
- [ ] Custom note text entry (currently always "Note" — no text-input dialog yet).
- [ ] Annotating in continuous-scroll layout (currently single-page-mode only).
- [x] Rotate (quarter-turn, live visual feedback via a Compose rotation — no re-render needed), delete, move/reorder, and append/merge (pick another PDF, appended to the end) implemented in `PdfEditEngine`/`PdfBoxEditEngine`; rotate/delete/merge wired into a "Page" menu in the UI. `PdfBoxEditEngineTest` covers all four against the real engine. — PDF-006
- [ ] Reorder/move has no UI yet (engine method + test exist, but nothing lets the user actually trigger a move — no drag-to-reorder on the thumbnail strip).
- [ ] Insert-at-position and extract-to-new-file not implemented (`appendDocument` only adds to the end; there's no "extract selected pages into their own file").
- [ ] No preview, progress, or cancellation for merge/delete/rotate — they apply immediately and silently stage the change (a snackbar says so), which is fine for a 1-3 page fixture but will matter for larger real documents.
- [ ] Page-structure changes (delete/move/merge) are staged only — the live render stays on the unedited file (via `PdfEngine`) until the user exports a copy. This is a known, documented limitation (planning/ADR.md ADR-010), not an oversight, but a "preview after merge/delete" experience would need the render engine to pick up the edited document somehow.
- [ ] Malformed-PDF corpus/fuzz pass against PDFBox-Android before it processes untrusted user documents (docs/SECURITY_PRIVACY.md §6) — not done; the POC and engine tests only used the project's own hand-built fixture.
- [ ] BouncyCastle is now transitively present (via PDFBox-Android — confirmed via `./gradlew :app:dependencies`, see `planning/DEPENDENCY_LICENSE_INVENTORY.md`) but has not been separately vetted for direct use signing PDFs (PDF-008) — still a distinct, gated piece of work.

## Phase 4 — Signature workflows (visual signature done; digital signature still blocked)
- [x] Visual signature draw/type/import, placement, private local storage, excluded from sync by default. — PDF-007. **Not gated** like PDF-008: `docs/DECISIONS_AND_OPEN_QUESTIONS.md` only requires a decision before *certificate-based* signing, and a visual signature is just a stamped image, which the already-approved PDFBox-Android engine (DEC-001) can do directly — no new engine/library decision needed. `LocalFileSignatureRepository` stores signatures as PNGs under app-private `filesDir` (never SAF, never a content provider); `android:allowBackup="false"` (already set, `AndroidManifest.xml`) covers "excluded from sync by default" without needing a separate `dataExtractionRules.xml` (that stays a cross-cutting lint item below, since it only matters once `allowBackup` is ever turned on). Draw (finger strokes), type (rendered text), and import (picked image, downscaled to at most 800px) are all implemented, behind a "Sign" menu in `ReaderScreen.kt`. Placing a signature calls `PdfEditEngine.addImageStamp` and also adds a live Compose overlay (same pattern as note annotations, PDF-005) — see `planning/ADR.md` ADR-012.
- [ ] Imported signature images are not background-removed/trimmed — a photo of a signature on paper will stamp with its paper background intact. — PDF-007
- [ ] No undo for a single placed signature stamp (whole-document "don't export this" is the only way back, same limitation as PDF-006's staged edits).
- [ ] No per-document remembered placement/size — every placement defaults to 35% of page width at the tapped point.
- [ ] Recovery of saved signatures across device changes/reinstalls (an explicit open question in `docs/DECISIONS_AND_OPEN_QUESTIONS.md`) is unaddressed — purely local storage has no backup/recovery story yet, and won't until Phase 5 (cloud sync) is designed.
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
