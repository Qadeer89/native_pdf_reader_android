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
- [x] Exact digital-signature profile requirements: certificate formats, PAdES level, timestamps, revocation checks, trust roots, hardware-backed/remote signing. **Approved 2026-10-30 — see DEC-002.**
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

### DEC-002 — Digital-signature profile for PDF-008 (certificate-based signing)
- Date: 2026-10-30
- Status: **Approved 2026-10-30** by the user. A technical POC (`app/src/test/java/com/pdfreader/app/poc/PdfSignaturePocTest.kt`) has since confirmed the mechanics: PDFBox-Android's external-signing hook + BouncyCastle's CMS API sign a fixture, the signature verifies against the actual signed content, and a single tampered byte is correctly detected (1/1 test passing under `./gradlew testDebugUnitTest`). This profile is cleared to move from POC to a real, UI-backed implementation (PDF-008).
- Owner: User (decision requested).
- Context: `docs/ARCHITECTURE.md`/this file gate PDF-008 behind "exact digital-signature profile requirements: certificate formats, PAdES level, timestamps, revocation checks, trust roots, hardware-backed/remote signing." `docs/SECURITY_PRIVACY.md` §5 additionally requires: a vetted crypto library (no custom CMS/PAdES/cert-validation code); verifying byte ranges/digest/chain/expiry/tamper; and explicitly *stating* any limitation on revocation, timestamp, or trust-anchor checking rather than silently skipping it.
- Research performed (web research, not a code POC — a POC is the next step only after this profile is approved):
  - **PAdES conformance levels** (ETSI EN 319 142-1): B-B (signature + cert only, fully offline) → B-T (+ RFC 3161 timestamp, needs a network call to a TSA) → B-LT (+ embedded OCSP/CRL revocation data, needs a responder) → B-LTA (+ periodic re-timestamping, needs ongoing server infrastructure). B-B is the only level achievable with zero new backend/third-party infrastructure. [Datalogics: PAdES Signature Levels Explained](https://www.datalogics.com/pades-signature-levels-explained).
  - **PDFBox-Android's signing hook was verified to actually exist in the version we depend on** (`pdfbox-android-2.0.27.0`) via `javap` against the real jar, not just documentation: `PDDocument.addSignature(PDSignature, SignatureInterface)`, `saveIncrementalForExternalSigning(OutputStream)` → `ExternalSigningSupport`, and `SignatureOptions` are all present. (A 9.5-year-old upstream GitHub issue claiming external signing was missing from this fork does not apply to this version — don't rely on that issue if it resurfaces in a search.) The documented desktop-PDFBox pattern — build the CMS/PKCS#7 `SignedData` via BouncyCastle's `CMSSignedDataGenerator` + `JcaContentSignerBuilder` + `JcaSignerInfoGeneratorBuilder` — is calling a vetted library's own documented CMS API, not hand-rolled ASN.1/CMS, satisfying the security doc's constraint. [PDFBox `CreateSignature` example Javadoc](https://pdfbox.apache.org/docs/2.0.11/javadocs/org/apache/pdfbox/examples/signature/CreateSignature.html).
  - **BouncyCastle 1.72** (already transitively on the classpath via PDFBox-Android — see `planning/DEPENDENCY_LICENSE_INVENTORY.md`): no CVE found that's exploitable for a CMS/PAdES-B-B signing use case. CVE-2023-33201 (LDAP injection, <1.74) only affects BC's LDAP `CertStore` for X.509 chain lookups, which this profile does not use (no LDAP directory lookups planned); CVE-2024-30172 (Ed25519) and CVE-2026-5598 (FrodoKEM) don't touch the RSA/CMS signing path proposed here. **Constraint to carry into implementation: do not introduce BC's `LDAPCertStoreHelper`.** [NVD CVE-2023-33201](https://nvd.nist.gov/vuln/detail/CVE-2023-33201).
  - **Certificate formats** — two distinct, non-overlapping features, not one decision: (1) **PKCS#12 import** ("sign with a certificate the user already has"): the standard JVM `KeyStore.getInstance("PKCS12")` provider can parse a `.p12`/`.pfx` file in-process on Android (this is ordinary `java.security`/JCA, not Android-Keystore-specific); the plan would be to decrypt it only in memory for the signing operation and never persist or export the raw key material, never log it, per the security doc. (2) **Android Keystore self-signed**: generating a new signing identity backed by hardware (private key never leaves the device, cannot be exported) — this is a "create a new identity on this device" path, not a "bring your own cert" path; it cannot import a user's existing external certificate. These should ship as two separate, clearly-labeled flows rather than one. *Caveat: the exact Android API level where PKCS12-into-AndroidKeystore import became available was not pinned down in this research pass — flagged as a gap to verify against official Android docs (not just secondary sources) before implementation, not asserted as fact here.*
  - **Timestamping (RFC 3161)**: implementing the protocol (HTTP POST to a TSA, parse the response) does not require picking a specific TSA provider now — "support B-T at all in v1" and "which TSA endpoint" are separable decisions, so the TSA choice itself can be deferred without blocking a B-B-only v1.
  - **Revocation (OCSP/CRL)**: materially more complex (network trust decisions, response parsing, offline fallback behavior) than timestamping, and the security doc explicitly permits shipping without it as long as the gap is disclosed to the user ("State limitations for revocation, timestamp and trust-anchor checks") rather than silently omitted or misrepresented.
- **Recommended profile (this is the proposal, not a decision made unilaterally):**

  | Capability | Proposed for v1 | Deferred |
  |---|---|---|
  | PAdES level | **B-B only** | B-T / B-LT / B-LTA |
  | Signing-key source | Android Keystore self-signed (new on-device identity) **and** PKCS#12 import (existing cert, decrypted in-memory only) — two separate flows | Remote/HSM/cloud signing |
  | Timestamp | None in v1 | RFC 3161 (specific TSA TBD) |
  | Revocation checking | Not implemented; UI must disclose this limitation wherever a signature's validity is shown | OCSP/CRL |
  | Trust roots | Verify chain/expiry/tamper against whatever root the certificate itself chains to (no app-curated trust-root list in v1); UI must label unverified/self-signed trust clearly | A curated or system trust-root policy |
  | Crypto library | BouncyCastle 1.72 (already present), via its documented CMS API only — no LDAP `CertStore` usage | — |
  | Cross-device recovery | Not addressed — a device-bound Keystore key is lost on reinstall/device change by design; a PKCS#12-imported key's recovery depends entirely on whether the user kept their own copy of the file. UI must disclose this. | A backup/recovery design (ties into the still-open cloud-sync questions) |

  This is the smallest slice that is still a real, standards-named PAdES level, uses only libraries already vetted/present (no new dependency, no new procurement), and needs zero new backend infrastructure, TSA account, or OCSP responder.
- Rationale: mirrors DEC-001's approach of presenting evidence-backed options for approval rather than deciding unilaterally (Assumption policy, below) — this touches security claims ("is this signature trustworthy") and product behavior (what a user can and can't do with a signing key) directly enough that it needs explicit sign-off before any code is written, per `docs/ARCHITECTURE.md` §2 and the Assumption policy.
- Security/privacy impact: if approved as-is, the app will **not** check certificate revocation or provide a long-term-validation story — both must be surfaced in the signing/verification UI as stated limitations, not hidden, per `docs/SECURITY_PRIVACY.md` §5 and §8 ("No release with... false claims about signature... security").
- Licensing/cost impact: none — BouncyCastle and PDFBox-Android are both already approved/present; no new paid service, TSA subscription, or SDK is introduced by this profile.
- Consequences and follow-up tasks: (1) **done** — the signing-engine POC passed (see Status above); a real finding from it: Robolectric transitively depends on `org.bouncycastle:bcprov-jdk18on`, a different artifact from (but the same Java package as) the `bcprov/bcpkix-jdk15to18:1.72` PDFBox-Android brings as a production dependency, and having both on the unit-test classpath caused `NoSuchFieldError` from mixed BC class versions — fixed by excluding the `jdk18on` variant from the `testImplementation(libs.robolectric)` dependency in `app/build.gradle.kts` (confirmed via `./gradlew :app:dependencies --configuration debugRuntimeClasspath` that only `jdk15to18` ever reaches the shipped app, so this was a test-classpath-only issue); (2) not started — design the PKCS#12-import flow and the Keystore-self-signed flow as explicitly separate, separately-tested features; (3) not started — write the "this app does not check revocation / has no long-term validation story" disclosure into whatever UI shows a signature's status; (4) not started — verify the exact Android API level for PKCS12-related APIs against official Android docs, not secondary sources, before implementation.

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
