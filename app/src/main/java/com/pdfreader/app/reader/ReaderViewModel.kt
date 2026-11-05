package com.pdfreader.app.reader

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pdfreader.app.data.documents.DestinationWriter
import com.pdfreader.app.data.documents.DocumentMetadata
import com.pdfreader.app.data.documents.DocumentMetadataResolver
import com.pdfreader.app.data.recents.RecentDocumentEntity
import com.pdfreader.app.data.recents.RecentDocumentsRepository
import com.pdfreader.app.data.signatures.ImageUriDecoder
import com.pdfreader.app.data.signatures.SavedSignature
import com.pdfreader.app.data.signatures.SignaturePlacement
import com.pdfreader.app.data.signatures.SignatureRepository
import com.pdfreader.app.data.signatures.removeLightBackground
import com.pdfreader.app.model.PdfAttachment
import com.pdfreader.app.model.PdfFormField
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfOutlineItem
import com.pdfreader.app.model.PdfSearchMatch
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.model.SignatureVerificationResult
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEditEngine
import com.pdfreader.app.pdfengine.PdfEngine
import com.pdfreader.app.signing.BytesUriReader
import com.pdfreader.app.signing.Pkcs12SigningMaterialLoader
import com.pdfreader.app.signing.SigningIdentity
import com.pdfreader.app.signing.SigningIdentityManager
import java.io.ByteArrayOutputStream
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
private const val DEFAULT_HIGHLIGHT_WIDTH_FRACTION = 0.3f
private const val DEFAULT_HIGHLIGHT_HEIGHT_FRACTION = 0.04f
private const val KEY_LAST_OPEN_URI = "reader_last_open_uri"
private const val KEY_LAST_PAGE_INDEX = "reader_last_page_index"

enum class PageLayoutMode { SINGLE, CONTINUOUS }

