package com.pdfreader.app.reader

import android.graphics.Bitmap
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TARGET_RENDER_WIDTH_PX = 1080
private const val THUMBNAIL_WIDTH_PX = 160

enum class PageLayoutMode { SINGLE, CONTINUOUS }

/**
 * Opens a PDF (bundled fixture by default, or a user-picked SAF/share-intent document
 * per PDF-001) and renders page(s). All PDF work runs via [PdfEngine], whose
 * implementation dispatches off the main thread.
 */
class ReaderViewModel(
    private val pdfEngine: PdfEngine,
    private val assetPdfCopier: AssetPdfCopier,
    private val fixtureAssetPath: String = "fixtures/sample.pdf",
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _layoutMode = MutableStateFlow(PageLayoutMode.SINGLE)
    val layoutMode: StateFlow<PageLayoutMode> = _layoutMode.asStateFlow()

    private var pageCount: Int = 0
    private var currentPageIndex: Int = 0

    /**
     * Cache of already-rendered page/thumbnail bitmaps for the currently open document,
     * keyed by `"$pageIndex@$widthPx"` — distinct sizes (thumbnail vs. full page) are
     * cached separately. Cleared on every new document open (see [openSource]). A single
     * [Mutex] (distinct from the engine's own internal lock) prevents two callers from
     * racing to render and cache the same entry twice; it does not block unrelated reads.
     */
    private val pageCacheLock = Mutex()
    private val pageCache = mutableMapOf<String, Bitmap>()

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

    fun toggleLayoutMode() {
        _layoutMode.value = if (_layoutMode.value == PageLayoutMode.SINGLE) {
            PageLayoutMode.CONTINUOUS
        } else {
            PageLayoutMode.SINGLE
        }
    }

    fun nextPage() {
        goToPage(currentPageIndex + 1)
    }

    fun previousPage() {
        goToPage(currentPageIndex - 1)
    }

    /** Jumps directly to [pageIndex] (0-based), e.g. from the thumbnail strip. */
    fun jumpToPage(pageIndex: Int) {
        goToPage(pageIndex)
    }

    fun currentPageCount(): Int = pageCount

    /**
     * Renders (or returns a cached render of) [pageIndex] at [widthPx]. Used by the
     * thumbnail strip and continuous-scroll layout, which each need page images outside
     * the single "current page" slot that [ReaderUiState.Loaded] carries.
     */
    suspend fun pageBitmap(pageIndex: Int, widthPx: Int): Bitmap? {
        val key = "$pageIndex@$widthPx"
        pageCacheLock.withLock { pageCache[key] }?.let { return it }

        val rendered = pdfEngine.renderPage(pageIndex, widthPx) ?: return null
        pageCacheLock.withLock { pageCache[key] = rendered }
        return rendered
    }

    private fun goToPage(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex >= pageCount || targetIndex == currentPageIndex) return
        viewModelScope.launch {
            renderAndPublish(targetIndex)
        }
    }

    private suspend fun openSource(source: PdfSource) {
        _uiState.value = ReaderUiState.Loading
        pageCacheLock.withLock { pageCache.clear() }
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
        val bitmap = pageBitmap(pageIndex, TARGET_RENDER_WIDTH_PX)
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

    companion object {
        const val THUMBNAIL_WIDTH = THUMBNAIL_WIDTH_PX
        const val FULL_PAGE_WIDTH = TARGET_RENDER_WIDTH_PX
    }
}
