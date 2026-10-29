# Requirement Traceability Matrix

Update module paths and test IDs when implementation begins. No requirement may be marked `Verified` without real test evidence.

| ID | Area | Expected module(s) | Minimum verification | Status |
|---|---|---|---|---|
| PDF-001 | Open/import | `data:documents`, `feature:reader` | SAF, share intent, malformed and Unicode-path fixtures | Planned |
| PDF-002 | Reader/navigation | `feature:reader`, `core:pdfengine` | Render, zoom, navigation, lifecycle and memory tests | Planned |
| PDF-003 | Search/OCR scope | `feature:search`, `core:pdfengine` | Search known text; OCR only if approved | Planned |
| PDF-004 | Library/metadata | `data:documents` | Recent files, clear history, outline/bookmarks | Planned |
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
