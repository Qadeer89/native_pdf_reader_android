# Requirement Traceability Matrix

Update module paths and test IDs when implementation begins. No requirement may be marked `Verified` without real test evidence.

| ID | Area | Expected module(s) | Minimum verification | Status |
|---|---|---|---|---|
| PDF-001 | Open/import | `app/src/main/java/com/pdfreader/app/MainActivity.kt`, `app/src/main/java/com/pdfreader/app/pdfengine` | SAF, share intent, malformed and Unicode-path fixtures | In progress — SAF picker (`ActivityResultContracts.OpenDocument`), `ACTION_VIEW`/`ACTION_SEND` intent handling, and persistable-permission grant implemented; malformed/encrypted/Unicode-filename fixture tests not yet added. JVM test `openDocumentOpensAContentUriSource` (`./gradlew testDebugUnitTest`) passes against a fake engine; real content-Uri behavior not verified on a real device (see PDF-002 note). |
| PDF-002 | Reader/navigation | `app/src/main/java/com/pdfreader/app/reader`, `app/src/main/java/com/pdfreader/app/pdfengine` | Render, zoom, navigation, lifecycle and memory tests | In progress — open/render, next/previous/jump page navigation, pinch-zoom/pan, thumbnail strip, and a continuous-scroll layout toggle are implemented. Fit-width/page auto-sizing, outline/bookmark navigation and process-death state restoration not yet implemented. JVM tests (`PdfReaderViewModelSmokeTest`, 7 tests, `./gradlew testDebugUnitTest`) pass against a fake engine; real-device verification of `PdfRenderInstrumentedTest` still required before `Verified`. |
| PDF-003 | Search/OCR scope | `feature:search`, `core:pdfengine` | Search known text; OCR only if approved | **Blocked** — `android.graphics.pdf.PdfRenderer` (the only PDF engine wired up so far) exposes no text-extraction or search API at all; it only rasterizes pages to bitmaps. This is the same PDF-engine-selection gate noted in `docs/ARCHITECTURE.md` §2 for annotations/signing (see planning/ADR.md ADR-002) — text search cannot be implemented, even partially, until a text-capable engine is selected. Not attempted; no stub or fake search was added. |
| PDF-004 | Library/metadata | `app/src/main/java/com/pdfreader/app/data/recents`, `app/src/main/java/com/pdfreader/app/data/documents` | Recent files, clear history, outline/bookmarks | In progress — Room-backed recent-documents list (upsert on open, clear history, per-item delete) and a document-properties dialog (name/page count/size via `ContentResolver`) are implemented for SAF/share-intent opens (the bundled fixture is deliberately excluded from recents). Outline/bookmark navigation not implemented (depends on the same PDF-003 engine gate — `PdfRenderer` exposes no outline API either). JVM test `openDocumentRecordsARecentEntryButTheFixtureDoesNot` (`./gradlew testDebugUnitTest`) passes against an in-memory Room DB and a fake engine. |
| PDF-005 | Annotations | `feature:annotations`, `core:pdfengine` | Edit/undo/save/reopen round trip | Planned |
| PDF-006 | Page operations/merge | `feature:pageops`, `core:pdfengine` | Order/count, cancellation and source preservation | Planned |
| PDF-007 | Visual signature | `feature:visualsignature`, `core:security` | Placement, persistence, delete and clear labeling | Planned |
| PDF-008 | Digital signature | `feature:digitalsignature`, `core:pdfengine` | Sign, validate, tamper and certificate edge cases | Planned |
| PDF-009 | Forms/attachments | `feature:forms`, `core:pdfengine` | Form save/reopen and explicit attachment action | Planned |
| PDF-010 | Save/export/print | `data:documents`, platform adapters | Overwrite, disk-full, permission and reopen tests | Planned |
| PDF-011 | Cloud sync | `data:cloud` | Offline, retry, conflict, delete and sync tests | Planned |
| PDF-012 | UX/accessibility/reliability | UI modules | Compose UI, screen reader, lifecycle and large-file tests | Planned |
| PDF-013 | Passwords/permissions | `core:security`, `core:pdfengine` | Incorrect password, restricted file and no-secret-logging tests | Planned |
| SEC-001 | Malicious PDF safety | `core:security`, `core:pdfengine` | Malformed corpus, limits and safe action tests | Planned |
| SEC-002 | Cloud authorization | `data:cloud`, backend rules | Emulator tests deny cross-user access | Planned |
| SEC-003 | Key/signature safety | `core:security`, `feature:digitalsignature` | Key lifecycle and log inspection | Planned |
| SEC-004 | Privacy/consent | app shell, `data:cloud` | Consent, no unexpected upload and deletion tests | Planned |
| SEC-005 | Permanent redaction (if approved) | annotation/PDF adapter | Text/object recovery tests | Out of scope until approved |

## Status values
- `Planned`: not implemented or not verified.
- `In progress`: implementation underway.
- `Verified`: implementation exists and tests passed; add actual test IDs/commands.
- `Blocked`: dependency or decision prevents progress.
- `Out of scope`: explicit decision, not accidental omission.
