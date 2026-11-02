package com.pdfreader.app.cloud

import kotlinx.coroutines.flow.Flow

/**
 * Per-document cloud upload/download/delete (PDF-011, DEC-003). Kept as an interface — same
 * pattern as [CloudAuthRepository] — so the real Firebase Storage/Firestore implementation
 * can be swapped in once `app/google-services.json` exists, without the rest of the app
 * (or its tests) depending on the Firebase SDK directly.
 *
 * [localUri] identifies the document on this device (the same string form used by
 * `com.pdfreader.app.data.recents.RecentDocumentsRepository`), never a cloud id — a
 * document has no cloud id at all until its first successful upload.
 */
interface CloudSyncRepository {
    /** Never completes — the caller observes status changes for as long as it collects. */
    fun observeSyncStatus(localUri: String): Flow<CloudSyncStatus>

    /**
     * Enqueues [localUri] for upload (offline-safe: queued even without a connection, per
     * `docs/PRODUCT_REQUIREMENTS.md` PDF-011 "handle offline edits... with explicit conflict
     * resolution"). Returns once queued, not once uploaded — watch [observeSyncStatus] for
     * the actual outcome.
     */
    suspend fun enqueueUpload(localUri: String): Result<Unit>

    /** No-ops if [localUri] isn't currently queued or uploading. */
    suspend fun cancelUpload(localUri: String)

    /** Deletes the cloud copy only — the local file is untouched. */
    suspend fun deleteFromCloud(localUri: String): Result<Unit>

    /** Deletes every cloud document and the account itself (PDF-011 "account/data deletion"). */
    suspend fun deleteAllAccountData(): Result<Unit>
}

/**
 * Placeholder used until `app/google-services.json` exists (see [NotConfiguredCloudAuthRepository]
 * for why) — reports every document as [CloudSyncStatus.Local] and fails every mutating call
 * with a clear reason rather than silently pretending to sync.
 */
class NotConfiguredCloudSyncRepository : CloudSyncRepository {
    override fun observeSyncStatus(localUri: String): Flow<CloudSyncStatus> =
        kotlinx.coroutines.flow.flowOf(CloudSyncStatus.Local)

    override suspend fun enqueueUpload(localUri: String): Result<Unit> = notConfiguredFailure()

    override suspend fun cancelUpload(localUri: String) = Unit

    override suspend fun deleteFromCloud(localUri: String): Result<Unit> = notConfiguredFailure()

    override suspend fun deleteAllAccountData(): Result<Unit> = notConfiguredFailure()

    private fun notConfiguredFailure(): Result<Unit> =
        Result.failure(IllegalStateException("Cloud sync is not configured yet (no Firebase project connected)."))
}
