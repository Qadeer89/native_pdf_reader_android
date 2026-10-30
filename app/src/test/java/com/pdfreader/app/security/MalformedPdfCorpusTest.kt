package com.pdfreader.app.security

import androidx.test.core.app.ApplicationProvider
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.PdfBoxEditEngine
import com.pdfreader.app.pdfengine.PdfEditEngine
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SEC-001 (docs/TRACEABILITY_MATRIX.md) / docs/SECURITY_PRIVACY.md §6 ("Handle malformed
 * PDFs and unsupported encryption safely"): a small, self-contained corpus of malformed/
 * adversarial PDF byte sequences, run through [PdfBoxEditEngine] — the engine that actually
 * touches untrusted user documents for anything beyond baseline rendering (PDF-005/006/007/008).
 *
 * Scope, deliberately: this corpus targets *parse-safety* (structurally broken or hostile
 * input must not crash, hang, or throw past the engine's own [PdfOpenResult] boundary) — not
 * true memory-exhaustion/decompression-bomb resistance, which docs/SECURITY_PRIVACY.md §2
 * itself says may need process isolation or SDK-level sandboxing, neither of which this app
 * has (tracked as a separate, harder follow-up in `planning/IMPLEMENTATION_BACKLOG.md`, not
 * silently declared solved by this pass).
 *
 * `android.graphics.pdf.PdfRenderer` (behind `PlatformPdfRendererEngine`) is **not** exercised
 * here: Robolectric has no real binding for it in this toolchain (planning/ADR.md ADR-006),
 * so a "corpus test" against it under Robolectric would just trivially pass without actually
 * parsing anything — that would be worse than not testing it, since it would look like
 * coverage that isn't real. Real verification of that engine's malformed-input handling
 * still needs a device/emulator, same gap as every other `PlatformPdfRendererEngine` claim
 * in this project.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MalformedPdfCorpusTest {

    private fun newEngine(): PdfEditEngine =
        PdfBoxEditEngine(ApplicationProvider.getApplicationContext(), ioDispatcher = Dispatchers.Unconfined)

    private fun fixtureBytes(): ByteArray =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets.open("fixtures/sample.pdf").use { it.readBytes() }

    private fun fileOf(bytes: ByteArray, suffix: String = ".pdf"): File {
        val file = File.createTempFile("malformed-pdf-corpus", suffix)
        file.writeBytes(bytes)
        return file
    }

    /** Opens [file] and asserts it completes within a short bound (no hang) without throwing past [PdfEditEngine.open]'s own result type. */
    private suspend fun assertOpensWithoutHangingOrThrowing(file: File): PdfOpenResult {
        val engine = newEngine()
        val result = try {
            withTimeout(5_000) { engine.open(PdfSource.LocalFile(file)) }
        } finally {
            engine.close()
        }
        return result
    }

    @Test
    fun emptyFileIsRejectedSafely() = runTest {
        val file = fileOf(ByteArray(0))
        try {
            val result = assertOpensWithoutHangingOrThrowing(file)
            assertTrue("expected Failure for an empty file but got $result", result is PdfOpenResult.Failure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun arbitraryNonPdfBytesAreRejectedSafely() = runTest {
        val file = fileOf("This is just a plain text file, not a PDF at all.".repeat(50).toByteArray())
        try {
            val result = assertOpensWithoutHangingOrThrowing(file)
            assertTrue("expected Failure for non-PDF bytes but got $result", result is PdfOpenResult.Failure)
        } finally {
            file.delete()
        }
    }

    @Test
    fun pdfHeaderWithNoBodyIsRejectedSafely() = runTest {
        val file = fileOf("%PDF-1.4\n".toByteArray())
        try {
            val result = assertOpensWithoutHangingOrThrowing(file)
            assertTrue("expected Failure for a header-only file but got $result", result is PdfOpenResult.Failure)
        } finally {
            file.delete()
        }
    }

    /**
     * A valid PDF's bytes, cut off partway through — no trailer, no xref, no `%%EOF`.
     * PDFBox is known to attempt best-effort recovery for some truncated files (its
     * "non-sequential parser" fallback), so this does not assert Failure specifically —
     * only that it never hangs or throws past [PdfOpenResult].
     */
    @Test
    fun truncatedPdfDoesNotHangOrThrow() = runTest {
        val original = fixtureBytes()
        val file = fileOf(original.copyOfRange(0, original.size / 3))
        try {
            assertOpensWithoutHangingOrThrowing(file)
        } finally {
            file.delete()
        }
    }

    /**
     * A valid PDF with its tail (where the xref table and trailer live) overwritten with
     * zero bytes — simulates xref/trailer corruption without needing to hand-craft a
     * syntactically precise broken xref table.
     */
    @Test
    fun corruptedTrailerAndXrefDoesNotHangOrThrow() = runTest {
        val original = fixtureBytes()
        val corrupted = original.copyOf()
        val tailStart = (corrupted.size * 9) / 10
        for (i in tailStart until corrupted.size) corrupted[i] = 0
        val file = fileOf(corrupted)
        try {
            assertOpensWithoutHangingOrThrowing(file)
        } finally {
            file.delete()
        }
    }

    /**
     * A minimal, hand-crafted PDF whose only page-tree node is also its own "kid" — a
     * single-hop self-reference, the simplest shape of the "object graph with a cycle"
     * class of malicious input. Not a stand-in for a true deep-recursion/decompression
     * bomb (see class doc) — just confirms a self-referential object doesn't hang the parser.
     */
    @Test
    fun selfReferentialPageTreeDoesNotHangOrThrow() = runTest {
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
        val file = fileOf(malicious)
        try {
            assertOpensWithoutHangingOrThrowing(file)
        } finally {
            file.delete()
        }
    }

    /** An encrypted PDF, opened without a password — must fail as PasswordProtected, not crash. PDF-013. */
    @Test
    fun encryptedPdfOpenedWithoutAPasswordFailsSafely() = runTest {
        val sourceFile = fileOf(fixtureBytes())
        val encryptedFile = File.createTempFile("malformed-pdf-corpus-encrypted", ".pdf")
        try {
            PDDocument.load(sourceFile).use { document ->
                val policy = StandardProtectionPolicy("owner-secret", "user-secret", AccessPermission())
                policy.encryptionKeyLength = 128
                document.protect(policy)
                document.save(encryptedFile)
            }

            val result = assertOpensWithoutHangingOrThrowing(encryptedFile)
            assertTrue(
                "expected PasswordProtected failure for an encrypted file opened with no password, got $result",
                result is PdfOpenResult.Failure &&
                    result.error is com.pdfreader.app.model.PdfOpenError.PasswordProtected,
            )
        } finally {
            sourceFile.delete()
            encryptedFile.delete()
        }
    }

    /**
     * A document that opens successfully but has zero pages — downstream page-indexed
     * operations (annotate/rotate/stamp) must report failure cleanly, never crash, when
     * given an out-of-range index against such a document.
     */
    @Test
    fun aDocumentThatOpensButHasNoValidPagesRejectsPageOperationsSafely() = runTest {
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
            withTimeout(5_000) { engine.open(PdfSource.LocalFile(file)) }
            withTimeout(5_000) {
                assertTrue(!engine.addNoteAnnotation(0, 0.5f, 0.5f, "note"))
                assertTrue(!engine.rotatePage(0, 90))
                assertTrue(!engine.deletePage(0))
            }
        } finally {
            engine.close()
            file.delete()
        }
    }
}