/**
 * Opens a PDF (bundled fixture by default, or a user-picked SAF/share-intent document
 * per PDF-001), renders page(s) via [pdfEngine], and — once DEC-001 (PDFBox-Android) was
 * approved — supports adding simple note annotations (PDF-005), page operations (PDF-006),
 * stamping saved visual signatures via [signatureRepository]/[pdfEditEngine] (PDF-007),
 * certificate-based digital signing via [signingIdentityManager]/[pkcs12Loader] (PDF-008,
 * DEC-002), and exporting an edited copy (PDF-010).
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
    private val signingIdentityManager: SigningIdentityManager,
    private val pkcs12Loader: Pkcs12SigningMaterialLoader,
    private val bytesUriReader: BytesUriReader,
    private val fixtureAssetPath: String = "fixtures/sample.pdf",
    /**
     * Test-only escape hatch: when true and there's no previously-open document to restore,
     * [init] loads the bundled [fixtureAssetPath] fixture instead of showing [ReaderUiState.Empty].
     * Production (see `MainActivity`) never passes this, so a fresh install shows the real
     * Home/empty state rather than the smoke-test PDF; test helpers pass `true` to keep
     * exercising the fixture-driven state machine without touching every test case.
     */
    private val loadFixtureOnEmptyStartup: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
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

    /**
     * True while [mergeDocument]/[insertDocumentAt] — the two page operations that read a
     * whole second document and so can be slow for a large file — are running, so the UI
     * can show a progress indicator and offer [cancelPageOp]. Cancellation is cooperative
     * (standard `Job.cancel()`): it stops at the next suspension point inside the engine
     * rather than guaranteed-instantly mid-copy, same caveat as any other Kotlin coroutine
     * cancellation. PDF-006.
     */
    private val _pageOpInProgress = MutableStateFlow(false)
    val pageOpInProgress: StateFlow<Boolean> = _pageOpInProgress.asStateFlow()

    private var pageOpJob: Job? = null

    val recentDocuments: StateFlow<List<RecentDocumentEntity>> = recentDocumentsRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _savedSignatures = MutableStateFlow<List<SavedSignature>>(emptyList())
    val savedSignatures: StateFlow<List<SavedSignature>> = _savedSignatures.asStateFlow()

    private val _selectedSignatureId = MutableStateFlow<String?>(null)
    val selectedSignatureId: StateFlow<String?> = _selectedSignatureId.asStateFlow()

    private val _signingIdentities = MutableStateFlow<List<SigningIdentity>>(emptyList())
    val signingIdentities: StateFlow<List<SigningIdentity>> = _signingIdentities.asStateFlow()

    private val _signState = MutableStateFlow<ExportState>(ExportState.Idle)
    val signState: StateFlow<ExportState> = _signState.asStateFlow()

    /** True once a PKCS#12 file has been picked and read, awaiting its password (PDF-008). */
    private val _pkcs12AwaitingPassword = MutableStateFlow(false)
    val pkcs12AwaitingPassword: StateFlow<Boolean> = _pkcs12AwaitingPassword.asStateFlow()

    /** Decrypted only transiently, in memory, for a single sign operation — never persisted. */
    private var pendingPkcs12Bytes: ByteArray? = null

    private val _extractState = MutableStateFlow<ExportState>(ExportState.Idle)
    val extractState: StateFlow<ExportState> = _extractState.asStateFlow()

    private val _attachmentExtractState = MutableStateFlow<ExportState>(ExportState.Idle)
    val attachmentExtractState: StateFlow<ExportState> = _attachmentExtractState.asStateFlow()

    private val _printState = MutableStateFlow<PrintState>(PrintState.Idle)
    val printState: StateFlow<PrintState> = _printState.asStateFlow()

    /** True once a PDF has been picked to insert, awaiting its target position (PDF-006). */
    private val _insertAwaitingPosition = MutableStateFlow(false)
    val insertAwaitingPosition: StateFlow<Boolean> = _insertAwaitingPosition.asStateFlow()

    private var pendingInsertUri: Uri? = null

    /**
     * True once opening a document failed with [PdfOpenError.PasswordProtected] (PDF-013) —
     * the prompt stays up until [submitPassword] succeeds or [cancelPasswordPrompt] is
     * called. [_passwordError] is null on the very first prompt and set to a message once a
     * submitted password itself was wrong, distinguishing "needs a password" from "that
     * wasn't it."
     */
    private val _passwordRequired = MutableStateFlow(false)
    val passwordRequired: StateFlow<Boolean> = _passwordRequired.asStateFlow()

    private val _passwordError = MutableStateFlow<String?>(null)
    val passwordError: StateFlow<String?> = _passwordError.asStateFlow()

    private var pendingPasswordSource: PdfSource? = null
    private var pendingPasswordMetadata: DocumentMetadata? = null
    private var pendingPasswordRecentUri: String? = null

    private var pageCount: Int = 0
    private var currentPageIndex: Int = 0
    private var editEngineAvailable: Boolean = false

    /** Set once in [init] from [savedStateHandle], consumed by the first [openSource] after process restoration. */
    private var restorePendingPageIndex: Int? = null

    /**
     * True for the lifetime of a document that [pdfEditEngine] could open (with the right
     * password) but `android.graphics.pdf.PdfRenderer` (behind [pdfEngine]) could never open
     * at all, since it has no way to accept a password at this app's minSdk — see
     * planning/ADR.md ADR-020. While true, [pageBitmap] renders through [pdfEditEngine]
     * instead of [pdfEngine]; every other document is unaffected.
     */
    private var usingFallbackRenderer: Boolean = false

    /** Normalized marker positions per page index, this session only (not read back from the file). */
    private val annotationMarkersByPage = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()

    /** Highlight markers per page index, this session only — see [addHighlightAt]. PDF-005. */
    private val highlightMarkersByPage = mutableMapOf<Int, MutableList<HighlightMarker>>()

    /** A stable key for the currently open document, for [SignatureRepository]'s placement memory — see [openSource]. PDF-007. */
    private var currentDocumentKey: String? = null

    private val _lastPlacementForDocument = MutableStateFlow<SignaturePlacement?>(null)
    val lastPlacementForDocument: StateFlow<SignaturePlacement?> = _lastPlacementForDocument.asStateFlow()

    /**
     * Records, in add order, every session-added note/highlight annotation per page — its
     * [PageAnnotationRecord.engineIndex] (the annotation's index into `PDPage.getAnnotations()`
     * at the moment it was added, so deleting it later targets the right one regardless of
     * any pre-existing annotations already in the file) plus enough to also remove its UI
     * marker. [undoLastAnnotation] only ever pops the last entry, so this never needs to
     * re-derive an index after an earlier one shifted — each new addition is always appended
     * after the current last entry, same list-append order PDFBox itself uses.
     */
    private data class PageAnnotationRecord(
        val engineIndex: Int,
        val isNote: Boolean,
        val xFraction: Float,
        val yFraction: Float,
    )

    private val annotationHistoryByPage = mutableMapOf<Int, MutableList<PageAnnotationRecord>>()

    /** Visual-only rotation feedback per page index, this session only — see [rotateCurrentPage]. */
    private val pageRotationsDegrees = mutableMapOf<Int, Int>()

    /** Signatures stamped this session, per page index — see [placeSignatureAt]. */
    private val signatureStampsByPage = mutableMapOf<Int, MutableList<SignatureStamp>>()

    /** Existing digital signatures found in the currently open document — see [openSource]. PDF-008. */
    private var signatureVerifications: List<SignatureVerificationResult> = emptyList()

    /** AcroForm fields and embedded-file attachments found in the currently open document — see [openSource]. PDF-009. */
    private var formFields: List<PdfFormField> = emptyList()
    private var attachments: List<PdfAttachment> = emptyList()

    /** The currently open document's outline/bookmark tree — see [openSource]. PDF-002/PDF-004. */
    private var outline: List<PdfOutlineItem> = emptyList()

    /** Results of the most recent [searchText] call, this session only — PDF-003. */
    private val _searchResults = MutableStateFlow<List<PdfSearchMatch>>(emptyList())
    val searchResults: StateFlow<List<PdfSearchMatch>> = _searchResults.asStateFlow()

    private val _fullScreen = MutableStateFlow(false)
    val fullScreen: StateFlow<Boolean> = _fullScreen.asStateFlow()

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
        val restoredUri = savedStateHandle.get<String>(KEY_LAST_OPEN_URI)
        if (restoredUri != null) {
            // PDF-013/process-death restoration: reopen the document the user had open
            // before the process was killed in the background, instead of the bundled
            // fixture — [restorePendingPageIndex] is consumed once rendering succeeds, in
            // [openSource], so a subsequent fresh [openDocument] doesn't also jump pages.
            restorePendingPageIndex = savedStateHandle.get<Int>(KEY_LAST_PAGE_INDEX)
            openDocument(Uri.parse(restoredUri))
        } else if (loadFixtureOnEmptyStartup) {
            loadFixture()
        } else {
            _uiState.value = ReaderUiState.Empty
        }
        viewModelScope.launch { refreshSavedSignatures() }
        viewModelScope.launch { refreshSigningIdentities() }
    }

    private suspend fun refreshSavedSignatures() {
        val signatures = signatureRepository.listAll()
        _savedSignatures.value = signatures
        if (_selectedSignatureId.value == null) {
            _selectedSignatureId.value = signatures.lastOrNull()?.id
        }
    }

    private suspend fun refreshSigningIdentities() {
        _signingIdentities.value = signingIdentityManager.listIdentities()
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
        savedStateHandle[KEY_LAST_OPEN_URI] = uri.toString()
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

    /** Jumps directly to [pageIndex] (0-based), e.g. from the thumbnail strip or outline. */
    fun jumpToPage(pageIndex: Int) {
        goToPage(pageIndex)
    }

    /** PDF-002/PDF-004: toggles a chrome-free, full-screen reading mode. */
    fun toggleFullScreen() {
        _fullScreen.value = !_fullScreen.value
    }

    /**
     * PDF-003: searches the currently open document for [query] via [pdfEditEngine],
     * publishing results to [searchResults]. No-ops (clearing any previous results) if the
     * edit engine isn't available for this document or [query] is blank.
     */
    fun searchText(query: String) {
        if (!editEngineAvailable || query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _searchResults.value = pdfEditEngine.searchText(query)
        }
    }

    fun clearSearchResults() {
        _searchResults.value = emptyList()
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
            val engineIndexBefore = pdfEditEngine.annotationCount(pageIndex)
            val added = pdfEditEngine.addNoteAnnotation(pageIndex, xFraction, yFraction, text)
            if (added) {
                annotationMarkersByPage.getOrPut(pageIndex) { mutableListOf() }.add(xFraction to yFraction)
                annotationHistoryByPage.getOrPut(pageIndex) { mutableListOf() }
                    .add(PageAnnotationRecord(engineIndexBefore, isNote = true, xFraction, yFraction))
                publishAnnotationState(pageIndex)
            }
        }
    }

    /**
     * Adds a highlight annotation to the current page at top-left ([xFraction], [yFraction])
     * with size ([widthFraction], [heightFraction]) — all `0f..1f` of the displayed page,
     * top-down. No-ops if the edit engine isn't available. PDF-005.
     */
    fun addHighlightAt(
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float = DEFAULT_HIGHLIGHT_WIDTH_FRACTION,
        heightFraction: Float = DEFAULT_HIGHLIGHT_HEIGHT_FRACTION,
    ) {
        if (!editEngineAvailable) return
        val pageIndex = currentPageIndex
        viewModelScope.launch {
            val engineIndexBefore = pdfEditEngine.annotationCount(pageIndex)
            val added = pdfEditEngine.addHighlightAnnotation(pageIndex, xFraction, yFraction, widthFraction, heightFraction)
            if (added) {
                highlightMarkersByPage.getOrPut(pageIndex) { mutableListOf() }
                    .add(HighlightMarker(xFraction, yFraction, widthFraction, heightFraction))
                annotationHistoryByPage.getOrPut(pageIndex) { mutableListOf() }
                    .add(PageAnnotationRecord(engineIndexBefore, isNote = false, xFraction, yFraction))
                publishAnnotationState(pageIndex)
            }
        }
    }

    /**
     * Removes the most recently added note or highlight annotation (whichever was added
     * last, across both types) on the current page. No-ops if there's nothing to undo on
     * this page, or the edit engine isn't available. PDF-005.
     */
    fun undoLastAnnotation() {
        if (!editEngineAvailable) return
        val pageIndex = currentPageIndex
        val history = annotationHistoryByPage[pageIndex]
        val record = history?.lastOrNull() ?: return
        viewModelScope.launch {
            val deleted = pdfEditEngine.deleteAnnotation(pageIndex, record.engineIndex)
            if (deleted) {
                history.removeAt(history.size - 1)
                if (record.isNote) {
                    annotationMarkersByPage[pageIndex]?.remove(record.xFraction to record.yFraction)
                } else {
                    highlightMarkersByPage[pageIndex]?.removeAll {
                        it.xFraction == record.xFraction && it.yFraction == record.yFraction
                    }
                }
                publishAnnotationState(pageIndex)
            }
        }
    }

    /** Republishes [ReaderUiState.Loaded]'s annotation-related fields for [pageIndex], if it's the one currently shown. */
    private fun publishAnnotationState(pageIndex: Int) {
        val current = _uiState.value
        if (current is ReaderUiState.Loaded && current.pageIndex == pageIndex) {
            _uiState.value = current.copy(
                annotationMarkers = annotationMarkersByPage[pageIndex].orEmpty(),
                highlightMarkers = highlightMarkersByPage[pageIndex].orEmpty(),
                canUndoAnnotation = annotationHistoryByPage[pageIndex].orEmpty().isNotEmpty(),
            )
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

    /**
     * Prepares a validated temp copy (staged edits included, same as [exportAnnotatedCopy])
     * for the Android print framework to stream to whatever printer/output the user picks
     * in the system print dialog (PDF-010). The caller owns [PrintState.Ready.file] once
     * this resolves — it must delete it once the print job is handed off or fails, since
     * there is no destination picker step here to drive that cleanup.
     */
    fun requestPrint(documentName: String) {
        if (!editEngineAvailable) return
        viewModelScope.launch {
            _printState.value = PrintState.Preparing
            val tempFile = pdfEditEngine.writeValidatedTempCopy()
            _printState.value = if (tempFile != null) {
                PrintState.Ready(tempFile, documentName)
            } else {
                PrintState.Failure("Could not prepare this document for printing.")
            }
        }
    }

    fun acknowledgePrintState() {
        _printState.value = PrintState.Idle
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

    /**
     * Moves the current page to ([targetPageNumber1Based] - 1) (0-based internally; the UI
     * works in 1-based page numbers). Staged like [deleteCurrentPage] — [pdfEngine] keeps
     * rendering pages by their original index, so the thumbnail strip/page view does not
     * visually reorder; only [exportAnnotatedCopy] actually saves the new order. No-ops if
     * [targetPageNumber1Based] is out of range or equal to the page's current position.
     */
    fun movePageTo(targetPageNumber1Based: Int) {
        if (!editEngineAvailable) return
        val targetIndex = targetPageNumber1Based - 1
        if (targetIndex < 0 || targetIndex >= pageCount || targetIndex == currentPageIndex) return
        val fromIndex = currentPageIndex
        viewModelScope.launch {
            _pageOpMessage.value = if (pdfEditEngine.movePage(fromIndex, targetIndex)) {
                "Page moved. Export a copy to save this change."
            } else {
                "Could not move this page."
            }
        }
    }

    /** Appends every page of [uri] to the end of the document (PDF-006 merge). Staged like [deleteCurrentPage]. */
    fun mergeDocument(uri: Uri) {
        if (!editEngineAvailable) return
        pageOpJob?.cancel()
        pageOpJob = viewModelScope.launch {
            _pageOpInProgress.value = true
            try {
                _pageOpMessage.value = if (pdfEditEngine.appendDocument(PdfSource.ContentUri(uri))) {
                    "Pages added. Export a copy to save this change."
                } else {
                    "Could not add pages from that document."
                }
            } catch (e: CancellationException) {
                _pageOpMessage.value = "Cancelled."
                throw e
            } finally {
                _pageOpInProgress.value = false
            }
        }
    }

    /** Cancels a running [mergeDocument]/[insertDocumentAt] — cooperative, see [pageOpInProgress]'s doc. PDF-006. */
    fun cancelPageOp() {
        pageOpJob?.cancel()
    }

    /** Remembers [uri] (a picked PDF to insert) and signals [insertAwaitingPosition] so the UI can ask where. PDF-006. */
    fun pickedDocumentToInsert(uri: Uri) {
        pendingInsertUri = uri
        _insertAwaitingPosition.value = true
    }

    /** Cancels an in-progress insert, forgetting the picked document. PDF-006. */
    fun cancelInsert() {
        pendingInsertUri = null
        _insertAwaitingPosition.value = false
    }

    /**
     * Inserts the previously-picked document's pages at [atPageNumber1Based] (1-based; the
     * UI works in 1-based page numbers, same as [movePageTo]). Staged like [mergeDocument] —
     * [pdfEngine] keeps rendering by the original page indices, so nothing visually shifts
     * until the user exports a copy.
     */
    fun insertDocumentAt(atPageNumber1Based: Int) {
        val uri = pendingInsertUri
        _insertAwaitingPosition.value = false
        pendingInsertUri = null
        if (!editEngineAvailable || uri == null) return
        val atIndex = atPageNumber1Based - 1
        if (atIndex < 0 || atIndex > pageCount) return
        pageOpJob?.cancel()
        pageOpJob = viewModelScope.launch {
            _pageOpInProgress.value = true
            try {
                _pageOpMessage.value = if (pdfEditEngine.insertDocument(PdfSource.ContentUri(uri), atIndex)) {
                    "Pages inserted. Export a copy to save this change."
                } else {
                    "Could not insert pages from that document."
                }
            } catch (e: CancellationException) {
                _pageOpMessage.value = "Cancelled."
                throw e
            } finally {
                _pageOpInProgress.value = false
            }
        }
    }

    /**
     * Extracts the current page into a brand-new PDF and commits it to [destination] — a
     * copy, not a move: the current page stays in the open document (pair with
     * [deleteCurrentPage] to actually remove it). Unlike the other page operations, this
     * never mutates the open document, so there is nothing to export later — the extracted
     * file is written out immediately, same "write temp, validate, commit" shape as
     * [exportAnnotatedCopy]/[finishSigning]. PDF-006.
     */
    fun extractCurrentPageTo(destination: Uri) {
        if (!editEngineAvailable) return
        val pageIndex = currentPageIndex
        viewModelScope.launch {
            _extractState.value = ExportState.Saving
            val extracted = pdfEditEngine.extractPages(listOf(pageIndex))
            if (extracted == null) {
                _extractState.value = ExportState.Failure("Could not extract this page.")
                return@launch
            }
            val committed = destinationWriter.writeFileToUri(extracted, destination)
            extracted.delete()
            _extractState.value = if (committed) {
                ExportState.Success
            } else {
                ExportState.Failure("Could not save the extracted page.")
            }
        }
    }

    fun acknowledgeExtractState() {
        _extractState.value = ExportState.Idle
    }

    /**
     * Sets an AcroForm field's value (PDF-009). Unlike the page-structure operations above,
     * this is **not** staged — PDFBox's `PDField.setValue` writes straight into the live
     * in-memory document, same as [addNoteAnnotation], and [formFields] is refetched
     * immediately afterward so the UI reflects the new value without needing an export.
     */
    fun setFormFieldValue(fullyQualifiedName: String, value: String) {
        if (!editEngineAvailable) return
        viewModelScope.launch {
            if (pdfEditEngine.setFormFieldValue(fullyQualifiedName, value)) {
                formFields = pdfEditEngine.listFormFields()
                val current = _uiState.value
                if (current is ReaderUiState.Loaded) {
                    _uiState.value = current.copy(formFields = formFields)
                }
            }
        }
    }

    /**
     * Extracts [name]'s attached-file bytes to [destination] (PDF-009) — never mutates the
     * open document (same shape as [extractCurrentPageTo]), so it writes out immediately
     * rather than staging anything.
     */
    fun extractAttachmentTo(name: String, destination: Uri) {
        if (!editEngineAvailable) return
        viewModelScope.launch {
            _attachmentExtractState.value = ExportState.Saving
            val extracted = pdfEditEngine.extractAttachment(name)
            if (extracted == null) {
                _attachmentExtractState.value = ExportState.Failure("Could not extract this attachment.")
                return@launch
            }
            val committed = destinationWriter.writeFileToUri(extracted, destination)
            extracted.delete()
            _attachmentExtractState.value = if (committed) {
                ExportState.Success
            } else {
                ExportState.Failure("Could not save the attachment.")
            }
        }
    }

    fun acknowledgeAttachmentExtractState() {
        _attachmentExtractState.value = ExportState.Idle
    }

    /**
     * Extracts every attachment in the open document at once into the directory [treeUri]
     * (from `ACTION_OPEN_DOCUMENT_TREE`) — batch counterpart to [extractAttachmentTo] for a
     * document with several attachments, avoiding a separate destination picker per file.
     * Reports overall success only if every attachment was both extracted and written.
     */
    fun extractAllAttachmentsTo(treeUri: Uri) {
        if (!editEngineAvailable) return
        viewModelScope.launch {
            _attachmentExtractState.value = ExportState.Saving
            val extracted = pdfEditEngine.extractAllAttachments()
            if (extracted.isEmpty()) {
                _attachmentExtractState.value = ExportState.Failure("No attachments to extract.")
                return@launch
            }
            var allCommitted = true
            for ((name, file) in extracted) {
                val committed = destinationWriter.writeFileIntoTree(file, treeUri, name)
                file.delete()
                if (!committed) allCommitted = false
            }
            _attachmentExtractState.value = if (allCommitted) {
                ExportState.Success
            } else {
                ExportState.Failure("Could not save every attachment.")
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
            persistSignature(removeLightBackground(bitmap))
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
    fun placeSignatureAt(xFraction: Float, yFraction: Float, widthFraction: Float = DEFAULT_SIGNATURE_WIDTH_FRACTION) {
        if (!editEngineAvailable) return
        val signature = _savedSignatures.value.firstOrNull { it.id == _selectedSignatureId.value } ?: return
        val pageIndex = currentPageIndex
        val documentKey = currentDocumentKey
        viewModelScope.launch {
            val pngBytes = ByteArrayOutputStream().use { stream ->
                signature.bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                stream.toByteArray()
            }
            val placed = pdfEditEngine.addImageStamp(pageIndex, xFraction, yFraction, widthFraction, pngBytes)
            if (placed) {
                val stamp = SignatureStamp(xFraction, yFraction, widthFraction, signature.bitmap)
                signatureStampsByPage.getOrPut(pageIndex) { mutableListOf() }.add(stamp)
                val current = _uiState.value
                if (current is ReaderUiState.Loaded && current.pageIndex == pageIndex) {
                    _uiState.value = current.copy(signatureStamps = signatureStampsByPage[pageIndex].orEmpty())
                }
                if (documentKey != null) {
                    val placement = SignaturePlacement(xFraction, yFraction, widthFraction)
                    signatureRepository.saveLastPlacement(documentKey, placement)
                    _lastPlacementForDocument.value = placement
                }
            }
        }
    }

    /**
     * Places the selected signature at this document's remembered position/size — PDF-007
     * placement memory — instead of requiring a fresh tap. No-ops if nothing's been placed
     * on a document with this key before, or the edit engine isn't available.
     */
    fun placeSignatureAtLastPosition() {
        val placement = _lastPlacementForDocument.value ?: return
        placeSignatureAt(placement.xFraction, placement.yFraction, placement.widthFraction)
    }

    /** Creates a new device-bound signing identity named [displayName] (PDF-008, DEC-002). */
    fun createSigningIdentity(displayName: String) {
        viewModelScope.launch {
            signingIdentityManager.createIdentity(displayName)
            refreshSigningIdentities()
        }
    }

    /** Permanently deletes signing identity [alias]. It cannot be recovered afterward. PDF-008. */
    fun deleteSigningIdentity(alias: String) {
        viewModelScope.launch {
            signingIdentityManager.deleteIdentity(alias)
            refreshSigningIdentities()
        }
    }

    /** Signs the current document with the Keystore-backed identity [alias] and commits it to [destination]. PDF-008. */
    fun signWithIdentity(alias: String, destination: Uri) {
        if (!editEngineAvailable) return
        viewModelScope.launch {
            _signState.value = ExportState.Saving
            val material = signingIdentityManager.loadSigningMaterial(alias)
            if (material == null) {
                _signState.value = ExportState.Failure("That signing identity is no longer available.")
                return@launch
            }
            val displayName = _signingIdentities.value.firstOrNull { it.alias == alias }?.displayName ?: alias
            finishSigning(displayName, material.first, material.second, destination)
        }
    }

    /** Reads [uri] into memory and signals [pkcs12AwaitingPassword] so the UI can prompt for its password. PDF-008. */
    fun importPkcs12(uri: Uri) {
        viewModelScope.launch {
            val bytes = bytesUriReader.read(uri)
            if (bytes == null) {
                _signState.value = ExportState.Failure("Could not read that file.")
                return@launch
            }
            pendingPkcs12Bytes = bytes
            _pkcs12AwaitingPassword.value = true
        }
    }

    /** Cancels an in-progress PKCS#12 import, discarding any bytes already read. PDF-008. */
    fun cancelPkcs12Import() {
        pendingPkcs12Bytes = null
        _pkcs12AwaitingPassword.value = false
    }

    /**
     * Decrypts the previously-imported PKCS#12 file with [password], signs the current
     * document with it, and commits the result to [destination] — [password] and the
     * decrypted key material are never retained past this single call. PDF-008.
     */
    fun signWithImportedPkcs12(password: CharArray, destination: Uri) {
        if (!editEngineAvailable) return
        val bytes = pendingPkcs12Bytes
        _pkcs12AwaitingPassword.value = false
        pendingPkcs12Bytes = null
        if (bytes == null) {
            _signState.value = ExportState.Failure("No certificate file was selected.")
            return
        }
        viewModelScope.launch {
            _signState.value = ExportState.Saving
            val material = pkcs12Loader.load(bytes, password)
            if (material == null) {
                _signState.value = ExportState.Failure("Could not open that certificate — check the password.")
                return@launch
            }
            finishSigning("Imported certificate", material.first, material.second, destination)
        }
    }

    private suspend fun finishSigning(
        signerName: String,
        privateKey: PrivateKey,
        certificateChain: List<X509Certificate>,
        destination: Uri,
    ) {
        val signedTemp = pdfEditEngine.signWithDetachedCms(signerName, privateKey, certificateChain)
        if (signedTemp == null) {
            _signState.value = ExportState.Failure("Could not sign this document.")
            return
        }
        val committed = destinationWriter.writeFileToUri(signedTemp, destination)
        signedTemp.delete()
        _signState.value = if (committed) {
            ExportState.Success
        } else {
            ExportState.Failure("Could not save the signed copy.")
        }
    }

    fun acknowledgeSignState() {
        _signState.value = ExportState.Idle
    }

    /**
     * Renders (or returns a cached render of) [pageIndex] at [widthPx]. Used by the
     * thumbnail strip and continuous-scroll layout, which each need page images outside
     * the single "current page" slot that [ReaderUiState.Loaded] carries.
     */
    suspend fun pageBitmap(pageIndex: Int, widthPx: Int): Bitmap? {
        val key = "$pageIndex@$widthPx"
        pageCacheLock.withLock { pageCache[key] }?.let { return it }

        val rendered = if (usingFallbackRenderer) {
            pdfEditEngine.renderPage(pageIndex, widthPx)
        } else {
            pdfEngine.renderPage(pageIndex, widthPx)
        } ?: return null
        pageCacheLock.withLock { pageCache[key] = rendered }
        return rendered
    }

    private fun goToPage(targetIndex: Int) {
        if (targetIndex < 0 || targetIndex >= pageCount || targetIndex == currentPageIndex) return
        viewModelScope.launch {
            renderAndPublish(targetIndex)
        }
    }

    /**
     * Consumes [restorePendingPageIndex] (set once in [init] from a restored process-death
     * save) if it's still within [pageCount] for the document that just opened — a stale
     * index from a differently-sized document is silently ignored rather than crashing or
     * clamping to a confusing page.
     */
    private fun restoredPageIndexOrZero(): Int {
        val pending = restorePendingPageIndex
        restorePendingPageIndex = null
        return if (pending != null && pending in 0 until pageCount) pending else 0
    }

    private suspend fun openSource(source: PdfSource, metadata: DocumentMetadata, recentUriToRecord: String?) {
        _uiState.value = ReaderUiState.Loading
        pageCacheLock.withLock { pageCache.clear() }
        annotationMarkersByPage.clear()
        highlightMarkersByPage.clear()
        annotationHistoryByPage.clear()
        pageRotationsDegrees.clear()
        signatureStampsByPage.clear()
        _pageOpMessage.value = null
        usingFallbackRenderer = false
        _searchResults.value = emptyList()
        pendingPasswordSource = null
        pendingPasswordMetadata = null
        pendingPasswordRecentUri = null
        _passwordRequired.value = false
        _passwordError.value = null

        currentDocumentKey = recentUriToRecord ?: metadata.displayName
        _lastPlacementForDocument.value = currentDocumentKey?.let { signatureRepository.getLastPlacement(it) }

        val editOpenResult = pdfEditEngine.open(source)
        editEngineAvailable = editOpenResult is PdfOpenResult.Success
        // Computed once per document open, not staged/session-only like the edit features
        // above — this reports what's actually already in the file (PDF-008 "validate").
        signatureVerifications = if (editEngineAvailable) pdfEditEngine.verifySignatures() else emptyList()
        formFields = if (editEngineAvailable) pdfEditEngine.listFormFields() else emptyList()
        attachments = if (editEngineAvailable) pdfEditEngine.listAttachments() else emptyList()
        outline = if (editEngineAvailable) pdfEditEngine.listOutline() else emptyList()

        val engineResult = pdfEngine.open(source)
        when {
            engineResult is PdfOpenResult.Success -> {
                pageCount = engineResult.document.pageCount
                currentPageIndex = 0
                renderAndPublish(restoredPageIndexOrZero(), metadata)
                recordRecentIfNeeded(recentUriToRecord, metadata)
            }
            // android.graphics.pdf.PdfRenderer has no way to supply a password at this
            // app's minSdk, so it fails here even with the right password — but if the edit
            // engine (which does support passwords) already opened the document, that
            // password was correct. Fall back to its own rendering (ADR-020) instead of
            // treating this as an open failure.
            editOpenResult is PdfOpenResult.Success &&
                engineResult is PdfOpenResult.Failure &&
                engineResult.error is PdfOpenError.PasswordProtected -> {
                usingFallbackRenderer = true
                pageCount = editOpenResult.document.pageCount
                currentPageIndex = 0
                renderAndPublish(restoredPageIndexOrZero(), metadata)
                recordRecentIfNeeded(recentUriToRecord, metadata)
            }
            engineResult is PdfOpenResult.Failure && engineResult.error is PdfOpenError.PasswordProtected -> {
                pageCount = 0
                currentPageIndex = 0
                pendingPasswordSource = source
                pendingPasswordMetadata = metadata
                pendingPasswordRecentUri = recentUriToRecord
                // A password was already tried (and the edit engine, which does support
                // passwords, rejected it too) vs. this being the first prompt for this file.
                _passwordError.value = if (source.password != null) "Incorrect password." else null
                _passwordRequired.value = true
                _uiState.value = ReaderUiState.Error(engineResult.error)
            }
            else -> {
                pageCount = 0
                currentPageIndex = 0
                _uiState.value = ReaderUiState.Error((engineResult as PdfOpenResult.Failure).error)
            }
        }
    }

    private suspend fun recordRecentIfNeeded(recentUriToRecord: String?, metadata: DocumentMetadata) {
        if (recentUriToRecord == null) return
        recentDocumentsRepository.recordOpened(
            uri = recentUriToRecord,
            displayName = metadata.displayName,
            pageCount = pageCount,
            openedAtEpochMillis = clock(),
        )
    }

    /** Retries the document that triggered [passwordRequired] with a user-entered password (PDF-013). */
    fun submitPassword(password: String) {
        val source = pendingPasswordSource ?: return
        val metadata = pendingPasswordMetadata ?: return
        val recentUri = pendingPasswordRecentUri
        viewModelScope.launch {
            openSource(source.withPassword(password), metadata, recentUri)
        }
    }

    fun cancelPasswordPrompt() {
        pendingPasswordSource = null
        pendingPasswordMetadata = null
        pendingPasswordRecentUri = null
        _passwordRequired.value = false
        _passwordError.value = null
    }

    private suspend fun renderAndPublish(pageIndex: Int, metadata: DocumentMetadata? = null) {
        val bitmap = pageBitmap(pageIndex, TARGET_RENDER_WIDTH_PX)
        val currentMetadata = metadata ?: (_uiState.value as? ReaderUiState.Loaded)?.documentMetadata
        if (bitmap != null) {
            currentPageIndex = pageIndex
            if (savedStateHandle.get<String>(KEY_LAST_OPEN_URI) != null) {
                savedStateHandle[KEY_LAST_PAGE_INDEX] = pageIndex
            }
            _uiState.value = ReaderUiState.Loaded(
                pageIndex = pageIndex,
                pageCount = pageCount,
                pageBitmap = bitmap,
                documentMetadata = currentMetadata,
                annotationMarkers = annotationMarkersByPage[pageIndex].orEmpty(),
                canAnnotate = editEngineAvailable,
                pageRotationDegrees = pageRotationsDegrees.getOrDefault(pageIndex, 0),
                signatureStamps = signatureStampsByPage[pageIndex].orEmpty(),
                signatureVerifications = signatureVerifications,
                formFields = formFields,
                attachments = attachments,
                outline = outline,
                highlightMarkers = highlightMarkersByPage[pageIndex].orEmpty(),
                canUndoAnnotation = annotationHistoryByPage[pageIndex].orEmpty().isNotEmpty(),
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
