package com.pdfreader.app.reader

import android.graphics.Bitmap
import com.pdfreader.app.data.documents.DocumentMetadata
import com.pdfreader.app.model.PdfOpenError

/** UI state for the reader screen. */
sealed class ReaderUiState {
    data object Loading : ReaderUiState()

    data class Loaded(
        val pageIndex: Int,
        val pageCount: Int,
        val pageBitmap: Bitmap,
        val documentMetadata: DocumentMetadata? = null,
    ) : ReaderUiState()

    data class Error(val error: PdfOpenError) : ReaderUiState()
}
