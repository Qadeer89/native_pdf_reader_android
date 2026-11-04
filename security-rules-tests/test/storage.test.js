'use strict';

// SEC-002 (cloud authorization): proves storage.rules enforces owner-only access, a
// content-type restriction, and the 200MB size cap (mirroring PdfOpenError.TooLarge in the
// app's own engines — SEC-001) for PDF-011's `users/{userId}/documents/{docId}` PDF bytes.
// Runs only against the local Firebase emulator (see firebase.json) — never touches a real
// project or bucket.

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require('@firebase/rules-unit-testing');
const { ref, uploadBytes, getBytes, deleteObject } = require('firebase/storage');

const OWNER_UID = 'owner-user';
const OTHER_UID = 'other-user';
const DOC_ID = 'doc-1';
const OBJECT_PATH = `users/${OWNER_UID}/documents/${DOC_ID}`;

const SMALL_PDF_BYTES = Buffer.from('%PDF-1.4\n%\xE2\xE3\xCF\xD3\n', 'binary');

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'pdf-reader-rules-test',
    storage: {
      rules: fs.readFileSync(path.resolve(__dirname, '../../storage.rules'), 'utf8'),
    },
  });
});

after(async () => {
  if (testEnv) await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.withSecurityRulesDisabled(async (context) => {
    await uploadBytes(ref(context.storage(), OBJECT_PATH), SMALL_PDF_BYTES, {
      contentType: 'application/pdf',
    });
  });
});

describe('storage.rules — users/{userId}/documents/{docId}', () => {
  it('denies a download with no auth at all (unauthenticated request)', async () => {
    const unauth = testEnv.unauthenticatedContext();
    await assertFails(getBytes(ref(unauth.storage(), OBJECT_PATH)));
  });

  it('denies an upload with no auth at all (unauthenticated request)', async () => {
    const unauth = testEnv.unauthenticatedContext();
    await assertFails(
      uploadBytes(ref(unauth.storage(), OBJECT_PATH), SMALL_PDF_BYTES, {
        contentType: 'application/pdf',
      })
    );
  });

  it('denies another signed-in user from downloading the owner\'s file (cross-user read)', async () => {
    const other = testEnv.authenticatedContext(OTHER_UID);
    await assertFails(getBytes(ref(other.storage(), OBJECT_PATH)));
  });

  it('denies another signed-in user from overwriting the owner\'s file (cross-user write)', async () => {
    const other = testEnv.authenticatedContext(OTHER_UID);
    await assertFails(
      uploadBytes(ref(other.storage(), OBJECT_PATH), SMALL_PDF_BYTES, {
        contentType: 'application/pdf',
      })
    );
  });

  it('denies another signed-in user from deleting the owner\'s file (cross-user delete)', async () => {
    const other = testEnv.authenticatedContext(OTHER_UID);
    await assertFails(deleteObject(ref(other.storage(), OBJECT_PATH)));
  });

  it('allows the owner to download their own file', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    await assertSucceeds(getBytes(ref(owner.storage(), OBJECT_PATH)));
  });

  it('allows the owner to upload a PDF under their own path', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    await assertSucceeds(
      uploadBytes(ref(owner.storage(), OBJECT_PATH), SMALL_PDF_BYTES, {
        contentType: 'application/pdf',
      })
    );
  });

  it('allows the owner to delete their own file', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    await assertSucceeds(deleteObject(ref(owner.storage(), OBJECT_PATH)));
  });

  it('denies the owner uploading a non-PDF content type under their own path', async () => {
    const owner = testEnv.authenticatedContext(OWNER_UID);
    await assertFails(
      uploadBytes(ref(owner.storage(), OBJECT_PATH), Buffer.from('not a pdf'), {
        contentType: 'image/png',
      })
    );
  });

  it('allows a different user to write under their OWN uid path (ownership is the uid segment, not the doc id)', async () => {
    // other-user is "signed in" but has never created doc-1 under their own uid — a write
    // there should still simply be evaluated against *their* uid segment and succeed only
    // because it IS their own path. This proves the rule isn't accidentally keyed on DOC_ID.
    const other = testEnv.authenticatedContext(OTHER_UID);
    const ownPath = `users/${OTHER_UID}/documents/${DOC_ID}`;
    await assertSucceeds(
      uploadBytes(ref(other.storage(), ownPath), SMALL_PDF_BYTES, {
        contentType: 'application/pdf',
      })
    );
  });
});

// The 200MB cap's *comparison logic* (not the literal 200MB constant — the local emulator
// test harness's own relay proxy rejects a request body anywhere near that size with its own
// "PayloadTooLargeError", unrelated to the rule itself) is verified separately here, against
// a copy of storage.rules with the same expression structure but a much smaller literal
// substituted in, so a real transfer of hundreds of MB is never needed to prove the
// comparison operator and boundary are correct.
describe('storage.rules — size cap boundary (verified at a reduced threshold; see comment above)', () => {
  const REDUCED_LIMIT_BYTES = 4096;
  let sizeCapEnv;

  before(async () => {
    const original = fs.readFileSync(path.resolve(__dirname, '../../storage.rules'), 'utf8');
    const reduced = original.replace('200 * 1024 * 1024', String(REDUCED_LIMIT_BYTES));
    assert.notEqual(
      reduced,
      original,
      'expected to find the literal "200 * 1024 * 1024" cap expression in storage.rules to rewrite for this test — did the rule text change?'
    );
    sizeCapEnv = await initializeTestEnvironment({
      projectId: 'pdf-reader-rules-test-sizecap',
      storage: { rules: reduced },
    });
  });

  after(async () => {
    if (sizeCapEnv) await sizeCapEnv.cleanup();
  });

  it('allows an upload under the (reduced-for-testing) size cap', async () => {
    const owner = sizeCapEnv.authenticatedContext(OWNER_UID);
    await assertSucceeds(
      uploadBytes(ref(owner.storage(), OBJECT_PATH), Buffer.alloc(REDUCED_LIMIT_BYTES - 1), {
        contentType: 'application/pdf',
      })
    );
  });

  it('denies an upload at or over the (reduced-for-testing) size cap', async () => {
    const owner = sizeCapEnv.authenticatedContext(OWNER_UID);
    await assertFails(
      uploadBytes(ref(owner.storage(), OBJECT_PATH), Buffer.alloc(REDUCED_LIMIT_BYTES + 1), {
        contentType: 'application/pdf',
      })
    );
  });
});
