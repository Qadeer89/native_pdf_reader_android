# Security and Privacy — Android + Cloud

## 1. Threat model
Protect private PDFs, metadata, signatures and account data from malicious PDFs, unsafe attachments/links, cross-account cloud access, leaked credentials, compromised dependencies, insecure caches/temp files, unauthorized sharing and sync conflicts.

Treat every imported PDF, image, metadata field, filename, link, form value and attachment as untrusted.

## 2. Android security baseline
- Use least privilege and Android Storage Access Framework; avoid broad storage permissions unless a reviewed feature truly requires them.
- Use Android Keystore for app-managed keys when suitable. Verify whether secure hardware is available; never assume all devices offer equivalent backing.
- Store auth/session material using supported SDK mechanisms; never hard-code secrets or ship service-account/admin credentials in the app.
- Keep exported components/deep links narrowly configured; validate incoming intents and content URIs.
- Disable cleartext traffic; use TLS for network communication and configure network security intentionally.
- Keep document processing off the UI thread. For untrusted PDFs, evaluate process isolation or SDK-provided sandboxing where feasible.
- Do not execute embedded PDF JavaScript or launch links/attachments automatically.
- Avoid logging document text, passwords, tokens, signature images, private keys or sensitive file paths.

## 3. Cloud authorization and privacy
- Private by default. Every object and metadata record must be bound to an authenticated owner/tenant.
- Enforce authorization server-side using Firebase Security Rules or equivalent backend authorization; UI hiding is not security.
- Write deny-by-default rules for Firestore and Cloud Storage, scoped to `request.auth.uid` or a verified membership model.
- Do not use broad “any authenticated user can read/write” rules for production user documents.
- Validate upload size, content type as a hint, path, ownership and allowed metadata; parse/inspect PDFs in a controlled environment.
- Test rules using Firebase Emulator Suite with multiple users, unauthenticated requests, path traversal attempts, forged metadata and cross-user reads/writes.
- Avoid public object URLs. Use authenticated SDK access or short-lived, scoped access mechanisms where appropriate.
- Separate document bytes from metadata. Minimize metadata; do not store passwords or private keys in Firestore.
- Define cloud region, retention, backup, deletion, incident response, account deletion and support access policy before launch.
- Explain cloud data handling before first upload. User consent must be explicit and revocable.
- Cloud encryption at rest and TLS are not the same as end-to-end encryption. Never claim E2EE unless client-side encryption, key recovery, sharing and multi-device flows are implemented and reviewed.

## 4. Local files and temporary data
- Use app-private temp locations with restrictive permissions and unpredictable names.
- Protect cache/thumbnail files; define eviction and cleanup behavior.
- Write outputs to temporary files, validate/reopen, then atomically commit where supported.
- Preserve source file on failures and cancellation. Never overwrite silently.
- Clean stale temporary files at startup; don't promise forensic secure deletion where the filesystem cannot guarantee it.

## 5. Visual and digital signatures
- Treat imported/drawn signature images as sensitive personal data. Store locally by default, protect at rest where feasible, offer deletion, and do not sync them by default.
- Clearly label visual signatures as appearances only.
- Certificate-based signing must use a vetted library. No custom crypto, homemade CMS/PAdES implementation, or hand-rolled certificate validation.
- Keep private keys out of logs, app preferences, cloud document storage and analytics.
- Android Keystore can protect app-generated keys, but does not automatically solve importing a user's existing PDF-signing certificate, PKCS#12 handling, cloud signing or cross-device signing. Design and test those separately.
- Verify signature byte ranges, digest, certificate chain/trust, expiry and tampering. State limitations for revocation, timestamp and trust-anchor checks.
- Never upload a private key or certificate bundle without a separately designed, explicitly consented and reviewed flow.
- Signing keys may be device-bound; provide clear behavior if a user changes devices or reinstalls the app.

## 6. PDF-specific safeguards
- Bound page count, image dimensions, decompression, recursion, CPU and memory where the engine permits.
- Handle malformed PDFs and unsupported encryption safely.
- Require user gesture to open external links; block or warn on dangerous URI schemes.
- Never auto-open attachments or execute embedded actions.
- Permanent redaction, if later approved, must remove underlying content/objects and be verified by extraction/search/recovery tests. A visual overlay is not redaction.

## 7. Cloud sync correctness
- Use stable document IDs, version identifiers/checksums and idempotent uploads.
- Never silently overwrite a newer remote version; show conflict UI or provide a documented safe merge strategy.
- Retry transient failures with bounded exponential backoff and cancellation.
- On sign-out, stop scheduled work and clear in-memory/session state; local-file deletion behavior must be clearly disclosed.
- Deletion must cover metadata, object, versions and relevant backups according to the stated retention policy.

## 8. Security verification and release blockers
- Dependency vulnerability and license scans.
- PDF corpus/fuzz testing where practical.
- Cloud Rules emulator tests, including cross-user access denial.
- Inspect logs and crash reports for sensitive data.
- Test network loss, expired auth, upload cancellation, stale versions and account deletion.
- No release with known critical/high exploitable issues without an approved mitigation, insecure cloud rules, leaked secrets, unexpected uploads, silent overwrite or false claims about signature/redaction security.
