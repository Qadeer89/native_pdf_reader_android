package com.pdfreader.app.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfreader.app.data.documents.DocumentMetadata
import com.pdfreader.app.data.documents.DocumentMetadataResolver
import com.pdfreader.app.data.recents.RecentDocumentEntity
import com.pdfreader.app.data.recents.RecentDocumentsRepository
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
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
 *
 * Only documents opened via [openDocument] (a real [PdfSource.ContentUri]) are recorded
 * to [RecentDocumentsRepository] (PDF-004) — the bundled fixture opened by [loadFixture]
 * is never added to recents, since it isn't a real user document.
 */
class ReaderViewModel(
    private val pdfEngine: PdfEngine,
    private val assetPdfCopier: AssetPdfCopier,
    private val recentDocumentsRepository: RecentDocumentsRepository,
    private val metadataResolver: DocumentMetadataResolver,
    private val fixtureAssetPath: String = "fixtures/sample.pdf",
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _layoutMode = MutableStateFlow(PageLayoutMode.SINGLE)
    val layoutMode: StateFlow<PageLayoutMode> = _layoutMode.asStateFlow()

    val recentDocuments: StateFlow<List<RecentDocumentEntity>> = recentDocumentsRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

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
            openSource(
                source = PdfSource.LocalFile(file),
                metadata = DocumentMetadata(displayName = file.name, sizeBytes = file.length()),
                recentUriToRecord = null,
            )
        }
    }

    /** Opens a document the user picked via the SAF document picker or a share intent. */
    fun openDocument(uri: Uri) {
        viewModelScope.launch {
            val metadata = metadataResolver.resolve(uri.toString())
            openSource(
                source = PdfSource.ContentUri(uri),
                metadata = metadata,
                recentUriToRecord = uri.toString(),
            )
        }
    }

    /** Re-opens a document from the recent-documents list by its stored Uri string. */
    fun openRecentDocument(uriString: String) {
        openDocument(Uri.parse(uriString))
    }

    fun clearRecentDocuments() {
        viewModelScope.launch { recentDocumentsRepository.clearHistory() }
    }

    fun removeRecentDocument(uriString: String) {
        viewModelScope.launch { recentDocumentsRepository.remove(uriString) }
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

    private suspend fun openSource(source: PdfSource, metadata: DocumentMetadata, recentUriToRecord: String?) {
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
                renderAndPublish(0, metadata)
                if (recentUriToRecord != null) {
                    recentDocumentsRepository.recordOpened(
                        uri = recentUriToRecord,
                        displayName = metadata.displayName,
                        pageCount = pageCount,
                        openedAtEpochMillis = clock(),
                    )
                }
            }
        }
    }

    private suspend fun renderAndPublish(pageIndex: Int, metadata: DocumentMetadata? = null) {
        val bitmap = pageBitmap(pageIndex, TARGET_RENDER_WIDTH_PX)
        val currentMetadata = metadata ?: (_uiState.value as? ReaderUiState.Loaded)?.documentMetadata
        if (bitmap != null) {
            currentPageIndex = pageIndex
            _uiState.value = ReaderUiState.Loaded(
                pageIndex = pageIndex,
                pageCount = pageCount,
                pageBitmap = bitmap,
                documentMetadata = currentMetadata,
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
