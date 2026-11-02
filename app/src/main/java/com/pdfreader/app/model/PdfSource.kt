package com.pdfreader.app.model

import android.net.Uri
import java.io.File

/**
 * Where a document to open comes from. [ContentUri] is the normal case for PDF-001
 * (Storage Access Framework picker / share intent); [LocalFile] is used for the bundled
 * fixture and for any document already copied into app-private storage.
 *
 * [password] is `null` for the overwhelming majority of opens; it is only set when
 * re-attempting a password-protected document (PDF-013) after the user has entered one —
 * see [withPassword] and [com.pdfreader.app.reader.ReaderViewModel.submitPassword].
 */
sealed class PdfSource {
    abstract val password: String?

    data class LocalFile(val file: File, override val password: String? = null) : PdfSource()
    data class ContentUri(val uri: Uri, override val password: String? = null) : PdfSource()

    fun withPassword(password: String): PdfSource = when (this) {
        is LocalFile -> copy(password = password)
        is ContentUri -> copy(password = password)
    }
}
