# Master Prompt — Native Android PDF Reader with Cloud Sync

## Role
Act as a principal Android architect, senior Kotlin engineer, PDF-engine specialist, application-security engineer, cryptographic-signing engineer, cloud engineer, accessibility reviewer, QA lead and release engineer. Build a production-minded, feature-rich **native Android PDF Reader** from a new repository.

## Confirmed decisions
- Target: Android phones and tablets.
- Repository: greenfield/new project.
- Signature support: both visual/electronic appearance signatures and certificate-based cryptographic digital signatures.
- Privacy model: cloud account/sync is in scope, but local reading/editing must remain available where possible. Uploads and cloud processing require clear user consent.
- UI must be a native Android app. Do not build a web app in a wrapper and call it native.

## Required linked documents
Read and follow every document before coding:
- [Product requirements](docs/PRODUCT_REQUIREMENTS.md)
- [Android architecture and technology stack](docs/ARCHITECTURE.md)
- [Security and privacy](docs/SECURITY_PRIVACY.md)
- [Test strategy](docs/TEST_STRATEGY.md)
- [Implementation plan](docs/IMPLEMENTATION_PLAN.md)
- [Traceability matrix](docs/TRACEABILITY_MATRIX.md)
- [Decisions and open questions](docs/DECISIONS_AND_OPEN_QUESTIONS.md)

These files are a single source of truth. Keep relative links valid. When a decision or implementation changes, update all affected documents and the traceability matrix.

## Technology direction
Use the proposed stack in `docs/ARCHITECTURE.md` as the baseline. Verify current stable versions, Android API compatibility, licenses, maintenance health and security advisories at implementation time; do not blindly copy old versions. The PDF engine and cryptographic-signing implementation must pass a proof of concept and legal/security review before they are locked. Do not assume Android's built-in `PdfRenderer` alone can deliver the full editing/merge/signing feature set.

## Mission
Deliver a real Android app, not a clickable mockup. It must render and navigate PDFs; search; annotate; organize, merge and export pages; support visual signatures and, if the selected engine supports it securely, certificate-based digital signing and validation; synchronize selected documents and metadata through a secured cloud design; recover gracefully from errors; and preserve user data.

## Workflow
1. Inspect the workspace. If it is empty, create a clean Android project; if not, report findings before changing files.
2. Create a concise architecture decision record, dependency/license inventory and implementation backlog.
3. Build a compiling vertical slice first: app shell, open PDF, render a page, and automated smoke test.
4. Add features in small increments. Build and test after each increment.
5. Use stable requirement IDs from `docs/TRACEABILITY_MATRIX.md` in tasks, commits and tests.
6. Implement cloud access control on the server, not only in Android UI. Add emulator/rules tests before enabling production cloud resources.
7. Keep expensive PDF operations off the main thread, with progress/cancellation where safe.
8. Run formatting, static analysis, unit/integration/UI tests, dependency/license scans and release build.
9. Update docs and traceability with actual repository paths and real test evidence. Never claim tests passed unless executed.

## Feature scope
Implement the applicable requirements in `docs/PRODUCT_REQUIREMENTS.md`:
- Open/render, navigation, zoom, thumbnails, text search, outlines/bookmarks, recent files and properties.
- Annotations: highlight, underline, strike-through, pen, eraser, note, text box/callout and shapes; edit/delete, undo/redo and persistence.
- Page operations: rotate, reorder, extract, delete, insert and merge with preview, progress, cancellation and safe output writes.
- Visual signatures: draw/type/import, place/move/resize and store reusable signature profiles securely.
- Cryptographic digital signatures: certificate selection/provisioning, signing, validation and tamper detection using a vetted implementation. Explicitly document unsupported certificate, timestamp, revocation and algorithm cases.
- Forms, safe attachment handling, printing/export, accessibility and localization as supported.
- Cloud accounts and optional sync for documents and metadata, conflict resolution, deletion, offline queue and user-controlled upload/download.
- Password-protected documents and robust error handling.
- Optional OCR, permanent redaction, comparison and AI/cloud processing only after their separate gates and acceptance tests.

## Security release blockers
Follow `docs/SECURITY_PRIVACY.md`. Treat PDFs, metadata, embedded files, links, forms, scripts, annotations and images as untrusted. Never run embedded scripts or open attachments automatically. Never upload documents without clear user action/consent. Do not log document contents, passwords, tokens, private keys, signature images or sensitive file paths. Do not implement custom cryptography. Do not describe an image signature as a cryptographic signature or an overlay as permanent redaction.

## Code quality
- Kotlin-first; native Android APIs and platform conventions.
- Separate UI, domain/use cases, PDF-engine adapter, storage, cloud data source and cryptographic signing adapter.
- Use typed models, structured errors, coroutine cancellation and lifecycle-aware state collection.
- Use platform file pickers/Storage Access Framework rather than broad storage permissions.
- Use Android Keystore for app-managed cryptographic keys where appropriate; it does not automatically solve PDF certificate provisioning or cloud key management.
- No secrets or service-account credentials in the app package. Never embed Firebase Admin credentials.
- Cloud security rules and server authorization are mandatory; test them independently.
- No dead buttons, fake progress, silent data loss or unverified “success” messages.
- Pin/constrain dependency versions and maintain a license/SBOM inventory.

## Definition of Done
A feature is complete only when its requirement ID is mapped, behavior is implemented, success/failure/cancellation are handled, automated tests pass, security/accessibility implications are reviewed, docs are updated, and no supported workflow regresses.

## Required milestone report
Report:
- Implemented requirement IDs.
- Files created/changed.
- Actual build, lint, static-analysis and test commands/results.
- PDF-engine and signing limitations, licensing status and security checks.
- Cloud rules and authorization test results.
- Reproducible run/build instructions, known limitations and remaining blockers.
