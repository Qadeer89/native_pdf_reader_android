# Decisions and Open Questions

## Confirmed
- Platform: native Android phone/tablet application.
- Repository: new greenfield project.
- Signing: visual signatures and certificate-based digital signatures are both in scope.
- Cloud: account and optional document/metadata sync are in scope; uploads must be explicit/consented and private by default.
- Proposed stack: Kotlin, Jetpack Compose, AndroidX architecture components, coroutines/Flow, Room, DataStore, WorkManager, SAF, Android Keystore; Firebase Auth/Cloud Storage/Firestore is a candidate cloud stack.

## Required decisions before production implementation
- [ ] Minimum supported Android API level and target SDK policy.
- [ ] PDF engine: Android `PdfRenderer` for baseline rendering versus a maintained commercial SDK or vetted open-source combination for editing/signing.
- [ ] Commercial SDK budget and licensing model.
- [ ] Exact digital-signature profile requirements: certificate formats, PAdES level, timestamps, revocation checks, trust roots, hardware-backed/remote signing.
- [ ] Cloud provider, deployment region, data residency, retention and backup/deletion policy.
- [ ] Is end-to-end encryption required, or is TLS + provider encryption at rest acceptable for MVP? Do not describe the latter as E2EE.
- [ ] Cloud sharing/collaboration scope and roles.
- [ ] OCR and permanent redaction: MVP, later or excluded.
- [ ] App name, applicationId/package name, distribution channel and privacy contact.
- [ ] Recovery expectations for visual signatures and digital-signing keys across device changes/reinstalls.

## Decision log template
### DEC-XXX — [Decision title]
- Date:
- Status: Proposed / Approved / Rejected / Superseded
- Owner:
- Context:
- Decision:
- Alternatives:
- Rationale:
- Security/privacy impact:
- Licensing/cost impact:
- Consequences and follow-up tasks:

## Assumption policy
Do not silently lock a PDF SDK, promise E2EE, claim certificate signatures are trusted, or claim secure redaction. Present options with evidence and request approval where the decision materially affects cost, security, licensing or product behavior.
