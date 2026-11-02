package com.pdfreader.app.data.cloud

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Real test for [OfflineUploadQueueRepository] (PDF-011's offline queue) against an
 * in-memory Room database — same construction pattern `PdfReaderViewModelSmokeTest` uses
 * for the recent-documents DB.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineUploadQueueRepositoryTest {

    private lateinit var db: PendingUploadsDatabase
    private lateinit var repository: OfflineUploadQueueRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PendingUploadsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = OfflineUploadQueueRepository(db.pendingUploadDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun enqueueAddsTheDocumentToTheQueue() = runTest {
        repository.enqueue("content://example/document/1", queuedAtEpochMillis = 1000L)

        val queue = repository.observeQueue().first()
        assertEquals(1, queue.size)
        assertEquals("content://example/document/1", queue.first().localUri)
        assertEquals(0, queue.first().retryCount)
        assertNull(queue.first().lastError)
    }

    @Test
    fun enqueuingTheSameDocumentTwiceUpsertsRatherThanDuplicates() = runTest {
        repository.enqueue("content://example/document/1", queuedAtEpochMillis = 1000L)
        repository.enqueue("content://example/document/1", queuedAtEpochMillis = 2000L)

        val queue = repository.observeQueue().first()
        assertEquals(1, queue.size)
        assertEquals(2000L, queue.first().queuedAtEpochMillis)
    }

    @Test
    fun dequeueRemovesTheDocument() = runTest {
        repository.enqueue("content://example/document/1", queuedAtEpochMillis = 1000L)

        repository.dequeue("content://example/document/1")

        assertTrue(repository.observeQueue().first().isEmpty())
    }

    @Test
    fun recordFailureIncrementsRetryCountAndSetsTheReason() = runTest {
        repository.enqueue("content://example/document/1", queuedAtEpochMillis = 1000L)

        repository.recordFailure("content://example/document/1", "No network connection")
        repository.recordFailure("content://example/document/1", "No network connection")

        val entry = repository.observeQueue().first().first()
        assertEquals(2, entry.retryCount)
        assertEquals("No network connection", entry.lastError)
    }

    @Test
    fun recordFailureIsANoOpForADocumentThatIsNotQueued() = runTest {
        // Must not throw even though nothing was ever enqueued.
        repository.recordFailure("content://example/document/1", "No network connection")

        assertTrue(repository.observeQueue().first().isEmpty())
    }

    @Test
    fun clearEmptiesTheWholeQueue() = runTest {
        repository.enqueue("content://example/document/1", queuedAtEpochMillis = 1000L)
        repository.enqueue("content://example/document/2", queuedAtEpochMillis = 2000L)

        repository.clear()

        assertTrue(repository.observeQueue().first().isEmpty())
    }
}
