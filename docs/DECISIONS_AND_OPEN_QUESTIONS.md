# Decisions and Open Questions

## Confirmed
- Platform: native Android phone/tablet application.
- Repository: new greenfield project.
- Signing: visual signatures and certificate-based digital signatures are both in scope.
- Cloud: account and optional document/metadata sync are in scope; uploads must be explicit/consented and private by default.
- Proposed stack: Kotlin, Jetpack Compose, AndroidX architecture components, coroutines/Flow, Room, DataStore, WorkManager, SAF, Android Keystore; Firebase Auth/Cloud Storage/Firestore is a candidate cloud stack.

## Required decisions before production implementation
- [ ] Minimum supported Android API level and target SDK policy.
- [x] PDF engine for annotations/page-ops/forms: **Approved 2026-10-30** — PDFBox-Android. See DEC-001.
- [ ] Commercial SDK budget and licensing model — no longer relevant unless DEC-001 is revisited (e.g. the maintenance-staleness risk materializes).
- [ ] Exact digital-signature profile requirements: certificate formats, PAdES level, timestamps, revocation checks, trust roots, hardware-backed/remote signing.
- [ ] Cloud provider, deployment region, data residency, retention and backup/deletion policy.
- [ ] Is end-to-end encryption required, or is TLS + provider encryption at rest acceptable for MVP? Do not describe the latter as E2EE.
- [ ] Cloud sharing/collaboration scope and roles.
- [ ] OCR and permanent redaction: MVP, later or excluded.
- [ ] App name, applicationId/package name, distribution channel and privacy contact.
- [ ] Recovery expectations for visual signatures and digital-signing keys across device changes/reinstalls.

## Decision log

### DEC-001 — PDF engine for annotations/page-operations (Phase 3 gate)
- Date: 2026-10-30
- Status: **Approved 2026-10-30** by the user, after reviewing the POC evidence below. PDFBox-Android is cleared to move from `testImplementation` to a real production dependency and back PDF-005/PDF-006.
- Owner: User (approved directly).
- Context: `docs/ARCHITECTURE.md` §2 requires a PDF-engine decision, backed by a working proof of concept, before PDF-005 (annotations), PDF-006 (page operations/merge), or PDF-009 (forms) can be implemented. The platform `android.graphics.pdf.PdfRenderer` already in use for PDF-002 (baseline rendering) has **no** API for annotations, page mutation, merging, or text/encryption handling — confirmed by its API surface, not an assumption.
- Candidates evaluated:
  1. **PDFBox-Android** (`com.tom-roush:pdfbox-android`, a direct Android port of Apache PDFBox) — **Apache License 2.0**. Actionable immediately: no procurement, no trial signup, no legal review beyond standard OSS attribution (already required practice for this project regardless).
  2. **Apryse** (formerly PDFTron) — commercial, quote-only pricing (no public price list; third-party sources mention a "starting as low as $1,500" custom package, unverified against Apryse's own materials). Has annotation + digital-signature packages. Requires sales engagement and a trial-key signup this agent cannot perform (account creation / commercial engagement on the user's behalf is outside what this agent does without explicit, in-the-moment direction).
  3. **iText** — dual-licensed AGPLv3 / commercial. AGPL requires disclosing this app's full source code (incompatible with a closed-source app per `docs/ARCHITECTURE.md` §2's own warning); commercial pricing is quote-only, with a third-party blog citing "$1,800+ per developer" as an unverified estimate. Also requires a business decision, not a technical one.
  - Sources: [TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android), Maven Central listing (`com.tom-roush:pdfbox-android`, latest `2.0.27.0`), [iText license models](https://itextpdf.com/how-buy), [iText EULA](https://itextpdf.com/end-user-license-agreement), [Apryse pricing guide (third-party)](https://verdocs.com/blog/apryse-pricing).
- **Technical POC performed and passing** (`app/src/test/java/com/pdfreader/app/poc/PdfBoxAndroidEnginePocTest.kt`, 3/3 tests pass under `./gradlew testDebugUnitTest`, Robolectric/JVM — no device needed since PDFBox-Android's document model is largely pure-JVM, unlike `PdfRenderer`):
  - **Annotate / save / reopen round trip**: added a `PDAnnotationText` to the bundled fixture, saved, reopened, confirmed the annotation and its contents survived.
  - **Merge**: merged two copies of the fixture via `PDFMergerUtility`, confirmed the output has the expected combined page count (2).
  - **Encrypted PDF handling**: encrypted the fixture with `StandardProtectionPolicy` (owner/user passwords, 128-bit), confirmed opening with the wrong password throws `InvalidPasswordException` and opening with the correct password succeeds and reports `isEncrypted = true`.
  - **Not yet covered by this POC pass** (tracked in `planning/IMPLEMENTATION_BACKLOG.md` Phase 3/4): visual signature placement, certificate-based signing/validation/tamper-detection (PDFBox supports PDF signing via an external crypto provider — typically BouncyCastle — which would need its own vetting pass per `docs/SECURITY_PRIVACY.md` §5 "no custom crypto, no homemade CMS/PAdES implementation"), and real-device memory/performance measurement.
- **Maintenance risk — the one real caveat**: PDFBox-Android's latest release is **2.0.27.0, dated 2023-01-02** — no release in roughly the last ~3.5 years as of this decision's date. The repository shows 113 open issues and 12 open PRs, with no archive notice found. The underlying upstream Apache PDFBox (Java) project continues to release, but the Android port specifically has gone quiet. This is a real signal to weigh against "it's free and it works" — a security advisory in upstream PDFBox might not get ported promptly, if at all.
- Rationale: Of the three candidates, only PDFBox-Android was something this agent could actually integrate, run, and verify in this environment without a business decision (budget, sales contact, legal sign-off) blocking the step. It passed every POC scenario attempted. It is not automatically "selected" — see Assumption policy — but it is now evidence-backed rather than speculative, which is what `docs/ARCHITECTURE.md` §2 asks for.
- Licensing/cost impact: Apache 2.0 — permissive, compatible with closed-source distribution, standard NOTICE/attribution obligations only (to be captured in a release-time license-notices file, not yet created). Zero direct cost. The commercial alternatives were not actionable for the reason above, not because they were technically rejected.
- Security/privacy impact: Still needs the fuzz/corpus testing and malformed-PDF handling pass from `docs/SECURITY_PRIVACY.md` §6 before this engine processes untrusted user documents in production; this POC only used the project's own hand-built fixture.
- Consequences and follow-up tasks (now underway): (1) promote the dependency from `testImplementation` to `implementation` and design the annotation/page-ops adapter (`PdfEditEngine`) — done in the commit that follows this entry; (2) run a malformed-PDF corpus against it — not yet done, tracked in `planning/IMPLEMENTATION_BACKLOG.md`; (3) evaluate the signing path (BouncyCastle) separately before PDF-008 — not started; (4) the maintenance-staleness risk is accepted for now (re-evaluate if a security advisory surfaces in upstream PDFBox that the Android port doesn't pick up).

### Template for future entries
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
