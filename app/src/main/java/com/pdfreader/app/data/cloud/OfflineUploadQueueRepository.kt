package com.pdfreader.app.data.cloud

import kotlinx.coroutines.flow.Flow

/**
 * Thin repository boundary over [PendingUploadDao] (PDF-011's offline upload queue) so
 * callers don't depend on Room directly — same pattern as
 * `com.pdfreader.app.data.recents.RecentDocumentsRepository`.
 *
 * Deliberately independent of [com.pdfreader.app.cloud.CloudSyncRepository]: a document can
 * be queued here while fully offline, or before a cloud backend exists at all — actually
 * uploading a queued entry is a separate concern this repository knows nothing about.
 */
class OfflineUploadQueueRepository(private val dao: PendingUploadDao) {

    fun observeQueue(): Flow<List<PendingUploadEntity>> = dao.observeAll()

    /** Re-enqueuing an already-queued document resets its retry count and last error. */
    suspend fun enqueue(localUri: String, queuedAtEpochMillis: Long) {
        dao.upsert(PendingUploadEntity(localUri = localUri, queuedAtEpochMillis = queuedAtEpochMillis))
    }

    suspend fun dequeue(localUri: String) = dao.delete(localUri)

    suspend fun clear() = dao.clearAll()

    /** No-ops if [localUri] isn't currently queued — same "must not throw" contract as the sync repository's cancelUpload. */
    suspend fun recordFailure(localUri: String, reason: String) {
        val existing = dao.get(localUri) ?: return
        dao.upsert(existing.copy(retryCount = existing.retryCount + 1, lastError = reason))
    }
}
