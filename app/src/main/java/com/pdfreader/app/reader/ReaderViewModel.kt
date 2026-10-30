package com.pdfreader.app.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfreader.app.data.documents.DestinationWriter
import com.pdfreader.app.data.documents.DocumentMetadata
import com.pdfreader.app.data.documents.DocumentMetadataResolver
import com.pdfreader.app.data.recents.RecentDocumentEntity
import com.pdfreader.app.data.recents.RecentDocumentsRepository
import com.pdfreader.app.data.signatures.ImageUriDecoder
import com.pdfreader.app.data.signatures.SavedSignature
import com.pdfreader.app.data.signatures.SignatureRepository
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEditEngine
import com.pdfreader.app.pdfengine.PdfEngine
import java.io.ByteArrayOutputStream
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
private const val DEFAULT_NOTE_TEXT = "Note"
private const val DEFAULT_SIGNATURE_WIDTH_FRACTION = 0.35f

enum class PageLayoutMode { SINGLE, CONTINUOUS }

/**
 * Opens a PDF (bundled fixture by default, or a user-picked SAF/share-intent document
 * per PDF-001), renders page(s) via [pdfEngine], and — once DEC-001 (PDFBox-Android) was
 * approved — supports adding simple note annotations (PDF-005), page operations (PDF-006),
 * stamping saved visual signatures via [signatureRepository]/[pdfEditEngine] (PDF-007), and
 * exporting an edited copy (PDF-010).
 *
 * The two engines are opened in parallel against the *same* [PdfSource] but are otherwise
 * independent (see the doc comment on [PdfEditEngine]): [pdfEngine] drives every page
 * render, [pdfEditEngine] only tracks annotations until the user exports a copy. The
 * rendered bitmap does not reflect in-progress annotations live — [ReaderUiState.Loaded]
 * carries [ReaderUiState.Loaded.annotationMarkers] (normalized positions for the current
 * page, this session only) so the UI can draw its own overlay markers instead.
 *
 * Only documents opened via [openDocument] (a real [PdfSource.ContentUri]) are recorded
 * to [RecentDocumentsRepository] (PDF-004) — the bundled fixture opened by [loadFixture]
 * is never added to recents, since it isn't a real user document.
 */
