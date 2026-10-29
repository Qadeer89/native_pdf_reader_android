# Android Architecture and Recommended Technology Stack

## 1. Recommended baseline
Use this as the proposed stack for a new native Android application. At implementation time, select compatible current stable versions from official release documentation and lock them in Gradle version catalogs. Do not guess versions or use dynamic `+` dependencies.

| Concern | Recommendation | Notes / licensing |
|---|---|---|
| Language | Kotlin | Android-native primary language |
| UI | Jetpack Compose + Material 3 | Android's recommended modern UI toolkit; verify component versions and API support |
| Architecture | Single Activity, MVVM/UDF, ViewModel, repositories, use cases where useful | Keep UI state observable and lifecycle-aware |
| Async work | Kotlin coroutines + Flow | Cancellation and structured concurrency |
| Dependency injection | Hilt for multi-module app, or manual DI for a small MVP | Avoid unnecessary complexity |
| Local structured data | Room | Store metadata, annotations/index state as appropriate; avoid storing PDF bytes in database by default |
| Simple preferences | DataStore | Avoid plaintext secrets |
| Background sync | WorkManager | Network constraints, retries and persistent work; don't use it for interactive rendering |
| File access | Storage Access Framework / `ContentResolver` | Use user-selected URI grants instead of broad storage access |
| Device keys | Android Keystore | Protect app-managed keys; hardware backing varies by device and is not guaranteed |
| Cloud identity/files | Firebase Authentication + Cloud Storage; Firestore only for suitable metadata | A reasonable MVP option, not a mandatory vendor choice; configure strict rules, budgets, region and retention |
| Cloud backend | Small trusted backend/Cloud Functions if needed for privileged operations | Never put admin/service-account credentials in the Android app |
| PDF rendering | Evaluate Android `PdfRenderer` for baseline rendering, and a maintained PDF SDK for advanced editing/signing | `PdfRenderer` is not a complete annotation/merge/signing SDK; validate supported Android API levels |
| PDF editing/signing | Run a proof of concept against a maintained commercial SDK or a carefully vetted open-source combination | Check Android support, annotations, merge, digital signatures, validation, licenses and vendor security process |
| Testing | JUnit, AndroidX Test, Compose UI tests, Robolectric where appropriate, Firebase Emulator Suite for rules | Add fuzz/corpus tests and real-device smoke tests |
| Build/release | Gradle Kotlin DSL, version catalog, R8, CI, signed release artifacts | Never commit signing keys or secrets |

## 2. PDF engine decision — mandatory gate
No single library is assumed to satisfy the whole feature list. Evaluate candidates using a feature matrix and working proof of concept:
1. Android `PdfRenderer`: platform API (API 21+ baseline for renderer) for rendering. Newer Android versions expose additional PDF capabilities, but do not assume uniform behavior across supported OS versions.
2. Commercial native PDF SDK: evaluate maintained vendors such as Apryse or Nutrient for annotation, page operations, forms and certificate signing/validation. Obtain current written license terms, test trial limitations, and verify supported Android versions and APIs.
3. Open-source components: evaluate each library's maintenance activity, Android compatibility, license obligations and security history. The `pdfbox-android` fork's published README says it is based on PDFBox 2.0.18 and references old JCenter distribution; treat it as a research candidate, not an automatically approved production engine.
4. iText: its official licensing page describes AGPLv3 and commercial licensing. If used in a closed-source distributed app or backend, obtain legal review and the appropriate license before integration.
5. Do not combine multiple PDF engines until a prototype proves that file compatibility, signatures, forms and annotations survive round trips.

**Required proof of concept:** open/render; annotate/save/reopen; merge; preserve existing annotations/forms; add visual signature; certificate-sign; validate signature and detect tampering; encrypted PDF handling; memory/performance; Android API compatibility; dependency/license scan. Record evidence in `DECISIONS_AND_OPEN_QUESTIONS.md`.

## 3. App modules
Keep modules/package boundaries clear; use Gradle modules when they materially improve isolation:
- `app`: startup, navigation, lifecycle, dependency composition.
- `core:model`: typed document, page, annotation, signature, sync and error models.
- `core:designsystem`: Compose theme and shared accessible components.
- `feature:reader`: render, page navigation, zoom, thumbnails and outlines.
- `feature:search`: text search and result navigation.
- `feature:annotations`: annotation tools, transformations, undo/redo.
- `feature:pageops`: rotate, reorder, extract, delete, insert and merge.
- `feature:visualsignature`: signature drawing/import and placement.
- `feature:digitalsignature`: certificate/key selection, signing and validation adapter.
- `feature:forms`: supported forms and safe attachment listing.
- `data:documents`: SAF, recent files, safe local persistence and export.
- `data:cloud`: authentication state, metadata sync, object upload/download, conflict handling.
- `core:pdfengine`: adapter interface and selected PDF SDK implementation.
- `core:security`: key management interfaces, safe logging, link policy, file validation.
- `core:testing`: PDF fixtures and test utilities.

## 4. Data and cloud flow
- UI -> ViewModel -> use case/repository -> local source and/or remote source.
- Local document access uses SAF URIs; copy into app-private temporary storage only when required and documented.
- Editing produces a temporary output, validates/reopens it, then commits to a user-selected destination.
- Cloud uploads happen only after explicit user action or clearly enabled sync setting; show status and allow cancellation where safe.
- Cloud paths are owner-scoped, e.g. `users/{uid}/documents/{documentId}/...`; enforce authorization in server-side rules and test with emulators.
- Metadata and binary document storage have distinct schemas, retention and deletion behavior.
- Define conflict behavior, version IDs, checksums, retry/backoff and idempotency.
- If client-side end-to-end encryption is a requirement, design key derivation, per-file keys, recovery, sharing and multi-device access before implementing sync. Cloud-provider encryption at rest alone is not end-to-end encryption.

## 5. License and dependency governance
For every dependency, record name, version, license, transitive licenses, source URL, maintenance/last release, known advisories, purpose and approved use. Run automated dependency vulnerability and license checks in CI. Review licenses with qualified counsel for commercial distribution. Do not assume “open source” means “no obligations.”

## 6. Reference documentation
- Android architecture recommendations: https://developer.android.com/topic/architecture/recommendations
- Android `PdfRenderer`: https://developer.android.com/reference/android/graphics/pdf/PdfRenderer
- Android Keystore: https://developer.android.com/privacy-and-security/keystore
- Firebase Storage security: https://firebase.google.com/docs/storage/security
- Firebase Security Rules: https://firebase.google.com/docs/rules
- iText licensing: https://itextpdf.com/how-buy
- PDFBox Android fork (research only; verify current state): https://github.com/TomRoush/PdfBox-Android

These references support initial architecture research, not a claim that every candidate is approved or fully verified. Recheck current docs before dependency selection.
