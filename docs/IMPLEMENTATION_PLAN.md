# Implementation Plan — Android + Cloud

## Phase 0 — Decisions and proof of concept
- Confirm minSdk/targetSdk, supported device range, cloud region/retention and commercial license budget.
- Evaluate PDF SDKs against rendering, annotations, page operations, forms, visual signatures, cryptographic signing/validation and supported Android versions.
- Review dependency licenses, transitive licenses, vendor maintenance and security advisories.
- Define whether client-side end-to-end encryption is required.
**Gate:** signed-off decision record and successful PDF/signature proof of concept.

## Phase 1 — Greenfield foundation
- Create Kotlin Android project with Compose, Material 3, Gradle Kotlin DSL and version catalog.
- Configure app modules, lint/static analysis, CI, dependency/license inventory and test fixtures.
- Add navigation, theme, ViewModels, repositories and typed error model.
**Gate:** clean build and test on emulator and real device.

## Phase 2 — Reader MVP
- SAF open/import, render, zoom, navigation, thumbnails, search, outlines, recent files and properties.
- Offline local reading and safe save pipeline.
**Gate:** PDF-001 to PDF-004 acceptance tests pass.

## Phase 3 — Annotations and page operations
- Annotation tools, edit/delete, undo/redo, save/reopen.
- Rotate, reorder, extract, insert, delete, merge, preview, progress/cancel and output validation.
**Gate:** PDF-005/PDF-006 round-trip and failure tests pass.

## Phase 4 — Signature workflows
- Visual signature drawing/import/placement and protected local storage.
- Certificate-based signing and validation through vetted PDF/crypto engine.
- Test tampering, certificate trust and signing limitations.
**Gate:** PDF-007/PDF-008 tests pass and the UI accurately differentiates both signature types.

## Phase 5 — Cloud accounts and sync
- Authentication, owner-scoped metadata and object storage.
- Strict cloud rules, emulator tests, upload/download, offline queue, retries, conflict resolution and deletion.
- Consent UX and privacy/retention disclosures.
**Gate:** all cross-user denial tests pass; no public document access by default.

## Phase 6 — Hardening
- Malformed PDF corpus/fuzzing where practical, dependency/license scans, performance and accessibility tests.
- Review key management, logs, temporary files, cloud rules, account deletion and sync conflicts.
**Gate:** no release-blocking security or data-loss issues.

## Phase 7 — Release
- Signed Android App Bundle/APK as appropriate, Play policy review, privacy disclosures, license notices and reproducible build steps.
- Verify fresh install, upgrade, sign-out, uninstall behavior, backup behavior and account/data deletion.
**Gate:** release checklist and evidence complete.

## Definition of Done
Every requirement is `Verified`, `Blocked` or `Out of scope` with evidence/reason. Planned work is never reported as implemented.
