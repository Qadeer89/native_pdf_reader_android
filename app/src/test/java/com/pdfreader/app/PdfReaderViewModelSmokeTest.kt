package com.pdfreader.app

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.pdfreader.app.model.PdfDocumentHandle
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEngine
import com.pdfreader.app.reader.ReaderUiState
import com.pdfreader.app.reader.ReaderViewModel
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FIXTURE_PAGE_COUNT = 1
private const val FAKE_RENDER_WIDTH = 400
private const val FAKE_RENDER_HEIGHT = 500

/**
 * Phase 1 vertical-slice smoke test (MASTER_PROMPT.md workflow step 3), actually
 * executed on the JVM via Robolectric in this environment (no Android emulator or
 * physical device is available here; see planning/ADR.md ADR-006).
 *
 * This exercises the real [AssetPdfCopier] (copies the bundled fixture out of app
 * assets into app-private cache) and the real [ReaderViewModel] state-machine, wired
 * to a [FakePdfEngine] test double standing in for `android.graphics.pdf.PdfRenderer`.
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

    @Test
    fun opensFixtureAndRendersFirstPageThroughViewModel() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val copier = AssetPdfCopier(context, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined)
        val fakeEngine = FakePdfEngine()

        val viewModel = ReaderViewModel(
            pdfEngine = fakeEngine,
            assetPdfCopier = copier,
        )

        val state = viewModel.uiState.value
        assertTrue("expected Loaded state but got $state", state is ReaderUiState.Loaded)
        val loaded = state as ReaderUiState.Loaded

        assertEquals(0, loaded.pageIndex)
        assertEquals(FIXTURE_PAGE_COUNT, loaded.pageCount)
        assertEquals(FAKE_RENDER_WIDTH, loaded.pageBitmap.width)
        assertEquals(FAKE_RENDER_HEIGHT, loaded.pageBitmap.height)

        assertTrue(
            "AssetPdfCopier should have actually copied a non-empty fixture file",
            fakeEngine.lastOpenedFile?.length()?.let { it > 0 } == true,
        )
    }

    /** Stands in for [PlatformPdfRendererEngine] — see class-level doc for why. */
    private class FakePdfEngine : PdfEngine {
        var lastOpenedFile: File? = null

        override suspend fun open(file: File): PdfOpenResult {
            lastOpenedFile = file
            return PdfOpenResult.Success(PdfDocumentHandle(pageCount = FIXTURE_PAGE_COUNT))
        }

        override suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap =
            Bitmap.createBitmap(FAKE_RENDER_WIDTH, FAKE_RENDER_HEIGHT, Bitmap.Config.ARGB_8888)

        override suspend fun close() {
            // no-op fake
        }
    }
}
