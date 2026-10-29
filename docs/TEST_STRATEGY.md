# Test Strategy — Native Android PDF Reader

## 1. Test layers
- Unit: page ranges, document IDs, annotation geometry, merge ordering, conflict resolver, validation and error mapping.
- Integration: PDF engine adapter, save/reopen, encrypted files, forms, visual signatures, digital-signature validation and cloud repositories.
- UI/system: Compose screens, SAF picker, intents, lifecycle, accessibility, navigation, progress, cancellation and offline behavior.
- Cloud security: Firebase Emulator Suite rules tests using multiple identities and malicious requests.
- Security: malformed PDF corpus, unsafe links/attachments, temp files, log inspection, dependency/license scans and signing tamper tests.
- Device: smoke test on supported Android API levels, phone/tablet layouts and at least one low-memory device profile.

## 2. PDF fixtures
Include legally usable fixtures for single/multi-page PDFs, mixed sizes/rotations, text, images, links, outlines, forms, annotations, visual and cryptographic signatures, encrypted/restricted PDFs, Unicode filenames, large files, malformed/truncated files and embedded attachments. Never use real confidential customer documents.

## 3. Core test cases
- Open via SAF, share intent and recent file; handle revoked URI permissions.
- Render, zoom, navigate, search and restore state after lifecycle/process recreation.
- Add/edit/move/delete each annotation type, undo/redo, save/reopen and compare geometry.
- Rotate/reorder/extract/delete/insert/merge and validate output page count/order.
- Preserve source on failure, cancellation, permission denial and disk-full simulation.
- Visual signature is persisted and labeled as visual only.
- Digital signing: valid signed output; tampered bytes; invalid/expired/untrusted certificate; unsupported algorithm; certificate/key cancellation; supported timestamp/revocation behavior.
- Forms save/reopen; attachments and external links require explicit user action.
- Cloud: unauthenticated denied; user A cannot read/write user B data; owner access succeeds; revoked share denied; oversized upload rejected; invalid metadata rejected.
- Sync: offline queue, retry, cancellation, duplicate retry/idempotency, conflict, remote deletion, local deletion, sign-out and account deletion.
- No secrets/document text in logs, crash reporting or analytics.
- Keyboard/screen-reader smoke tests and adaptive layout checks.

## 4. Performance and reliability
Benchmark first-page render, page navigation, search, merge, annotation save, memory usage and upload/download on representative devices and fixture sizes. Record device, Android version, PDF engine version and conditions. Establish budgets after baseline measurement rather than inventing numbers.

## 5. CI/release gates
- Gradle build, lint, unit tests, instrumented/Compose UI tests where feasible.
- Dependency vulnerability and license scans.
- Firebase rules tests must pass before deploying cloud resources.
- Release build uses R8 and securely managed signing configuration.
- No critical regression, cross-account access or silent data loss.
- Traceability matrix records actual commands, results and evidence.
