# SEC-002 — Firestore/Storage rules tests

Proves `../firestore.rules` and `../storage.rules` deny cross-user and unauthenticated
access to PDF-011's cloud-sync data, per `docs/SECURITY_PRIVACY.md` ("Test rules using
Firebase Emulator Suite with multiple users, unauthenticated requests, ... and cross-user
reads/writes") and `docs/PRODUCT_REQUIREMENTS.md` PDF-011 ("Owner-scoped access by
default"). This is a Node project, not an Android/Gradle module — it never touches a real
Firebase project.

## Run locally

Requires Node.js and a **JDK 21+** on `PATH`/`JAVA_HOME` (the Firestore/Storage emulators
in Firebase CLI 15+ refuse to start on JDK 17 or older — unrelated to the Android app
itself, which still builds with JDK 17).

```bash
npm install
cd ..
npx --yes firebase-tools@latest emulators:exec --only firestore,storage --project demo-pdf-reader "npm --prefix security-rules-tests test"
```

Run from the repository root (not this directory) so `firebase.json` is found.

## What's covered

- `test/firestore.test.js` — `users/{userId}/documents/{docId}` metadata documents: denies
  unauthenticated and cross-user read/write/delete; allows the owner full access; denies
  access to an unrelated top-level collection (default-deny catch-all).
- `test/storage.test.js` — `users/{userId}/documents/{docId}` PDF bytes: the same
  unauthenticated/cross-user/owner matrix, plus a content-type restriction
  (`application/pdf` only) and the 200MB size cap that mirrors `PdfOpenError.TooLarge` in
  the app's own engines (SEC-001). The size cap's *comparison logic* is verified at a much
  smaller substituted threshold rather than literally transferring 200MB+ through the local
  test harness, which has its own, unrelated request-size limit — see the comment in
  `test/storage.test.js`.

Runs automatically in CI on every push/PR (`.github/workflows/ci.yml`,
`security-rules-tests` job).
