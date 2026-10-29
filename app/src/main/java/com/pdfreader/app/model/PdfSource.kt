package com.pdfreader.app.model

import android.net.Uri
import java.io.File

/**
 * Where a document to open comes from. [ContentUri] is the normal case for PDF-001
 * (Storage Access Framework picker / share intent); [LocalFile] is used for the bundled
 * fixture and for any document already copied into app-private storage.
 */
sealed class PdfSource {
    data class LocalFile(val file: File) : PdfSource()
    data class ContentUri(val uri: Uri) : PdfSource()
}
