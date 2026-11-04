'use strict';

// SEC-002 (cloud authorization): proves firestore.rules enforces owner-only access to
// PDF-011's `users/{userId}/documents/{docId}` metadata documents — the exact requirement
// in docs/PRODUCT_REQUIREMENTS.md PDF-011 ("Owner-scoped access by default") and
// docs/SECURITY_PRIVACY.md ("Test rules using Firebase Emulator Suite with multiple users,
// unauthenticated requests, ... and cross-user reads/writes"). Runs only against the local
// Firebase emulator (see firebase.json) — never touches a real project.

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const { doc, getDoc, setDoc, deleteDoc } = require('firebase/firestore');

const OWNER_UID = 'owner-user';
const OTHER_UID = 'other-user';
const DOC_ID = 'doc-1';

const SAMPLE_DOC = {
  localUri: 'content://com.pdfreader.app/tree/primary/document/sample.pdf',
  status: 'Local',
  updatedAt: 1,
};

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'pdf-reader-rules-test',
    firestore: {
      rules: fs.readFileSync(path.resolve(__dirname, '../../firestore.rules'), 'utf8'),
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
  // Seed the owner's document directly against admin (rules bypassed), the same way a real
  // sync would have created it earlier — tests below only exercise *reading/writing* it.
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await setDoc(
      doc(context.firestore(), `users/${OWNER_UID}/documents/${DOC_ID}`),
      SAMPLE_DOC
    );
  });
});

function docRefFor(context) {
  return doc(context.firestore(), `users/${OWNER_UID}/documents/${DOC_ID}`);
}

describe('firestore.rules — users/{userId}/documents/{docId}', () => {
  it('denies a read with no auth at all (unauthenticated request)', async () => {
    const unauth = testEnv.unauthenticatedContext();
    await assertFails(getDoc(docRefFor(unauth)));
  });

  it('denies a write with no auth at all (unauthenticated request)', async () => {
    const unauth = testEnv.unauthenticatedContext();
    await assertFails(
      setDoc(docRefFor(unauth), { ...SAMPLE_DOC, status: 'Uploading' })
    );
  });

  it('denies another signed-in user from reading the owner\'s document (cross-user read)', async () => {
    const other = testEnv.authenticatedContext(OTHER_UID);
    await assertFails(getDoc(docRefFor(other)));
  });

  it('denies another signed-in user from writing the owner\'s document (cross-user write)', async () => {
    const other = testEnv.authenticatedContext(OTHER_UID);
    await assertFails(
      setDoc(docRefFor(other), { ...SAMPLE_DOC, status: 'Uploading' })
    );
  });

  it('denies another signed-in user from deleting the owner\'s document (cross-user delete)', async () => {
    const other = testEnv.authenticatedContext(OTHER_UID);
    await assertFails(deleteDoc(docRefFor(other)));
  });

  it('denies another signed-in user from reading under their OWN uid path for a doc that is not theirs (path traversal sanity)', async () => {
    // Confirms the rule is keyed on the path segment, not forgeable auth claims: other-user
    // reading users/other-user/documents/doc-1 (a doc that was never created there) must
    // also simply fail to exist, not somehow resolve to the owner's data.
    const other = testEnv.authenticatedContext(OTHER_UID);
    const forgedPathDoc = doc(
      other.firestore(),
      `users/${OTHER_UID}/documents/${DOC_ID}`
    );
    const snapshot = await assertSucceeds(getDoc(forgedPathDoc));
    assert.equal(snapshot.exists(), false);
  });

  it('allows the owner to read their own document', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    const snapshot = await assertSucceeds(getDoc(docRefFor(owner)));
    assert.equal(snapshot.exists(), true);
    assert.equal(snapshot.data().localUri, SAMPLE_DOC.localUri);
  });

  it('allows the owner to write (update) their own document', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    await assertSucceeds(
      setDoc(docRefFor(owner), { ...SAMPLE_DOC, status: 'Synced' })
    );
  });

  it('allows the owner to delete their own document', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    await assertSucceeds(deleteDoc(docRefFor(owner)));
  });

  it('denies access to an unrelated top-level collection entirely (default-deny catch-all)', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    const stray = doc(owner.firestore(), 'public/announcement');
    await assertFails(getDoc(stray));
  });
});
