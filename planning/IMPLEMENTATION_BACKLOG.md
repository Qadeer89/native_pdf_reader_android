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

## Phase 2 — Reader MVP (not started)
- [ ] SAF document picker integration (`Intent.ACTION_OPEN_DOCUMENT`) replacing the bundled-asset-only flow. — PDF-001
- [ ] Share-intent (`ACTION_SEND`/`VIEW` with `application/pdf`) and file-association handling. — PDF-001
- [ ] Malformed/encrypted/Unicode-filename fixture tests. — PDF-001, PDF-013
- [ ] Continuous/single-page layouts, pinch-zoom, fit width/page, page-jump. — PDF-002
- [ ] Thumbnails strip, page count, rotation, full-screen, outline/bookmark navigation. — PDF-002
- [ ] Text search with match navigation and result count. — PDF-003
- [ ] Recent documents (Room-backed), clear history, document properties. — PDF-004
- [ ] Lifecycle/process-death state restoration tests. — PDF-002, PDF-012

## Phase 3 — Annotations and page operations (blocked)
- [ ] **Gate not yet passed**: PDF-engine proof of concept for annotation + page-operation support (`docs/ARCHITECTURE.md` §2). `PdfRenderer` alone cannot write annotations or merge/reorder pages.
- [ ] Annotation tools (highlight/underline/strike/pen/eraser/note/textbox/shapes), undo/redo, save/reopen round trip. — PDF-005
- [ ] Rotate/reorder/extract/delete/insert/merge with preview, progress, cancellation, temp-file-then-commit writes. — PDF-006

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
