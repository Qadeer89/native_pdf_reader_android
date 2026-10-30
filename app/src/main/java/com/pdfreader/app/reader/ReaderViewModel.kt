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

    private val _signingIdentities = MutableStateFlow<List<SigningIdentity>>(emptyList())
    val signingIdentities: StateFlow<List<SigningIdentity>> = _signingIdentities.asStateFlow()

    private val _signState = MutableStateFlow<ExportState>(ExportState.Idle)
    val signState: StateFlow<ExportState> = _signState.asStateFlow()

    /** True once a PKCS#12 file has been picked and read, awaiting its password (PDF-008). */
    private val _pkcs12AwaitingPassword = MutableStateFlow(false)
    val pkcs12AwaitingPassword: StateFlow<Boolean> = _pkcs12AwaitingPassword.asStateFlow()

    /** Decrypted only transiently, in memory, for a single sign operation — never persisted. */
    private var pendingPkcs12Bytes: ByteArray? = null

    private var pageCount: Int = 0
    private var currentPageIndex: Int = 0
    private var editEngineAvailable: Boolean = false

    /** Normalized marker positions per page index, this session only (not read back from the file). */
    private val annotationMarkersByPage = mutableMapOf<Int, MutableList<Pair<Float, Float>>>()

    /** Visual-only rotation feedback per page index, this session only — see [rotateCurrentPage]. */
    private val pageRotationsDegrees = mutableMapOf<Int, Int>()

    /** Signatures stamped this session, per page index — see [placeSignatureAt]. */
    private val signatureStampsByPage = mutableMapOf<Int, MutableList<SignatureStamp>>()

    /** Existing digital signatures found in the currently open document — see [openSource]. PDF-008. */
    private var signatureVerifications: List<SignatureVerificationResult> = emptyList()

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
        // Computed once per document open, not staged/session-only like the edit features
        // above — this reports what's actually already in the file (PDF-008 "validate").
        signatureVerifications = if (editEngineAvailable) pdfEditEngine.verifySignatures() else emptyList()

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
                signatureVerifications = signatureVerifications,
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
