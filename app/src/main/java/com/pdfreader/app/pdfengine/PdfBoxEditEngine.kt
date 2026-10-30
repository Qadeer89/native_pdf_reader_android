package com.pdfreader.app.pdfengine

import android.content.Context
import com.pdfreader.app.model.PdfDocumentHandle
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val NOTE_ANNOTATION_SIZE_POINTS = 20f

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

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    override suspend fun open(source: PdfSource): PdfOpenResult = withContext(ioDispatcher) {
        lock.withLock {
            closeLocked()
            try {
                val opened = loadDocument(source)
                document = opened
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

    /** Loads [source] as a fresh [PDDocument]; throws on failure, same as `PDDocument.load`. */
    private fun loadDocument(source: PdfSource): PDDocument = when (source) {
        is PdfSource.LocalFile -> {
            if (!source.file.exists()) throw FileNotFoundException(source.file.path)
            PDDocument.load(source.file)
        }
        is PdfSource.ContentUri -> {
            val input = context.contentResolver.openInputStream(source.uri)
                ?: throw FileNotFoundException(source.uri.toString())
            input.use { PDDocument.load(it) }
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

    override suspend fun annotationCount(pageIndex: Int): Int = withContext(ioDispatcher) {
        lock.withLock {
            val doc = document ?: return@withContext 0
            if (pageIndex < 0 || pageIndex >= doc.numberOfPages) return@withContext 0
            doc.getPage(pageIndex).annotations.size
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

    override suspend fun close() = withContext(ioDispatcher) {
        lock.withLock { closeLocked() }
    }

    private fun closeLocked() {
        document?.close()
        document = null
    }
}
