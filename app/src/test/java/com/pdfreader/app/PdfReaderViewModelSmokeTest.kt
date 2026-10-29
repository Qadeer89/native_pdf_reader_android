package com.pdfreader.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pdfreader.app.data.documents.DocumentMetadata
import com.pdfreader.app.data.documents.DocumentMetadataResolver
import com.pdfreader.app.data.recents.RecentDocumentsDatabase
import com.pdfreader.app.data.recents.RecentDocumentsRepository
import com.pdfreader.app.model.PdfDocumentHandle
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEngine
import com.pdfreader.app.reader.PageLayoutMode
import com.pdfreader.app.reader.ReaderUiState
import com.pdfreader.app.reader.ReaderViewModel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FIXTURE_PAGE_COUNT = 1
private const val MULTI_PAGE_COUNT = 3
private const val FAKE_RENDER_WIDTH = 400
private const val FAKE_RENDER_HEIGHT = 500

/**
 * Phase 1/2 vertical-slice smoke tests (MASTER_PROMPT.md workflow step 3), actually
 * executed on the JVM via Robolectric in this environment (no Android emulator or
 * physical device is available here; see planning/ADR.md ADR-006).
 *
 * These exercise the real [AssetPdfCopier] (copies the bundled fixture out of app
 * assets into app-private cache) and the real [ReaderViewModel] state-machine
 * (open, page navigation), wired to a [FakePdfEngine] test double standing in for
 * `android.graphics.pdf.PdfRenderer`.
 *
 * The real `PlatformPdfRendererEngine` (backed by the actual platform `PdfRenderer`) is
 * deliberately NOT exercised here: empirically, Robolectric ships no shadow or native
 * binding for `PdfRenderer` in this toolchain (`shadows-framework` and `nativeruntime`
 * jars for Robolectric 4.13 contain no PDF-related classes), so `PdfRenderer.pageCount`
 * reads back as 0 under Robolectric regardless of the input file's validity. That real
 * engine is covered instead by `PdfRenderInstrumentedTest` in `src/androidTest`, which
 * requires `./gradlew connectedAndroidTest` against a real device/emulator — not
 * available in this environment and therefore not executed in this pass (see the
 * milestone report).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfReaderViewModelSmokeTest {

    /**
     * [ReaderViewModel.recentDocuments] stays subscribed to its Room `Flow` for as long as
     * `viewModelScope` is alive. Unlike production (where the Activity/ViewModelProvider owns
     * that lifecycle), nothing here calls `ViewModel.clear()` unless we do it ourselves — so a
     * plain in-memory Room database created in a test and then closed out from under a still-
     * running collector logs a Robolectric `CloseGuard` resource-leak warning (harmless to test
     * results, but noisy, and worth tearing down properly rather than leaving dangling). Each
     * ViewModel built by [newViewModel] is registered in this store so [tearDown] can clear it
     * (cancelling its `viewModelScope`) before the matching database is closed.
     */
    private val viewModelStore = ViewModelStore()
    private val openDatabases = mutableListOf<RecentDocumentsDatabase>()

    @After
    fun tearDown() {
        viewModelStore.clear()
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    /** Builds a [ReaderViewModel] with a real (in-memory) recents DB and a fake metadata resolver. */
    private fun newViewModel(context: Context, engine: PdfEngine): ReaderViewModel {
        val db = Room.inMemoryDatabaseBuilder(context, RecentDocumentsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        openDatabases += db
        val viewModel = ReaderViewModel(
            pdfEngine = engine,
            assetPdfCopier = AssetPdfCopier(context, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined),
            recentDocumentsRepository = RecentDocumentsRepository(db.recentDocumentDao()),
            metadataResolver = FakeDocumentMetadataResolver(),
        )
        viewModelStore.put(db.hashCode().toString(), viewModel)
        return viewModel
    }

    @Test
    fun opensFixtureAndRendersFirstPageThroughViewModel() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        val state = viewModel.uiState.value
        assertTrue("expected Loaded state but got $state", state is ReaderUiState.Loaded)
        val loaded = state as ReaderUiState.Loaded

        assertEquals(0, loaded.pageIndex)
        assertEquals(FIXTURE_PAGE_COUNT, loaded.pageCount)
        assertEquals(FAKE_RENDER_WIDTH, loaded.pageBitmap.width)
        assertEquals(FAKE_RENDER_HEIGHT, loaded.pageBitmap.height)

        val openedSource = fakeEngine.lastOpenedSource
        assertTrue("expected a LocalFile source but got $openedSource", openedSource is PdfSource.LocalFile)
        assertTrue(
            "AssetPdfCopier should have actually copied a non-empty fixture file",
            (openedSource as PdfSource.LocalFile).file.length() > 0,
        )
    }

    @Test
    fun nextAndPreviousPageNavigateWithinBounds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = MULTI_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        viewModel.nextPage()
        var loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(1, loaded.pageIndex)

        viewModel.nextPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(2, loaded.pageIndex)

        // Already on the last page (index 2 of 3) — next is a no-op.
        viewModel.nextPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(2, loaded.pageIndex)

        viewModel.previousPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(1, loaded.pageIndex)

        viewModel.previousPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(0, loaded.pageIndex)

        // Already on the first page — previous is a no-op.
        viewModel.previousPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(0, loaded.pageIndex)
    }

    @Test
    fun openDocumentOpensAContentUriSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        val pickedUri = Uri.parse("content://com.android.providers.downloads.documents/document/42")
        viewModel.openDocument(pickedUri)

        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(0, loaded.pageIndex)

        val openedSource = fakeEngine.lastOpenedSource
        assertTrue("expected a ContentUri source but got $openedSource", openedSource is PdfSource.ContentUri)
        assertEquals(pickedUri, (openedSource as PdfSource.ContentUri).uri)
    }

    @Test
    fun jumpToPageGoesDirectlyToTheRequestedPage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = MULTI_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        viewModel.jumpToPage(2)
        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(2, loaded.pageIndex)

        // Out-of-range jumps are ignored rather than crashing or clamping silently.
        viewModel.jumpToPage(99)
        assertEquals(2, (viewModel.uiState.value as ReaderUiState.Loaded).pageIndex)
    }

    @Test
    fun pageBitmapCachesRenderedPagesPerSizeKey() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = MULTI_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)
        // Opening the document already rendered page 0 at FULL_PAGE_WIDTH once.
        assertEquals(1, fakeEngine.renderCallCount)

        viewModel.pageBitmap(0, ReaderViewModel.FULL_PAGE_WIDTH)
        assertEquals("a cached render should not call the engine again", 1, fakeEngine.renderCallCount)

        viewModel.pageBitmap(0, ReaderViewModel.THUMBNAIL_WIDTH)
        assertEquals("a different size key is not cached yet, so this renders", 2, fakeEngine.renderCallCount)

        viewModel.pageBitmap(1, ReaderViewModel.FULL_PAGE_WIDTH)
        assertEquals("a different page is not cached yet, so this renders", 3, fakeEngine.renderCallCount)
    }

    @Test
    fun toggleLayoutModeFlipsBetweenSingleAndContinuous() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))

        assertEquals(PageLayoutMode.SINGLE, viewModel.layoutMode.value)
        viewModel.toggleLayoutMode()
        assertEquals(PageLayoutMode.CONTINUOUS, viewModel.layoutMode.value)
        viewModel.toggleLayoutMode()
        assertEquals(PageLayoutMode.SINGLE, viewModel.layoutMode.value)
    }

    /**
     * Room's `@Query` [kotlinx.coroutines.flow.Flow] invalidation is delivered by posting
     * back to [ReaderViewModel]'s collector on the main dispatcher from a different
     * (Room executor) thread. Under Robolectric's default paused main Looper, that posted
     * task only runs once something pumps the Looper's queue — so this test idles it in a
     * short poll loop instead of asserting on [ReaderViewModel.recentDocuments]
     * immediately after a write, or relying on virtual test time to move it along.
     */
    @Test
    fun openDocumentRecordsARecentEntryButTheFixtureDoesNot() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT)
        val viewModel = newViewModel(context, fakeEngine)

        // The bundled fixture (loaded on init) must never show up in "Recent".
        assertEquals(emptyList<Any>(), viewModel.recentDocuments.value)

        val pickedUri = Uri.parse("content://com.android.providers.downloads.documents/document/7")
        viewModel.openDocument(pickedUri)

        val recents = awaitRecentDocuments(viewModel) { it.isNotEmpty() }
        assertEquals(1, recents.size)
        assertEquals(pickedUri.toString(), recents.first().uri)
        assertEquals(FIXTURE_PAGE_COUNT, recents.first().pageCount)

        viewModel.clearRecentDocuments()
        val cleared = awaitRecentDocuments(viewModel) { it.isEmpty() }
        assertEquals(emptyList<Any>(), cleared)
    }

    private suspend fun awaitRecentDocuments(
        viewModel: ReaderViewModel,
        predicate: (List<com.pdfreader.app.data.recents.RecentDocumentEntity>) -> Boolean,
    ): List<com.pdfreader.app.data.recents.RecentDocumentEntity> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val current = viewModel.recentDocuments.value
            if (predicate(current)) return current
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("recentDocuments never satisfied the predicate within the timeout")
    }

    private class FakeDocumentMetadataResolver : DocumentMetadataResolver {
        override suspend fun resolve(uriString: String): DocumentMetadata =
            DocumentMetadata(displayName = "fake-document.pdf", sizeBytes = 1234L)
    }

    /** Stands in for [PlatformPdfRendererEngine] — see class-level doc for why. */
    private class FakePdfEngine(private val pageCount: Int) : PdfEngine {
        var lastOpenedSource: PdfSource? = null
        var renderCallCount: Int = 0

        override suspend fun open(source: PdfSource): PdfOpenResult {
            lastOpenedSource = source
            return PdfOpenResult.Success(PdfDocumentHandle(pageCount = pageCount))
        }

        override suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap {
            renderCallCount++
            return Bitmap.createBitmap(FAKE_RENDER_WIDTH, FAKE_RENDER_HEIGHT, Bitmap.Config.ARGB_8888)
        }

        override suspend fun close() {
            // no-op fake
        }
    }
}
