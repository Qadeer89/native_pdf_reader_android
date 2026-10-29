package com.pdfreader.app.pdfengine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.pdfreader.app.model.PdfDocumentHandle
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.PdfOpenResult
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [PdfEngine] implementation backed by `android.graphics.pdf.PdfRenderer` (API 21+;
 * this app's minSdk is 26, see planning/ADR.md ADR-001).
 *
 * `PdfRenderer` and `PdfRenderer.Page` are not thread-safe and only one page may be open
 * at a time; access is serialized with a [Mutex] and executed on [ioDispatcher] so callers
 * never block the main/UI thread.
 */
class PlatformPdfRendererEngine(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : PdfEngine {

    private val lock = Mutex()
    private var fileDescriptor: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null

    override suspend fun open(file: File): PdfOpenResult = withContext(ioDispatcher) {
        lock.withLock {
            closeLocked()

            if (!file.exists()) {
                return@withContext PdfOpenResult.Failure(PdfOpenError.NotFound(file.path))
            }

            try {
                val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                val opened = PdfRenderer(fd)
                fileDescriptor = fd
                renderer = opened
                PdfOpenResult.Success(PdfDocumentHandle(pageCount = opened.pageCount))
            } catch (e: SecurityException) {
                PdfOpenResult.Failure(PdfOpenError.PasswordProtected(e.message ?: "locked"))
            } catch (e: IOException) {
                PdfOpenResult.Failure(PdfOpenError.Malformed(e.message ?: "malformed PDF"))
            } catch (e: IllegalArgumentException) {
                PdfOpenResult.Failure(PdfOpenError.Malformed(e.message ?: "malformed PDF"))
            } catch (e: Exception) {
                PdfOpenResult.Failure(PdfOpenError.Unexpected(e.message ?: "unknown error"))
            }
        }
    }

    override suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? = withContext(ioDispatcher) {
        lock.withLock {
            val pdfRenderer = renderer ?: return@withContext null
            if (pageIndex < 0 || pageIndex >= pdfRenderer.pageCount) return@withContext null

            pdfRenderer.openPage(pageIndex).use { page ->
                val widthPx = targetWidthPx.coerceAtLeast(1)
                val scale = widthPx.toFloat() / page.width.toFloat()
                val heightPx = (page.height * scale).toInt().coerceAtLeast(1)

                val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }

    override suspend fun close() = withContext(ioDispatcher) {
        lock.withLock { closeLocked() }
    }

    private fun closeLocked() {
        renderer?.close()
        renderer = null
        fileDescriptor?.close()
        fileDescriptor = null
    }
}
