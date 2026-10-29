package com.pdfreader.app.poc

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Phase 3 gate (docs/ARCHITECTURE.md §2 "PDF engine decision — mandatory gate",
 * docs/IMPLEMENTATION_PLAN.md Phase 0): technical proof-of-concept for PDFBox-Android as
 * a candidate engine for annotations/page-operations/encrypted-PDF handling, which the
 * platform `android.graphics.pdf.PdfRenderer` (used so far for PDF-002) cannot do at all.
 *
 * Scope of this POC pass: annotate/save/reopen round trip, merge, and encrypted-PDF
 * open/reject. NOT covered here (left for a later POC increment, see
 * planning/IMPLEMENTATION_BACKLOG.md): visual signature placement, certificate-based
 * signing/validation/tamper-detection, and memory/performance benchmarking on a real
 * device. Evidence and conclusions are recorded in
 * docs/DECISIONS_AND_OPEN_QUESTIONS.md DEC-001 — this is not itself a decision record.
 *
 * This dependency is `testImplementation`-only (see app/build.gradle.kts) — PDFBox-Android
 * is NOT wired into the shipped app. Unlike `PdfRenderer`, PDFBox-Android's document model
 * (COSDocument/PDDocument/annotations) is largely pure-JVM ported code, so this POC runs
 * under Robolectric without hitting the "no native binding" wall that blocked the
 * PdfRenderer-based tests (planning/ADR.md ADR-006) — confirmed empirically below.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBoxAndroidEnginePocTest {

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    private fun fixtureBytes(): ByteArray =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets.open("fixtures/sample.pdf").use { it.readBytes() }

    private fun writeFixtureTo(file: File) {
        file.writeBytes(fixtureBytes())
    }

    @Test
    fun annotateSaveAndReopenRoundTrip() {
        val sourceFile = File.createTempFile("pdfbox-poc-source", ".pdf")
        val outputFile = File.createTempFile("pdfbox-poc-annotated", ".pdf")
        try {
            writeFixtureTo(sourceFile)

            PDDocument.load(sourceFile).use { document ->
                assertEquals(1, document.numberOfPages)

                val page = document.getPage(0)
                val note = PDAnnotationText()
                note.rectangle = PDRectangle(72f, 650f, 20f, 20f)
                note.contents = "PDF-engine POC annotation"
                page.annotations.add(note)

                document.save(outputFile)
            }

            PDDocument.load(outputFile).use { reopened ->
                assertEquals(1, reopened.numberOfPages)
                val annotations = reopened.getPage(0).annotations
                assertEquals(1, annotations.size)
                assertEquals("PDF-engine POC annotation", annotations[0].contents)
            }
        } finally {
            sourceFile.delete()
            outputFile.delete()
        }
    }

    @Test
    fun mergeTwoDocumentsProducesTheExpectedCombinedPageCount() {
        val firstFile = File.createTempFile("pdfbox-poc-merge-a", ".pdf")
        val secondFile = File.createTempFile("pdfbox-poc-merge-b", ".pdf")
        val mergedFile = File.createTempFile("pdfbox-poc-merged", ".pdf")
        try {
            writeFixtureTo(firstFile)
            writeFixtureTo(secondFile)

            val merger = PDFMergerUtility()
            merger.addSource(firstFile)
            merger.addSource(secondFile)
            merger.destinationFileName = mergedFile.path
            merger.mergeDocuments(null)

            PDDocument.load(mergedFile).use { merged ->
                assertEquals(2, merged.numberOfPages)
            }
        } finally {
            firstFile.delete()
            secondFile.delete()
            mergedFile.delete()
        }
    }

    @Test
    fun encryptedDocumentRejectsWrongPasswordAndAcceptsCorrectPassword() {
        val sourceFile = File.createTempFile("pdfbox-poc-encrypt-source", ".pdf")
        val encryptedFile = File.createTempFile("pdfbox-poc-encrypted", ".pdf")
        try {
            writeFixtureTo(sourceFile)

            PDDocument.load(sourceFile).use { document ->
                val permissions = AccessPermission()
                val policy = StandardProtectionPolicy("owner-secret", "user-secret", permissions)
                policy.encryptionKeyLength = 128
                document.protect(policy)
                document.save(encryptedFile)
            }

            assertThrows(InvalidPasswordException::class.java) {
                PDDocument.load(encryptedFile, "wrong-password").close()
            }

            PDDocument.load(encryptedFile, "user-secret").use { opened ->
                assertEquals(1, opened.numberOfPages)
                assertFalse("document should report itself as encrypted", opened.isAllSecurityToBeRemoved)
                assertTrue(opened.isEncrypted)
            }
        } finally {
            sourceFile.delete()
            encryptedFile.delete()
        }
    }
}
