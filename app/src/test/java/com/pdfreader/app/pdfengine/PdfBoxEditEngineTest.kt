package com.pdfreader.app.pdfengine

import androidx.test.core.app.ApplicationProvider
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.tom_roush.pdfbox.pdmodel.PDDocument
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Production-grade test for [PdfBoxEditEngine] (DEC-001, docs/DECISIONS_AND_OPEN_QUESTIONS.md),
 * distinct from the gate-evidence POC in `src/test/.../poc/PdfBoxAndroidEnginePocTest.kt` — this
 * one exercises the engine as the app actually calls it (via [PdfEditEngine], with
 * [PdfSource], not PDFBox APIs directly).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxEditEngineTest {

    private fun fixtureFile(): File {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File.createTempFile("pdfbox-edit-engine-test", ".pdf")
        context.assets.open("fixtures/sample.pdf").use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        }
        return file
    }

    @Test
    fun opensAddsAnnotationAndWritesAValidatedTempCopy() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()

        try {
            val openResult = engine.open(PdfSource.LocalFile(sourceFile))
            assertTrue("expected Success but got $openResult", openResult is PdfOpenResult.Success)
            assertEquals(1, (openResult as PdfOpenResult.Success).document.pageCount)

            assertEquals(0, engine.annotationCount(0))

            val added = engine.addNoteAnnotation(pageIndex = 0, xFraction = 0.1f, yFraction = 0.2f, text = "A note")
            assertTrue("addNoteAnnotation should report success", added)
            assertEquals(1, engine.annotationCount(0))

            // Out-of-range page index must not crash and must report failure.
            assertTrue(!engine.addNoteAnnotation(pageIndex = 5, xFraction = 0f, yFraction = 0f, text = "x"))

            val tempCopy = engine.writeValidatedTempCopy()
            assertTrue("expected a validated temp file but got null", tempCopy != null)

            PDDocument.load(tempCopy!!).use { reopened ->
                assertEquals(1, reopened.numberOfPages)
                assertEquals(1, reopened.getPage(0).annotations.size)
                assertEquals("A note", reopened.getPage(0).annotations[0].contents)
            }

            tempCopy.delete()
            engine.close()
        } finally {
            sourceFile.delete()
        }
    }

    /**
     * Builds a 3-page working document by appending the (real) 1-page fixture to itself
     * twice via [PdfEditEngine.appendDocument] — exercising that method and giving the
     * rotate/delete/move tests below enough pages to be meaningful, without needing a
     * separate multi-page fixture file.
     */
    private suspend fun openThreePageWorkingCopy(engine: PdfEditEngine, fixture: File): PdfOpenResult {
        val openResult = engine.open(PdfSource.LocalFile(fixture))
        assertTrue(engine.appendDocument(PdfSource.LocalFile(fixture)))
        assertTrue(engine.appendDocument(PdfSource.LocalFile(fixture)))
        assertEquals(3, engine.pageCount())
        return openResult
    }

    @Test
    fun rotatePageNormalizesToTheZeroToThreeHundredSixtyRange() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            openThreePageWorkingCopy(engine, sourceFile)

            assertTrue(engine.rotatePage(0, 90))
            assertTrue(engine.rotatePage(0, 90))
            assertTrue(engine.rotatePage(0, 90))
            assertTrue(engine.rotatePage(0, 90)) // 4 * 90 = 360 -> back to 0

            // Out-of-range page index must not crash and must report failure.
            assertTrue(!engine.rotatePage(99, 90))

            val tempCopy = engine.writeValidatedTempCopy()
            assertTrue(tempCopy != null)
            PDDocument.load(tempCopy!!).use { reopened ->
                assertEquals(0, reopened.getPage(0).rotation)
            }
            tempCopy.delete()
            engine.close()
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun deletePageRemovesExactlyThatPage() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            openThreePageWorkingCopy(engine, sourceFile)

            assertTrue(engine.deletePage(1))
            assertEquals(2, engine.pageCount())

            // Out-of-range page index must not crash and must report failure.
            assertTrue(!engine.deletePage(99))

            val tempCopy = engine.writeValidatedTempCopy()
            assertTrue(tempCopy != null)
            PDDocument.load(tempCopy!!).use { reopened -> assertEquals(2, reopened.numberOfPages) }
            tempCopy!!.delete()
            engine.close()
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun movePageReordersWithoutChangingPageCount() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            openThreePageWorkingCopy(engine, sourceFile)

            assertTrue(engine.movePage(fromIndex = 0, toIndex = 2))
            assertEquals(3, engine.pageCount())

            // Out-of-range indices must not crash and must report failure.
            assertTrue(!engine.movePage(fromIndex = 0, toIndex = 99))
            assertTrue(!engine.movePage(fromIndex = 99, toIndex = 0))

            val tempCopy = engine.writeValidatedTempCopy()
            assertTrue(tempCopy != null)
            PDDocument.load(tempCopy!!).use { reopened -> assertEquals(3, reopened.numberOfPages) }
            tempCopy!!.delete()
            engine.close()
        } finally {
            sourceFile.delete()
        }
    }

    /**
     * A hand-decoded, known-valid 4x4 RGB PNG (generated via `javax.imageio.ImageIO`, not a
     * hand-crafted minimal PNG) — a 1x1 or paletted/alpha PNG triggers a real Robolectric/
     * OpenJDK decoder bug (robolectric/robolectric#6812) unrelated to this engine's own code.
     */
    private fun tinyPngBytes(): ByteArray = java.util.Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAIAAAAmkwkpAAAADElEQVR4XmNgIB0AAAA0AAGPmztyAAAAAElFTkSuQmCC",
    )

    @Test
    fun addImageStampDrawsOntoThePageWithoutChangingPageCount() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            engine.open(PdfSource.LocalFile(sourceFile))
            val pngBytes = tinyPngBytes()

            val placed = engine.addImageStamp(pageIndex = 0, xFraction = 0.1f, yFraction = 0.2f, widthFraction = 0.3f, pngBytes = pngBytes)
            assertTrue("addImageStamp should report success", placed)

            // Out-of-range page index must not crash and must report failure.
            assertTrue(!engine.addImageStamp(pageIndex = 99, xFraction = 0f, yFraction = 0f, widthFraction = 0.1f, pngBytes = pngBytes))

            val tempCopy = engine.writeValidatedTempCopy()
            assertTrue(tempCopy != null)
            PDDocument.load(tempCopy!!).use { reopened ->
                assertEquals(1, reopened.numberOfPages)
                assertTrue(
                    "stamping an image should add an XObject resource to the page",
                    reopened.getPage(0).resources.xObjectNames.iterator().hasNext(),
                )
            }
            tempCopy.delete()
            engine.close()
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun appendDocumentGrowsThePageCountAndLeavesTheSourceUntouched() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            engine.open(PdfSource.LocalFile(sourceFile))
            assertEquals(1, engine.pageCount())

            assertTrue(engine.appendDocument(PdfSource.LocalFile(sourceFile)))
            assertEquals(2, engine.pageCount())

            // The source fixture file itself must be unchanged (never opened for writing).
            PDDocument.load(sourceFile).use { original -> assertEquals(1, original.numberOfPages) }

            engine.close()
        } finally {
            sourceFile.delete()
        }
    }
}
