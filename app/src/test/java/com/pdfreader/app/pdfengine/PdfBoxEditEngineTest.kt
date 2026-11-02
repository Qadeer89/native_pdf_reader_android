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

    /** Self-signed test cert — same approach as `PdfSignaturePocTest`, whose mechanics this reuses via [PdfBoxEditEngine]. */
    private fun generateSelfSignedCertificate(): Pair<java.security.KeyPair, java.security.cert.X509Certificate> {
        if (java.security.Security.getProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
        }
        val keyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = org.bouncycastle.asn1.x500.X500Name("CN=Engine Test Signer")
        val certBuilder = org.bouncycastle.cert.X509v3CertificateBuilder(
            subject,
            java.math.BigInteger.valueOf(now),
            java.util.Date(now),
            java.util.Date(now + 365L * 24 * 60 * 60 * 1000),
            subject,
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))
        return keyPair to certificate
    }

    @Test
    fun signWithDetachedCmsProducesAValidatedSignedCopyAndLeavesTheLiveDocumentUsable() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            engine.open(PdfSource.LocalFile(sourceFile))
            val (keyPair, certificate) = generateSelfSignedCertificate()

            val signedFile = engine.signWithDetachedCms("Engine Test Signer", keyPair.private, listOf(certificate))
            assertTrue("signing should produce a validated signed copy", signedFile != null)

            PDDocument.load(signedFile!!).use { reopened ->
                assertEquals(1, reopened.numberOfPages)
                assertEquals(1, reopened.signatureDictionaries.size)
            }
            signedFile.delete()

            // The live document was signed from a snapshot, not mutated in place — it must
            // still be usable for further (unrelated) edits afterward.
            assertTrue("the live document must still accept further edits after signing", engine.rotatePage(0, 90))

            engine.close()
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun verifySignaturesReportsAValidUntamperedSignatureAndDetectsTampering() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val signingEngine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        var signedFile: File? = null
        try {
            signingEngine.open(PdfSource.LocalFile(sourceFile))
            val (keyPair, certificate) = generateSelfSignedCertificate()
            signedFile = signingEngine.signWithDetachedCms("Engine Test Signer", keyPair.private, listOf(certificate))
            assertTrue(signedFile != null)
            signingEngine.close()

            val verifyingEngine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
            verifyingEngine.open(PdfSource.LocalFile(signedFile!!))
            val results = verifyingEngine.verifySignatures()

            assertEquals(1, results.size)
            val result = results.first()
            assertEquals("Engine Test Signer", result.signerName)
            assertTrue("an untampered signature must verify cryptographically", result.cryptographicallyValid)
            assertTrue("the test certificate is valid for a year, so it must still be current", result.certificateCurrentlyValid)
            assertTrue("this is the only signature, so it must cover the whole file", result.coversEntireDocument)
            assertEquals("CN=Engine Test Signer", result.certificateSubject)
            verifyingEngine.close()

            // Flip one byte early in the file — within the signature's first byte-range
            // segment (always [0, offsetOfContents)), unlike the file's literal midpoint,
            // which can land inside the /Contents hex-digit placeholder itself (unused
            // trailing padding there doesn't affect the parsed CMS structure at all).
            val tamperedFile = File.createTempFile("pdfbox-edit-engine-test-tampered", ".pdf")
            val bytes = signedFile.readBytes()
            val tamperIndex = 200
            bytes[tamperIndex] = (bytes[tamperIndex] + 1).toByte()
            tamperedFile.writeBytes(bytes)
            try {
                val tamperCheckEngine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
                tamperCheckEngine.open(PdfSource.LocalFile(tamperedFile))
                val tamperedResults = tamperCheckEngine.verifySignatures()
                assertEquals(1, tamperedResults.size)
                assertTrue(
                    "a tampered document's signature must not verify cryptographically",
                    !tamperedResults.first().cryptographicallyValid,
                )
                tamperCheckEngine.close()
            } finally {
                tamperedFile.delete()
            }
        } finally {
            signedFile?.delete()
            sourceFile.delete()
        }
    }

    @Test
    fun verifySignaturesReturnsEmptyForAnUnsignedDocument() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            engine.open(PdfSource.LocalFile(sourceFile))
            assertEquals(emptyList<Any>(), engine.verifySignatures())
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

    @Test
    fun insertDocumentGrowsPageCountAtTheGivenPosition() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            engine.open(PdfSource.LocalFile(sourceFile))
            assertEquals(1, engine.pageCount())

            assertTrue(engine.insertDocument(PdfSource.LocalFile(sourceFile), atIndex = 0))
            assertEquals(2, engine.pageCount())

            // Out-of-range position must not crash and must report failure.
            assertTrue(!engine.insertDocument(PdfSource.LocalFile(sourceFile), atIndex = 99))
            assertTrue(!engine.insertDocument(PdfSource.LocalFile(sourceFile), atIndex = -1))

            val tempCopy = engine.writeValidatedTempCopy()
            assertTrue(tempCopy != null)
            PDDocument.load(tempCopy!!).use { reopened -> assertEquals(2, reopened.numberOfPages) }
            tempCopy.delete()

            // The source fixture file itself must be unchanged (never opened for writing).
            PDDocument.load(sourceFile).use { original -> assertEquals(1, original.numberOfPages) }

            engine.close()
        } finally {
            sourceFile.delete()
        }
    }

    @Test
    fun extractPagesCreatesASeparateDocumentWithoutModifyingTheOriginal() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = PdfBoxEditEngine(context, ioDispatcher = Dispatchers.Unconfined)
        val sourceFile = fixtureFile()
        try {
            openThreePageWorkingCopy(engine, sourceFile)

            val extracted = engine.extractPages(listOf(1))
            assertTrue("extraction should produce a validated copy", extracted != null)
            PDDocument.load(extracted!!).use { reopened -> assertEquals(1, reopened.numberOfPages) }
            extracted.delete()

            // The original, currently-open document must be unaffected — extraction copies, never moves.
            assertEquals(3, engine.pageCount())

            // An empty selection or an out-of-range index must not crash and must report failure.
            assertTrue(engine.extractPages(emptyList()) == null)
            assertTrue(engine.extractPages(listOf(99)) == null)

            engine.close()
        } finally {
            sourceFile.delete()
        }
    }
}
