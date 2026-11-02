package com.pdfreader.app.cloud

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [NotConfiguredCloudSyncRepository] is the stub [CloudSyncRepository] used until a real
 * Firebase project (DEC-003) is connected — this locks in that it reports every document as
 * purely local, and fails every mutating call clearly rather than pretending to sync.
 */
class NotConfiguredCloudSyncRepositoryTest {

    @Test
    fun reportsEveryDocumentAsLocal() = runTest {
        val repository = NotConfiguredCloudSyncRepository()

        val status = repository.observeSyncStatus("content://example/document/1").first()

        assertEquals(CloudSyncStatus.Local, status)
    }

    @Test
    fun enqueueUploadFailsRatherThanPretendingToQueue() = runTest {
        val repository = NotConfiguredCloudSyncRepository()

        val result = repository.enqueueUpload("content://example/document/1")

        assertTrue("upload must fail until a real Firebase project is connected", result.isFailure)
    }

    @Test
    fun deleteFromCloudFailsRatherThanPretendingToDelete() = runTest {
        val repository = NotConfiguredCloudSyncRepository()

        val result = repository.deleteFromCloud("content://example/document/1")

        assertTrue(result.isFailure)
    }

    @Test
    fun deleteAllAccountDataFails() = runTest {
        val repository = NotConfiguredCloudSyncRepository()

        val result = repository.deleteAllAccountData()

        assertTrue(result.isFailure)
    }

    @Test
    fun cancelUploadIsANoOp() = runTest {
        val repository = NotConfiguredCloudSyncRepository()

        // Must not throw even though nothing was ever enqueued.
        repository.cancelUpload("content://example/document/1")
    }
}
