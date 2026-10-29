package com.pdfreader.app.reader

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TARGET_RENDER_WIDTH_PX = 1080

/**
 * Opens a PDF (bundled fixture by default, or a user-picked SAF/share-intent document
 * per PDF-001) and renders the current page. All PDF work runs via [PdfEngine], whose
 * implementation dispatches off the main thread.
 */
class ReaderViewModel(
    private val pdfEngine: PdfEngine,
    private val assetPdfCopier: AssetPdfCopier,
    private val fixtureAssetPath: String = "fixtures/sample.pdf",
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private var pageCount: Int = 0
    private var currentPageIndex: Int = 0

    init {
        loadFixture()
    }

    private fun loadFixture() {
        viewModelScope.launch {
            val file = assetPdfCopier.copyAssetToCache(fixtureAssetPath)
            openSource(PdfSource.LocalFile(file))
        }
    }

    /** Opens a document the user picked via the SAF document picker or a share intent. */
    fun openDocument(uri: Uri) {
        viewModelScope.launch {
            openSource(PdfSource.ContentUri(uri))
        }
    }

    fun nextPage() {
        goToPage(currentPageIndex + 1)
    }

    fun previousPage() {
        goToPage(currentPageIndex - 1)
    }

    private fun goToPage(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex >= pageCount || targetIndex == currentPageIndex) return
        viewModelScope.launch {
            renderAndPublish(targetIndex)
        }
    }

    private suspend fun openSource(source: PdfSource) {
        _uiState.value = ReaderUiState.Loading
        when (val result = pdfEngine.open(source)) {
            is PdfOpenResult.Failure -> {
                pageCount = 0
                currentPageIndex = 0
                _uiState.value = ReaderUiState.Error(result.error)
            }
            is PdfOpenResult.Success -> {
                pageCount = result.document.pageCount
                currentPageIndex = 0
                renderAndPublish(0)
            }
        }
    }

    private suspend fun renderAndPublish(pageIndex: Int) {
        val bitmap = pdfEngine.renderPage(pageIndex, TARGET_RENDER_WIDTH_PX)
        if (bitmap != null) {
            currentPageIndex = pageIndex
            _uiState.value = ReaderUiState.Loaded(
                pageIndex = pageIndex,
                pageCount = pageCount,
                pageBitmap = bitmap,
            )
        } else {
            _uiState.value = ReaderUiState.Error(PdfOpenError.Unexpected("render returned no page"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { pdfEngine.close() }
    }
}
