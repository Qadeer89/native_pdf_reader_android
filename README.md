# Native Android PDF Reader — Master Prompt Pack

This pack defines a **new native Android application** with feature-rich PDF viewing, annotation, merge/page operations, visual signatures, certificate-based digital signatures, and optional cloud sync.

## Start here
1. Read [`MASTER_PROMPT.md`](MASTER_PROMPT.md).
2. Follow the linked documents in [`docs/`](docs/).
3. Keep every document synchronized when a requirement or architecture decision changes.
4. The recommended Android app stack is specified in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). PDF-engine and cryptographic-library selection remain a gated procurement/technical decision until a proof of concept and license/security review pass.

## Document map

| File | Purpose |
|---|---|
| [`MASTER_PROMPT.md`](MASTER_PROMPT.md) | Main instructions for the coding agent |
| [`docs/PRODUCT_REQUIREMENTS.md`](docs/PRODUCT_REQUIREMENTS.md) | Product scope and acceptance criteria |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | Android stack, modules, data flow, cloud architecture |
| [`docs/SECURITY_PRIVACY.md`](docs/SECURITY_PRIVACY.md) | Threat model, cloud controls, keys, signing and privacy |
| [`docs/TEST_STRATEGY.md`](docs/TEST_STRATEGY.md) | Unit, integration, UI, security, sync and signing tests |
| [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md) | Phased delivery and release gates |
| [`docs/TRACEABILITY_MATRIX.md`](docs/TRACEABILITY_MATRIX.md) | Requirement-to-module-to-test mapping |
| [`docs/DECISIONS_AND_OPEN_QUESTIONS.md`](docs/DECISIONS_AND_OPEN_QUESTIONS.md) | Recorded decisions and remaining approvals |

## Confirmed project decisions
- **Platform:** Native Android phone/tablet app.
- **Repository:** New greenfield project.
- **Signing:** Both visual signatures and certificate-based cryptographic digital signatures.
- **Cloud:** Cloud account/sync capability is in scope; document upload must be explicit and privacy controls are mandatory.
- **Suggested baseline:** Kotlin, Jetpack Compose, AndroidX architecture components, coroutines/Flow, Room, DataStore, WorkManager, Android Storage Access Framework and Android Keystore.
- **Important:** “Both signatures” means two separately designed and tested workflows. A visual signature image is not a cryptographic digital signature.
