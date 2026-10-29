# Product Requirements — Native Android PDF Reader

## Product principles
- Native Android UX for phones and tablets; adapt to landscape, large screens and foldables.
- Local-first editing with optional, user-controlled cloud sync.
- Preserve originals by default; no silent overwrites.
- Distinguish visual signatures from certificate-based digital signatures in wording and UI.
- Cloud documents are private by default and owner-scoped. Sharing must be explicit, revocable and auditable.

## PDF-001 — Import/open
- Open using Android Storage Access Framework (system document picker), share intent and supported app file associations.
- Avoid broad storage permissions when scoped access is sufficient.
- Handle Unicode filenames, large files, malformed PDFs, encrypted files and interrupted reads.
- Treat extension/MIME type as hints, not proof of safe content.
- Never execute embedded JavaScript or launch PDF actions automatically.

## PDF-002 — Reader and navigation
- Render pages, continuous/single-page layouts, fit width/page, zoom and page jump.
- Thumbnails, page count, rotation, full-screen mode and outline/bookmark navigation.
- Handle lifecycle changes and memory pressure without losing the user's position or edits.

## PDF-003 — Search and text
- Search text, navigate matches and show result count; support case/whole-word options if engine permits.
- Text selection/copy where supported and permitted.
- OCR for image-only PDFs is a separately gated feature with language selection, progress, cancellation and accuracy caveats.

## PDF-004 — Document library
- Recent documents, clear history, bookmarks/favorites and document properties.
- Persist only necessary local metadata.
- Sync metadata only after user consent; allow users to see and delete cloud copies.

## PDF-005 — Annotations
- Highlight, underline, strike-through, freehand pen, eraser, notes, text boxes/callouts and basic shapes.
- Color, opacity and stroke controls; select/move/edit/resize/delete.
- Undo/redo; save/reopen round trip; maintain geometry across zoom and page rotation.
- Flattening is a separate, clearly labeled operation and can reduce editability.

## PDF-006 — Page operations
- Rotate, reorder, extract, delete, insert and merge PDFs.
- Merge flow: choose multiple files, reorder inputs, select page ranges if supported, preview, select output, show progress and permit safe cancellation.
- Do not silently overwrite inputs. Write to temporary output, validate, then commit.
- Preserve page size/orientation, links, annotations, forms and metadata to the extent verified for the selected engine; document losses.

## PDF-007 — Visual signatures
- Draw, type or import an image; place, move, resize and remove before saving.
- Optional date/name labels only with user action.
- Reusable signature material must be private, protected at rest where feasible, deletable, and excluded from cloud sync by default.
- Display a clear explanation: a visual signature image alone does not prove signer identity or document integrity.

## PDF-008 — Certificate-based digital signatures
- Sign PDF byte ranges using a vetted, compatible PDF/cryptography library; no home-grown cryptography.
- Support a clearly documented certificate/key import or selection workflow.
- Validate signed PDFs and detect post-signing changes/tampering.
- Explicitly report signature validity, signer/certificate identity, trust-chain status, expiry and any unsupported revocation/timestamp checks.
- Never claim a signature is trusted merely because cryptographic verification succeeds.
- Support for remote signing or hardware-backed keys requires a separate design and proof of concept.
- Signing can invalidate or constrain later edits; warn users and test signature-preserving workflows.

## PDF-009 — Forms, links and attachments
- Support common AcroForm fields if engine allows; save/reopen values.
- Safely list attachments; export only after user gesture.
- Show links and require user action to open; block or warn on dangerous schemes.
- Document unsupported XFA or other formats honestly.

## PDF-010 — Save, export and print
- Save, Save As, export pages and Android print/share flows as supported.
- Confirm overwrites, handle permission denial, low disk space and cancellation.
- Verify output by reopening and checking expected page count/structure.

## PDF-011 — Cloud accounts and synchronization
- Account sign-in and sign-out; clear account/session data on sign-out as documented.
- Upload/download selected PDFs and sync metadata with visible status, retry and cancellation.
- Owner-scoped access by default; explicit share permissions, expiry/revocation if sharing is implemented.
- Handle offline edits and concurrent edits with explicit conflict resolution. Never silently replace a newer cloud version.
- Show which files are local, uploading, synced, conflicted or failed.
- Provide delete-from-cloud and account/data deletion flows consistent with the retention policy.
- Explain cloud storage, processing region/retention and what data is transmitted before first upload.
- End-to-end encryption is not assumed by default; if promised, implement and independently review client-side key management, recovery and sharing before making the claim.

## PDF-012 — UX, accessibility and reliability
- Native Android menus, system back behavior, keyboard support where available, accessibility semantics, screen-reader labels and scalable text.
- Localized strings; no scattered hard-coded user-facing text.
- Expensive operations run off main thread; progress/cancellation where safe.
- Recover from process death, low memory, disk full, network loss and interrupted uploads.

## PDF-013 — Passwords and permissions
- Prompt only when needed; never log or persist passwords in plaintext.
- Respect supported encryption and permissions; do not bypass access controls.
- State clearly when engine/platform cannot open a specific encryption mode.

## Advanced features — separate approval
Permanent redaction, OCR, PDF comparison, compression, AI processing, collaboration, remote signing, and enterprise identity features require their own threat model, licensing, privacy disclosure, acceptance criteria and tests.

## Global acceptance
- Core flows operate on real fixture PDFs.
- Save/reopen and merge round trips preserve expected content.
- Cloud rules prevent cross-user reads/writes and tests prove it.
- Offline mode remains usable for locally available documents.
- Visual signatures and cryptographic signatures are never conflated.
- No silent overwrite, upload or cloud sharing.
