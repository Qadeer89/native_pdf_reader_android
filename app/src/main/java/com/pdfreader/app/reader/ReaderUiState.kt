package com.pdfreader.app.reader

import android.graphics.Bitmap
import com.pdfreader.app.model.PdfOpenError

/** UI state for the single-page reader screen in the Phase 1 vertical slice. */
sealed class ReaderUiState {
    data object Loading : ReaderUiState()

    data class Loaded(
        val pageIndex: Int,
        val pageCount: Int,
        val pageBitmap: Bitmap,
    ) : ReaderUiState()

    data class Error(val error: PdfOpenError) : ReaderUiState()
}
