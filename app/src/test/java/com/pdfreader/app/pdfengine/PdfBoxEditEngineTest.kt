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
}
