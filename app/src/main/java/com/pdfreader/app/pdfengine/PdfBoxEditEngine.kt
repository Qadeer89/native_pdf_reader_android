package com.pdfreader.app.pdfengine

import android.content.Context
import android.graphics.Bitmap
import com.pdfreader.app.model.PdfAttachment
import com.pdfreader.app.model.PdfDocumentHandle
import com.pdfreader.app.model.PdfFormField
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfOutlineItem
import com.pdfreader.app.model.PdfSearchMatch
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.model.SignatureVerificationResult
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDNameTreeNode
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationTextMarkup
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import com.tom_roush.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.util.CollectionStore

private const val NOTE_ANNOTATION_SIZE_POINTS = 20f
private const val SEARCH_SNIPPET_CONTEXT_CHARS = 24

/**
 * [PdfEditEngine] backed by PDFBox-Android (approved as DEC-001; see
 * docs/DECISIONS_AND_OPEN_QUESTIONS.md and planning/ADR.md ADR-009/ADR-010).
 *
 * Like [PlatformPdfRendererEngine], all mutable state is guarded by a [Mutex] and all work
 * runs on [ioDispatcher], off the caller's thread.
 */
class PdfBoxEditEngine(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PdfEditEngine {

    private val lock = Mutex()
    private var document: PDDocument? = null

    /** The source [document] was last opened from — kept only to re-read raw bytes for [verifySignatures]. */
    private var currentSource: PdfSource? = null

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
        // Explicitly bundled/registered, never Android's own "BC" provider — see ADR-013:
        // Android's bundled BC crypto-algorithm support was removed in API 31.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    override suspend fun open(source: PdfSource): PdfOpenResult = withContext(ioDispatcher) {
        lock.withLock {
            closeLocked()
            try {
                val opened = loadDocument(source)
                document = opened
                currentSource = source
                PdfOpenResult.Success(PdfDocumentHandle(pageCount = opened.numberOfPages))
            } catch (e: InvalidPasswordException) {
                PdfOpenResult.Failure(PdfOpenError.PasswordProtected(e.message ?: "locked"))
            } catch (e: FileNotFoundException) {
                PdfOpenResult.Failure(PdfOpenError.NotFound(e.message ?: source.toString()))
            } catch (e: IOException) {
                PdfOpenResult.Failure(PdfOpenError.Malformed(e.message ?: "malformed PDF"))
            } catch (e: Exception) {
                PdfOpenResult.Failure(PdfOpenError.Unexpected(e.message ?: "unknown error"))
            }
        }
    }

    /**
     * Loads [source] as a fresh [PDDocument]; throws on failure, same as `PDDocument.load`.
     * [PdfSource.password] (PDF-013) is passed straight through — `null` behaves exactly
     * like the password-less overload, so this is a no-op for the overwhelming majority of
     * documents that aren't encrypted.
     */
    private fun loadDocument(source: PdfSource): PDDocument = when (source) {
        is PdfSource.LocalFile -> {
            if (!source.file.exists()) throw FileNotFoundException(source.file.path)
            if (source.password != null) PDDocument.load(source.file, source.password) else PDDocument.load(source.file)
        }
        is PdfSource.ContentUri -> {
            val input = context.contentResolver.openInputStream(source.uri)
                ?: throw FileNotFoundException(source.uri.toString())
            input.use {
                if (source.password != null) PDDocument.load(it, source.password) else PDDocument.load(it)
            }
        }
    }

    override suspend fun addNoteAnnotation(
        pageIndex: Int,
        xFraction: Float,
        yFraction: Float,
        text: String,
    ): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext false

            val page = doc.getPage(pageIndex)
            val mediaBox = page.mediaBox
            val xPoints = mediaBox.lowerLeftX + xFraction.coerceIn(0f, 1f) * mediaBox.width
            // PDF page coordinates are bottom-up; UI taps are measured top-down.
            val yPoints = mediaBox.lowerLeftY + (1f - yFraction.coerceIn(0f, 1f)) * mediaBox.height

            val note = PDAnnotationText()
            note.rectangle = PDRectangle(xPoints, yPoints, NOTE_ANNOTATION_SIZE_POINTS, NOTE_ANNOTATION_SIZE_POINTS)
            note.contents = text
            page.annotations.add(note)
            true
        }
    }

    /**
     * Uses `PDPageContentStream(..., AppendMode.APPEND, ...)` (verified via `javap` against
     * the real PDFBox-Android classes before writing this, same practice as ADR-011) so the
     * stamp is drawn *on top of* the page's existing content rather than replacing it.
     */
    override suspend fun addImageStamp(
        pageIndex: Int,
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float,
        pngBytes: ByteArray,
    ): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext false
            try {
                val page = doc.getPage(pageIndex)
                val mediaBox = page.mediaBox
                val image = PDImageXObject.createFromByteArray(doc, pngBytes, "signature")

                val widthPoints = widthFraction.coerceIn(0.01f, 1f) * mediaBox.width
                val heightPoints = widthPoints * image.height / image.width.toFloat()
                val xPoints = mediaBox.lowerLeftX + xFraction.coerceIn(0f, 1f) * mediaBox.width
                // PDF page coordinates are bottom-up; UI taps are measured top-down, and
                // (xFraction, yFraction) is the stamp's top-left corner (see interface doc).
                val yPoints = mediaBox.lowerLeftY +
                    (1f - yFraction.coerceIn(0f, 1f)) * mediaBox.height - heightPoints

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { stream ->
                    stream.drawImage(image, xPoints, yPoints, widthPoints, heightPoints)
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun annotationCount(pageIndex: Int): Int = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext 0
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext 0
            doc.getPage(pageIndex).annotations.size
        }
    }

    override suspend fun addHighlightAnnotation(
        pageIndex: Int,
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float,
        heightFraction: Float,
    ): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext false
            try {
                val page = doc.getPage(pageIndex)
                val mediaBox = page.mediaBox
                val xPoints = mediaBox.lowerLeftX + xFraction.coerceIn(0f, 1f) * mediaBox.width
                val widthPoints = widthFraction.coerceIn(0.01f, 1f) * mediaBox.width
                val heightPoints = heightFraction.coerceIn(0.01f, 1f) * mediaBox.height
                // Top-down UI fraction -> bottom-up PDF points; (xFraction, yFraction) is the
                // rectangle's top-left corner, same convention as addImageStamp.
                val yTopPoints = mediaBox.lowerLeftY + (1f - yFraction.coerceIn(0f, 1f)) * mediaBox.height
                val yBottomPoints = yTopPoints - heightPoints

                val highlight = PDAnnotationTextMarkup(PDAnnotationTextMarkup.SUB_TYPE_HIGHLIGHT)
                highlight.rectangle = PDRectangle(xPoints, yBottomPoints, widthPoints, heightPoints)
                highlight.color = PDColor(floatArrayOf(1f, 1f, 0f), PDDeviceRGB.INSTANCE)
                highlight.quadPoints = floatArrayOf(
                    xPoints, yTopPoints,
                    xPoints + widthPoints, yTopPoints,
                    xPoints, yBottomPoints,
                    xPoints + widthPoints, yBottomPoints,
                )
                page.annotations.add(highlight)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    override suspend fun deleteAnnotation(pageIndex: Int, annotationIndex: Int): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext false
            val annotations = doc.getPage(pageIndex).annotations
            if (annotationIndex < 0 || annotationIndex >= annotations.size) return@withContext false
            annotations.removeAt(annotationIndex)
            true
        }
    }

    override suspend fun pageCount(): Int = withContext(ioDispatcher) {
        lock.withLock { document?.numberOfPages ?: 0 }
    }

    override suspend fun rotatePage(pageIndex: Int, deltaDegrees: Int): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext false

            val page = doc.getPage(pageIndex)
            val normalized = ((page.rotation + deltaDegrees) % 360 + 360) % 360
            page.rotation = normalized
            true
        }
    }

    override suspend fun deletePage(pageIndex: Int): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext false
            doc.removePage(pageIndex)
            true
        }
    }

    /**
     * Moves the page at [fromIndex] to [toIndex] by rebuilding the page tree's order from a
     * plain reordered list, rather than juggling `PDPageTree.insertBefore`/`insertAfter`
     * against a tree that's shrinking/growing as we go — simpler to get right, and page
     * counts here are small enough that the extra remove/re-add churn doesn't matter.
     */
    override suspend fun movePage(fromIndex: Int, toIndex: Int): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            val pages = doc.pages
            val originalOrder = pages.toList()
            if (fromIndex < 0 || fromIndex >= originalOrder.size || toIndex < 0 || toIndex >= originalOrder.size) {
                return@withContext false
            }
            if (fromIndex == toIndex) return@withContext true

            val reordered = originalOrder.toMutableList()
            val moved = reordered.removeAt(fromIndex)
            reordered.add(toIndex, moved)

            originalOrder.forEach { pages.remove(it) }
            reordered.forEach { pages.add(it) }
            true
        }
    }

    override suspend fun appendDocument(source: PdfSource): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            try {
                loadDocument(source).use { sourceDoc ->
                    sourceDoc.pages.forEach { page -> doc.importPage(page) }
                }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Imports [source]'s pages (which always appends them at the end, same as
     * [appendDocument] — `PDPageTree` has no "insert at index" primitive, only `add`/
     * `insertBefore`/`insertAfter`), then rebuilds the page order with the newly-imported
     * pages spliced into position [atIndex] — the exact same "convert to list, splice,
     * remove-and-re-add everything" technique [movePage] uses, for the same reason: simpler
     * to get right than chaining `insertBefore`/`insertAfter` against a tree whose indices
     * shift as pages are added.
     */
    override suspend fun insertDocument(source: PdfSource, atIndex: Int): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            if (atIndex < 0 || atIndex > doc.numberOfPages) return@withContext false
            try {
                val pages = doc.pages
                val originalOrder = pages.toList()

                val importedPages = loadDocument(source).use { sourceDoc ->
                    sourceDoc.pages.map { page -> doc.importPage(page) }
                }

                val newOrder = originalOrder.toMutableList()
                newOrder.addAll(atIndex, importedPages)

                pages.toList().forEach { pages.remove(it) }
                newOrder.forEach { pages.add(it) }
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Copies [pageIndices] (read-only — [doc] is never modified) into a brand-new
     * [PDDocument], validating the result the same way [writeValidatedTempCopy] does.
     */
    override suspend fun extractPages(pageIndices: List<Int>): File? = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext null
            if (pageIndices.isEmpty() || pageIndices.any { it < 0 || it >= doc.numberOfPages }) return@withContext null

            val tempFile = File(context.cacheDir, "pdf-extracted-${UUID.randomUUID()}.pdf")
            try {
                PDDocument().use { extracted ->
                    pageIndices.forEach { index -> extracted.importPage(doc.getPage(index)) }
                    extracted.save(tempFile)
                }
                PDDocument.load(tempFile).use { reopened ->
                    if (reopened.numberOfPages != pageIndices.size) {
                        tempFile.delete()
                        return@withContext null
                    }
                }
                tempFile
            } catch (e: Exception) {
                tempFile.delete()
                null
            }
        }
    }

    /**
     * Signs a snapshot of [document] (not [document] itself — see interface doc) using the
     * exact pattern proven in `PdfSignaturePocTest` (DEC-002's gate POC): `addSignature` +
     * `saveIncrementalForExternalSigning`, with the CMS bytes built via BouncyCastle.
     */
    override suspend fun signWithDetachedCms(
        signerName: String,
        privateKey: PrivateKey,
        certificateChain: List<X509Certificate>,
    ): File? = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext null
            val expectedPageCount = doc.numberOfPages
            val snapshotBytes = try {
                ByteArrayOutputStream().also { doc.save(it) }.toByteArray()
            } catch (_: Exception) {
                return@withContext null
            }

            val tempFile = File(context.cacheDir, "pdf-signed-${UUID.randomUUID()}.pdf")
            try {
                PDDocument.load(ByteArrayInputStream(snapshotBytes)).use { snapshot ->
                    val signature = PDSignature().apply {
                        setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                        setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                        name = signerName
                        signDate = Calendar.getInstance()
                    }
                    snapshot.addSignature(signature)

                    FileOutputStream(tempFile).use { out ->
                        val externalSigning = snapshot.saveIncrementalForExternalSigning(out)
                        val contentToSign = externalSigning.content.readBytes()
                        externalSigning.setSignature(signCmsDetached(contentToSign, privateKey, certificateChain))
                    }
                }

                PDDocument.load(tempFile).use { reopened ->
                    if (reopened.numberOfPages != expectedPageCount || reopened.signatureDictionaries.isEmpty()) {
                        tempFile.delete()
                        return@withContext null
                    }
                }
                tempFile
            } catch (e: Exception) {
                tempFile.delete()
                null
            }
        }
    }

    private fun signCmsDetached(content: ByteArray, privateKey: PrivateKey, certificateChain: List<X509Certificate>): ByteArray {
        val generator = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val digestCalculatorProvider =
            JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(digestCalculatorProvider).build(contentSigner, certificateChain.first()),
        )
        generator.addCertificates(CollectionStore(certificateChain.map { JcaX509CertificateHolder(it) }))
        return generator.generate(CMSProcessableByteArray(content), false).encoded
    }

    /**
     * Re-reads [currentSource]'s raw bytes (not a re-save of the parsed [PDDocument], which
     * PDFBox could reformat and would break byte-range-based signature verification) so
     * [verifySignatures] can check each signature's actual on-disk coverage.
     */
    override suspend fun verifySignatures(): List<SignatureVerificationResult> = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext emptyList()
            val source = currentSource ?: return@withContext emptyList()
            val signatures = try {
                doc.signatureDictionaries
            } catch (_: Exception) {
                return@withContext emptyList()
            }
            if (signatures.isEmpty()) return@withContext emptyList()

            val fileBytes = try {
                readSourceBytes(source)
            } catch (_: Exception) {
                return@withContext emptyList()
            }
            signatures.map { sig -> verifyOneSignature(sig, fileBytes) }
        }
    }

    private fun readSourceBytes(source: PdfSource): ByteArray = when (source) {
        is PdfSource.LocalFile -> source.file.readBytes()
        is PdfSource.ContentUri -> context.contentResolver.openInputStream(source.uri)?.use { it.readBytes() }
            ?: throw FileNotFoundException(source.uri.toString())
    }

    private fun verifyOneSignature(sig: PDSignature, fileBytes: ByteArray): SignatureVerificationResult {
        val signerName = sig.name
        val signDateMillis = sig.signDate?.timeInMillis
        return try {
            val signedContent = sig.getSignedContent(fileBytes)
            val signedData = CMSSignedData(CMSProcessableByteArray(signedContent), sig.contents)
            val signerInfo = signedData.signerInfos.signers.firstOrNull()
                ?: return SignatureVerificationResult(signerName, signDateMillis, false, "Unknown", false, false)

            @Suppress("UNCHECKED_CAST")
            val selector = signerInfo.getSID() as org.bouncycastle.util.Selector<X509CertificateHolder>
            val matchingCerts = signedData.certificates.getMatches(selector)
            val certificateHolder = matchingCerts.firstOrNull()
            val certificate = certificateHolder?.let {
                JcaX509CertificateConverter().setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(it)
            }

            val cryptographicallyValid = certificate != null && try {
                signerInfo.verify(
                    JcaSimpleSignerInfoVerifierBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build(certificate),
                )
            } catch (_: CMSException) {
                false
            }

            val now = Date()
            val certificateCurrentlyValid = certificate != null && !now.before(certificate.notBefore) && !now.after(certificate.notAfter)

            val byteRange = sig.byteRange
            val coversEntireDocument = byteRange.size == 4 && byteRange[2] + byteRange[3] == fileBytes.size

            SignatureVerificationResult(
                signerName = signerName,
                signDateEpochMillis = signDateMillis,
                cryptographicallyValid = cryptographicallyValid,
                certificateSubject = certificate?.subjectX500Principal?.name ?: "Unknown",
                certificateCurrentlyValid = certificateCurrentlyValid,
                coversEntireDocument = coversEntireDocument,
            )
        } catch (_: Exception) {
            SignatureVerificationResult(signerName, signDateMillis, false, "Unknown", false, false)
        }
    }

    override suspend fun writeValidatedTempCopy(): File? = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext null
            val expectedPageCount = doc.numberOfPages
            val tempFile = File(context.cacheDir, "pdf-edit-${UUID.randomUUID()}.pdf")
            try {
                doc.save(tempFile)
                PDDocument.load(tempFile).use { reopened ->
                    if (reopened.numberOfPages != expectedPageCount) {
                        tempFile.delete()
                        return@withContext null
                    }
                }
                tempFile
            } catch (e: Exception) {
                tempFile.delete()
                null
            }
        }
    }

    /**
     * PDF-013 fallback path only (see the interface doc comment and planning/ADR.md
     * ADR-020) — a fresh [PDFRenderer] per call, since this is only ever invoked for a
     * password-protected document's occasional page render, not the main per-page-view
     * hot path [PdfEngine] normally serves.
     */
    override suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext null
            val pageWidthPoints = doc.getPage(pageIndex).mediaBox.width
            val scale = targetWidthPx.coerceAtLeast(1) / pageWidthPoints
            try {
                PDFRenderer(doc).renderImage(pageIndex, scale)
            } catch (e: IOException) {
                null
            }
        }
    }

    override suspend fun listFormFields(): List<PdfFormField> = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext emptyList()
            val acroForm = doc.documentCatalog.acroForm ?: return@withContext emptyList()
            acroForm.fieldTree.mapNotNull { field ->
                try {
                    PdfFormField(
                        fullyQualifiedName = field.fullyQualifiedName,
                        fieldType = field.fieldType,
                        value = field.valueAsString,
                        checkboxOnValue = (field as? com.tom_roush.pdfbox.pdmodel.interactive.form.PDCheckBox)?.onValue,
                        choiceOptions = (field as? com.tom_roush.pdfbox.pdmodel.interactive.form.PDChoice)?.options.orEmpty(),
                    )
                } catch (e: Exception) {
                    // A single malformed field must not take down the whole listing.
                    null
                }
            }
        }
    }

    override suspend fun setFormFieldValue(fullyQualifiedName: String, value: String): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext false
            val acroForm = doc.documentCatalog.acroForm ?: return@withContext false
            val field = acroForm.getField(fullyQualifiedName) ?: return@withContext false
            try {
                field.setValue(value)
                true
            } catch (e: IOException) {
                false
            }
        }
    }

    override suspend fun listAttachments(): List<PdfAttachment> = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext emptyList()
            collectAttachmentSpecs(doc).map { (name, spec) ->
                PdfAttachment(name = name, sizeBytes = spec.embeddedFile?.size?.toLong())
            }
        }
    }

    override suspend fun extractAttachment(name: String): File? = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext null
            val embeddedFile = collectAttachmentSpecs(doc)[name]?.embeddedFile ?: return@withContext null
            try {
                val safeName = name.replace(Regex("[/\\\\]"), "_")
                val tempFile = File(context.cacheDir, "pdf-attachment-${UUID.randomUUID()}-$safeName")
                tempFile.writeBytes(embeddedFile.toByteArray())
                tempFile
            } catch (e: IOException) {
                null
            }
        }
    }

    override suspend fun extractAllAttachments(): List<Pair<String, File>> = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext emptyList()
            collectAttachmentSpecs(doc).mapNotNull { (name, spec) ->
                val embeddedFile = spec.embeddedFile ?: return@mapNotNull null
                try {
                    val safeName = name.replace(Regex("[/\\\\]"), "_")
                    val tempFile = File(context.cacheDir, "pdf-attachment-${UUID.randomUUID()}-$safeName")
                    tempFile.writeBytes(embeddedFile.toByteArray())
                    name to tempFile
                } catch (e: IOException) {
                    null
                }
            }
        }
    }

    /**
     * Flattens the document's `/Names/EmbeddedFiles` name tree (PDF-009) into a single
     * name-to-spec map, recursing into [com.tom_roush.pdfbox.pdmodel.PDNameTreeNode.getKids]
     * when a node has child nodes instead of names directly — PDFBox's tree may be split
     * either way depending on how the document was produced.
     */
    private fun collectAttachmentSpecs(doc: PDDocument): Map<String, PDComplexFileSpecification> {
        val root = doc.documentCatalog.names?.embeddedFiles ?: return emptyMap()
        return try {
            // getNames()/getKids() declare IOException for a malformed name tree — a single
            // bad attachment entry must not take down every other feature on this document.
            collectNames(root)
        } catch (e: IOException) {
            emptyMap()
        }
    }

    private fun collectNames(node: PDNameTreeNode<PDComplexFileSpecification>): Map<String, PDComplexFileSpecification> {
        node.names?.let { return it }
        val result = mutableMapOf<String, PDComplexFileSpecification>()
        node.kids?.forEach { kid -> result.putAll(collectNames(kid)) }
        return result
    }

    override suspend fun searchText(query: String): List<PdfSearchMatch> = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext emptyList()
            if (query.isBlank()) return@withContext emptyList()
            val matches = mutableListOf<PdfSearchMatch>()
            for (pageIndex in 0 until doc.numberOfPages) {
                val pageText = try {
                    PDFTextStripper().apply {
                        startPage = pageIndex + 1
                        endPage = pageIndex + 1
                    }.getText(doc)
                } catch (e: IOException) {
                    continue
                }
                var searchFrom = 0
                while (true) {
                    val matchIndex = pageText.indexOf(query, searchFrom, ignoreCase = true)
                    if (matchIndex < 0) break
                    val snippetStart = (matchIndex - SEARCH_SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
                    val snippetEnd = (matchIndex + query.length + SEARCH_SNIPPET_CONTEXT_CHARS).coerceAtMost(pageText.length)
                    matches += PdfSearchMatch(
                        pageIndex = pageIndex,
                        snippet = pageText.substring(snippetStart, snippetEnd).trim(),
                    )
                    searchFrom = matchIndex + query.length
                }
            }
            matches
        }
    }

    override suspend fun listOutline(): List<PdfOutlineItem> = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext emptyList()
            val root = doc.documentCatalog.documentOutline ?: return@withContext emptyList()
            val result = mutableListOf<PdfOutlineItem>()
            collectOutline(root, doc, depth = 0, result)
            result
        }
    }

    private fun collectOutline(node: PDOutlineNode, doc: PDDocument, depth: Int, out: MutableList<PdfOutlineItem>) {
        var child = node.firstChild
        while (child != null) {
            val pageIndex = try {
                child.findDestinationPage(doc)?.let { page -> doc.pages.indexOf(page).takeIf { it >= 0 } }
            } catch (e: Exception) {
                // A single malformed/unresolvable destination must not take down the rest
                // of the outline — the title is still shown, just without navigation.
                null
            }
            out += PdfOutlineItem(title = child.title ?: "", depth = depth, pageIndex = pageIndex)
            collectOutline(child, doc, depth + 1, out)
            child = child.nextSibling
        }
    }

    override suspend fun close() = withContext(ioDispatcher) {
        lock.withLock { closeLocked() }
    }

    private fun closeLocked() {
        document?.close()
        document = null
        currentSource = null
    }
}
