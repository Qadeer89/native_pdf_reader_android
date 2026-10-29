package com.pdfreader.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PlatformPdfRendererEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real device/emulator instrumented test exercising the actual
 * `android.graphics.pdf.PdfRenderer` (via [PlatformPdfRendererEngine]) against the
 * bundled fixture PDF: open -> page count -> render page 1.
 *
 * NOT executed in this pass: no Android emulator or physical device is available in
 * this build environment (see planning/ADR.md ADR-006 and the milestone report). A JVM
 * unit test (`PdfReaderViewModelSmokeTest` in `src/test`) exercises the same open/render
 * UI wiring with a fake [com.pdfreader.app.pdfengine.PdfEngine] and does run in this
 * environment. Robolectric cannot stand in for this test: it ships no shadow or native
 * binding for `PdfRenderer` (verified empirically — `PdfRenderer.getPageCount()` returns
 * 0 under Robolectric regardless of input, for any PDF). This test must be run with
 * `./gradlew connectedAndroidTest` against a real device/emulator before PDF-002 can be
 * marked `Verified` in docs/TRACEABILITY_MATRIX.md.
 */
@RunWith(AndroidJUnit4::class)
class PdfRenderInstrumentedTest {

    @Test
    fun opensFixtureAndRendersFirstPage() = runBlocking {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val copier = AssetPdfCopier(targetContext)
        val engine = PlatformPdfRendererEngine(context = targetContext)

        val file = copier.copyAssetToCache("fixtures/sample.pdf")
        assertTrue(file.length() > 0)

        val openResult = engine.open(PdfSource.LocalFile(file))
        assertTrue(openResult is PdfOpenResult.Success)
        assertEquals(1, (openResult as PdfOpenResult.Success).document.pageCount)

        val bitmap = engine.renderPage(pageIndex = 0, targetWidthPx = 400)
        assertTrue(bitmap != null)
        assertEquals(400, bitmap!!.width)
        assertTrue(bitmap.height > 0)

        engine.close()
    }
}
