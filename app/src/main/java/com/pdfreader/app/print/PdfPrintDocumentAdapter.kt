package com.pdfreader.app.print

import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Hands an already-rendered PDF file straight to the Android print framework (PDF-010):
 * [file] is a validated temp copy (see [com.pdfreader.app.reader.ReaderViewModel.requestPrint]),
 * so no page rasterization or layout happens here — the print spooler parses the PDF itself
 * once it receives the bytes in [onWrite].
 *
 * [file] is deleted in [onFinish], which the framework calls once this adapter is no longer
 * needed (print completed, cancelled, or failed) — this is the only reliable point to clean
 * up, since the user may spend an arbitrary amount of time in the system print dialog before
 * [onWrite] is ever called.
 */
class PdfPrintDocumentAdapter(
    private val file: File,
    private val documentName: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes?,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(documentName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, oldAttributes != newAttributes)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            FileInputStream(file).use { input ->
                FileOutputStream(destination.fileDescriptor).use { output ->
                    input.copyTo(output)
                }
            }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: IOException) {
            callback.onWriteFailed(e.message)
        }
    }

    override fun onFinish() {
        file.delete()
    }
}