class ReaderViewModel(
    private val pdfEngine: PdfEngine,
    private val pdfEditEngine: PdfEditEngine,
    private val assetPdfCopier: AssetPdfCopier,
    private val recentDocumentsRepository: RecentDocumentsRepository,
    private val metadataResolver: DocumentMetadataResolver,
    private val destinationWriter: DestinationWriter,
    private val signatureRepository: SignatureRepository,
    private val imageUriDecoder: ImageUriDecoder,
    private val fixtureAssetPath: String = "fixtures/sample.pdf",
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ReaderUiState>(ReaderUiState.Loading)
    val uiState: StateFlow<ReaderUiState> = _uiState.asStateFlow()

    private val _layoutMode = MutableStateFlow(PageLayoutMode.SINGLE)
    val layoutMode: StateFlow<PageLayoutMode> = _layoutMode.asStateFlow()

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /**
     * Transient feedback for a page operation (rotate/delete/merge) that just happened.
     * These operations only affect [pdfEditEngine]'s in-memory document, not what
     * [pdfEngine] is currently rendering (see class doc) — so the message exists to tell
     * the user their change is staged and needs [exportAnnotatedCopy] to actually be saved,
     * since there is no live-updated preview of the new page order/count to show instead.
     */
    private val _pageOpMessage = MutableStateFlow<String?>(null)
    val pageOpMessage: StateFlow<String?> = _pageOpMessage.asStateFlow()

    val recentDocuments: StateFlow<List<RecentDocumentEntity>> = recentDocumentsRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _savedSignatures = MutableStateFlow<List<SavedSignature>>(emptyList())
    val savedSignatures: StateFlow<List<SavedSignature>> = _savedSignatures.asStateFlow()

    private val _selectedSignatureId = MutableStateFlow<String?>(null)
    val selectedSignatureId: StateFlow<String?> = _selectedSignatureId.asStateFlow()

    private var pageCount: Int = 0
    private var currentPageIndex: Int = 0
    private var editEngineAvailable: Boolean = false

    /** Normalized marker positions per page index, this session only (not read back from the file). */
    private val annotationMarkersByPage = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()

    /** Visual-only rotation feedback per page index, this session only — see [rotateCurrentPage]. */
    private val pageRotationsDegrees = mutableMapOf<Int, Int>()

    /** Signatures stamped this session, per page index — see [placeSignatureAt]. */
    private val signatureStampsByPage = mutableMapOf<Int, MutableList<SignatureStamp>>()

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
        viewModelScope.launch { refreshSavedSignatures() }
    }

    private suspend fun refreshSavedSignatures() {
        val signatures = signatureRepository.listAll()
        _savedSignatures.value = signatures
        if (_selectedSignatureId.value == null) {
            _selectedSignatureId.value = signatures.lastOrNull()?.id
        }
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
     * Adds a note annotation to the current page at ([xFraction], [yFraction]) — both in
     * `0f..1f` of the displayed page, top-down (matching tap coordinates). No-ops if the
     * edit engine failed to open (e.g. the document is still mid-load). PDF-005.
     */
    fun addNoteAt(xFraction: Float, yFraction: Float, text: String = DEFAULT_NOTE_TEXT) {
        if (!editEngineAvailable) return
        val pageIndex = currentPageIndex
        viewModelScope.launch {
            val added = pdfEditEngine.addNoteAnnotation(pageIndex, xFraction, yFraction, text)
            if (added) {
                annotationMarkersByPage.getOrPut(pageIndex) { mutableListOf() }.add(xFraction to yFraction)
                val current = _uiState.value
                if (current is ReaderUiState.Loaded && current.pageIndex == pageIndex) {
                    _uiState.value = current.copy(annotationMarkers = annotationMarkersByPage[pageIndex].orEmpty())
                }
            }
        }
    }

    /** Writes a validated temp copy with all annotations and commits it to [destination] (PDF-010). */
    fun exportAnnotatedCopy(destination: Uri) {
        viewModelScope.launch {
            _exportState.value = ExportState.Saving
            val tempFile = pdfEditEngine.writeValidatedTempCopy()
            if (tempFile == null) {
                _exportState.value = ExportState.Failure("Could not prepare the annotated copy.")
                return@launch
            }
            val committed = destinationWriter.writeFileToUri(tempFile, destination)
            tempFile.delete()
            _exportState.value = if (committed) {
                ExportState.Success
            } else {
                ExportState.Failure("Could not save to the chosen location.")
            }
        }
    }

    fun acknowledgeExportState() {
        _exportState.value = ExportState.Idle
    }

    fun acknowledgePageOpMessage() {
        _pageOpMessage.value = null
    }

    /**
     * Rotates the current page a quarter turn (PDF-006). Unlike annotations, rotation is
     * safe to preview live without re-rendering via [pdfEngine]: it's a pure visual
     * transform, so [ReaderUiState.Loaded.pageRotationDegrees] is updated immediately and
     * the UI applies it as a Compose rotation on top of the unrotated bitmap.
     */
    fun rotateCurrentPage() {
        if (!editEngineAvailable) return
        val pageIndex = currentPageIndex
        viewModelScope.launch {
            if (pdfEditEngine.rotatePage(pageIndex, 90)) {
                val newRotation = (pageRotationsDegrees.getOrDefault(pageIndex, 0) + 90) % 360
                pageRotationsDegrees[pageIndex] = newRotation
                val current = _uiState.value
                if (current is ReaderUiState.Loaded && current.pageIndex == pageIndex) {
                    _uiState.value = current.copy(pageRotationDegrees = newRotation)
                }
            }
        }
    }

    /**
     * Deletes the current page (PDF-006). This changes the edited document's page count/
     * order, which [pdfEngine] (still rendering the original, unedited file) has no way to
     * reflect live — so this only stages the change and reports it via [pageOpMessage];
     * [exportAnnotatedCopy] is what actually saves it.
     */
    fun deleteCurrentPage() {
        if (!editEngineAvailable) return
        viewModelScope.launch {
            _pageOpMessage.value = if (pdfEditEngine.deletePage(currentPageIndex)) {
                "Page deleted. Export a copy to save this change."
            } else {
                "Could not delete this page."
            }
        }
    }

    /** Appends every page of [uri] to the end of the document (PDF-006 merge). Staged like [deleteCurrentPage]. */
    fun mergeDocument(uri: Uri) {
        if (!editEngineAvailable) return
        viewModelScope.launch {
            _pageOpMessage.value = if (pdfEditEngine.appendDocument(PdfSource.ContentUri(uri))) {
                "Pages added. Export a copy to save this change."
            } else {
                "Could not add pages from that document."
            }
        }
    }

    /** Marks [id] as the signature [placeSignatureAt] will stamp next (PDF-007). */
    fun selectSignature(id: String) {
        _selectedSignatureId.value = id
    }

    /** Saves a newly drawn/typed signature [bitmap] and selects it. PDF-007. */
    fun saveSignature(bitmap: Bitmap) {
        viewModelScope.launch { persistSignature(bitmap) }
    }

    /** Decodes and saves a picked image [uri] as a signature, then selects it. PDF-007. */
    fun importSignatureImage(uri: Uri) {
        viewModelScope.launch {
            val bitmap = imageUriDecoder.decode(uri) ?: return@launch
            persistSignature(bitmap)
        }
    }

    private suspend fun persistSignature(bitmap: Bitmap) {
        val saved = signatureRepository.save(bitmap)
        refreshSavedSignatures()
        _selectedSignatureId.value = saved.id
    }

    fun deleteSignature(id: String) {
        viewModelScope.launch {
            signatureRepository.delete(id)
            if (_selectedSignatureId.value == id) {
                _selectedSignatureId.value = null
            }
            refreshSavedSignatures()
        }
    }

    /**
     * Stamps the currently selected saved signature onto the current page at ([xFraction],
     * [yFraction]) — its top-left corner, `0f..1f` of the page, top-down (matching tap
     * coordinates). No-ops if the edit engine is unavailable or no signature is selected.
     * Unlike delete/move/merge (PDF-006), this is drawn directly onto the page's content
     * stream rather than changing page count/order, so — like note annotations — it gets a
     * live Compose overlay instead of needing a full re-render. PDF-007.
     */
    fun placeSignatureAt(xFraction: Float, yFraction: Float) {
        if (!editEngineAvailable) return
        val signature = _savedSignatures.value.firstOrNull { it.id == _selectedSignatureId.value } ?: return
        val pageIndex = currentPageIndex
        viewModelScope.launch {
            val pngBytes = ByteArrayOutputStream().use { stream ->
                signature.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            val placed = pdfEditEngine.addImageStamp(pageIndex, xFraction, yFraction, DEFAULT_SIGNATURE_WIDTH_FRACTION, pngBytes)
            if (placed) {
                val stamp = SignatureStamp(xFraction, yFraction, DEFAULT_SIGNATURE_WIDTH_FRACTION, signature.bitmap)
                signatureStampsByPage.getOrPut(pageIndex) { mutableListOf() }.add(stamp)
                val current = _uiState.value
                if (current is ReaderUiState.Loaded && current.pageIndex == pageIndex) {
                    _uiState.value = current.copy(signatureStamps = signatureStampsByPage[pageIndex].orEmpty())
                }
            }
        }
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
        annotationMarkersByPage.clear()
        pageRotationsDegrees.clear()
        signatureStampsByPage.clear()
        _pageOpMessage.value = null
        editEngineAvailable = pdfEditEngine.open(source) is PdfOpenResult.Success

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
                annotationMarkers = annotationMarkersByPage[pageIndex].orEmpty(),
                canAnnotate = editEngineAvailable,
                pageRotationDegrees = pageRotationsDegrees.getOrDefault(pageIndex, 0),
                signatureStamps = signatureStampsByPage[pageIndex].orEmpty(),
            )
        } else {
            _uiState.value = ReaderUiState.Error(PdfOpenError.Unexpected("render returned no page"))
        }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch {
            pdfEngine.close()
            pdfEditEngine.close()
        }
    }

    companion object {
        const val THUMBNAIL_WIDTH = THUMBNAIL_WIDTH_PX
        const val FULL_PAGE_WIDTH = TARGET_RENDER_WIDTH_PX
    }
}
