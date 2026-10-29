package com.pdfreader.app.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TARGET_RENDER_WIDTH_PX = 1080

/**
 * Opens the bundled fixture PDF and renders its first page. All PDF work runs via
 * [PdfEngine], whose implementation dispatches off the main thread.
 */
class ReaderViewModel(
    private val pdfEngine: PdfEngine,
    private val assetPdfCopier: AssetPdfCopier,
    private val fixtureAssetPath: String = "fixtures/sample.pdf",
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    init {
        loadFixture()
    }

    private fun loadFixture() {
        viewModelScope.launch {
            _uiState.value = ReaderUiState.Loading
            val file = assetPdfCopier.copyAssetToCache(fixtureAssetPath)
            when (val result = pdfEngine.open(file)) {
                is PdfOpenResult.Failure -> {
                    _uiState.value = ReaderUiState.Error(result.error)
                }
                is PdfOpenResult.Success -> {
                    val bitmap = pdfEngine.renderPage(0, TARGET_RENDER_WIDTH_PX)
                    _uiState.value = if (bitmap != null) {
                        ReaderUiState.Loaded(
                            pageIndex = 0,
                            pageCount = result.document.pageCount,
                            pageBitmap = bitmap,
                        )
                    } else {
                        ReaderUiState.Error(
                            com.pdfreader.app.model.PdfOpenError.Unexpected("render returned no page"),
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { pdfEngine.close() }
    }
}
