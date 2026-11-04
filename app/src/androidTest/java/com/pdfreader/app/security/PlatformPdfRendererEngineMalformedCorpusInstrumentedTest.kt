package com.pdfreader.app.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.PlatformPdfRendererEngine
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * SEC-001 real-device counterpart to `src/test/.../security/MalformedPdfCorpusTest.kt`: the
 * same malformed/adversarial PDF byte sequences, but run through [PlatformPdfRendererEngine] —
 * i.e. the real `android.graphics.pdf.PdfRenderer` binding — which Robolectric has no shadow
 * for (ADR-006), so the JVM corpus test explicitly could not cover it. Must be run with
 * `./gradlew connectedDebugAndroidTest` against a real device/emulator.
 */
@RunWith(AndroidJUnit4::class)
class PlatformPdfRendererEngineMalformedCorpusInstrumentedTest {

    private val filesToDelete = mutableListOf<File>()

    private fun newEngine(): PlatformPdfRendererEngine {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        return PlatformPdfRendererEngine(context = targetContext)
    }

    private fun fixtureBytes(): ByteArray {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        return targetContext.assets.open("fixtures/sample.pdf").use { it.readBytes() }
    }

    private fun fileOf(bytes: ByteArray): File {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("malformed-pdf-corpus-device", ".pdf", targetContext.cacheDir)
        file.writeBytes(bytes)
        filesToDelete += file
        return file
    }

    /** Opens [file] via the real platform renderer and asserts it completes within a short bound (no hang) without throwing past [PdfOpenResult]. */
    private suspend fun assertOpensWithoutHangingOrThrowing(file: File): PdfOpenResult {
        val engine = newEngine()
        return try {
            withTimeout(10_000) { engine.open(PdfSource.LocalFile(file)) }
        } finally {
            engine.close()
        }
    }

    @After
    fun cleanup() {
        filesToDelete.forEach { it.delete() }
    }

    @Test
    fun emptyFileIsRejectedSafely() = runBlocking {
        val result = assertOpensWithoutHangingOrThrowing(fileOf(ByteArray(0)))
        assertTrue("expected Failure for an empty file but got $result", result is PdfOpenResult.Failure)
    }

    @Test
    fun arbitraryNonPdfBytesAreRejectedSafely() = runBlocking {
        val result = assertOpensWithoutHangingOrThrowing(
            fileOf("This is just a plain text file, not a PDF at all.".repeat(50).toByteArray()),
        )
        assertTrue("expected Failure for non-PDF bytes but got $result", result is PdfOpenResult.Failure)
    }

    @Test
    fun pdfHeaderWithNoBodyIsRejectedSafely() = runBlocking {
        val result = assertOpensWithoutHangingOrThrowing(fileOf("%PDF-1.4\n".toByteArray()))
        assertTrue("expected Failure for a header-only file but got $result", result is PdfOpenResult.Failure)
    }

    /** PdfRenderer is strict about structural validity; unlike PDFBox it is not expected to attempt recovery. */
    @Test
    fun truncatedPdfDoesNotHangOrThrow() = runBlocking {
        val original = fixtureBytes()
        assertOpensWithoutHangingOrThrowing(fileOf(original.copyOfRange(0, original.size / 3)))
        Unit
    }

    @Test
    fun corruptedTrailerAndXrefDoesNotHangOrThrow() = runBlocking {
        val original = fixtureBytes()
        val corrupted = original.copyOf()
        val tailStart = (corrupted.size * 9) / 10
        for (i in tailStart until corrupted.size) corrupted[i] = 0
        assertOpensWithoutHangingOrThrowing(fileOf(corrupted))
        Unit
    }

    @Test
    fun selfReferentialPageTreeDoesNotHangOrThrow() = runBlocking {
        val malicious = """
            %PDF-1.4
            1 0 obj
            << /Type /Pages /Kids [1 0 R] /Count 1 >>
            endobj
            2 0 obj
            << /Type /Catalog /Pages 1 0 R >>
            endobj
            trailer
            << /Root 2 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        assertOpensWithoutHangingOrThrowing(fileOf(malicious))
        Unit
    }

    /** An unencrypted-open attempt of an encrypted PDF must fail safely, never crash — PlatformPdfRendererEngine has no password support at all (ADR-020), so any failure outcome is acceptable here, just not a hang/throw. */
    @Test
    fun encryptedPdfOpenedWithoutAPasswordFailsSafely() = runBlocking {
        val sourceBytes = fixtureBytes()
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        val sourceFile = File.createTempFile("malformed-pdf-corpus-device-src", ".pdf", targetContext.cacheDir)
        sourceFile.writeBytes(sourceBytes)
        val encryptedFile = File.createTempFile("malformed-pdf-corpus-device-enc", ".pdf", targetContext.cacheDir)
        filesToDelete += sourceFile
        filesToDelete += encryptedFile
        try {
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(sourceFile).use { document ->
                val policy = com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "owner-secret",
                    "user-secret",
                    com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission(),
                )
                policy.encryptionKeyLength = 128
                document.protect(policy)
                document.save(encryptedFile)
            }
            val result = assertOpensWithoutHangingOrThrowing(encryptedFile)
            assertTrue("expected Failure for an encrypted file opened with no password, got $result", result is PdfOpenResult.Failure)
        } finally {
            // PDFBoxResourceLoader must be initialized before PDDocument.protect()'s font handling runs on-device.
        }
    }

    @Test
    fun documentClaimingFarMorePagesThanItActuallyHasOpensSafelyWithTheRealCount() = runBlocking {
        val malicious = """
            %PDF-1.4
            1 0 obj
            << /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>
            endobj
            2 0 obj
            << /Type /Pages /Kids [1 0 R] /Count 50000000 >>
            endobj
            3 0 obj
            << /Type /Catalog /Pages 2 0 R >>
            endobj
            trailer
            << /Root 3 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        val result = assertOpensWithoutHangingOrThrowing(fileOf(malicious))
        if (result is PdfOpenResult.Success) {
            assertTrue(
                "expected the real (small) page count, not the claimed /Count, got ${result.document.pageCount}",
                result.document.pageCount < 1000,
            )
        }
    }

    @Test
    fun deeplyNestedArrayDoesNotHangOrThrowOrStackOverflow() = runBlocking {
        val depth = 20_000
        val nestedArray = "[".repeat(depth) + "]".repeat(depth)
        val malicious = """
            %PDF-1.4
            1 0 obj
            << /Type /Pages /Kids [] /Count 0 /Dummy $nestedArray >>
            endobj
            2 0 obj
            << /Type /Catalog /Pages 1 0 R >>
            endobj
            trailer
            << /Root 2 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        assertOpensWithoutHangingOrThrowing(fileOf(malicious))
        Unit
    }

    @Test
    fun aDocumentThatOpensButHasNoValidPagesDoesNotCrashOnRenderAttempt() = runBlocking {
        val malicious = """
            %PDF-1.4
            1 0 obj
            << /Type /Pages /Kids [] /Count 0 >>
            endobj
            2 0 obj
            << /Type /Catalog /Pages 1 0 R >>
            endobj
            trailer
            << /Root 2 0 R >>
            %%EOF
        """.trimIndent().toByteArray()
        val file = fileOf(malicious)
        val engine = newEngine()
        try {
            withTimeout(10_000) { engine.open(PdfSource.LocalFile(file)) }
            withTimeout(10_000) {
                val bitmap = engine.renderPage(pageIndex = 0, targetWidthPx = 400)
                assertTrue("rendering an out-of-range page must return null, not crash", bitmap == null)
            }
        } finally {
            engine.close()
        }
    }
}
