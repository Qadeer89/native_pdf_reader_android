package com.pdfreader.app

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pdfreader.app.data.documents.DestinationWriter
import com.pdfreader.app.data.documents.DocumentMetadata
import com.pdfreader.app.data.documents.DocumentMetadataResolver
import com.pdfreader.app.data.recents.RecentDocumentsDatabase
import com.pdfreader.app.data.recents.RecentDocumentsRepository
import com.pdfreader.app.data.signatures.ImageUriDecoder
import com.pdfreader.app.data.signatures.SavedSignature
import com.pdfreader.app.data.signatures.SignatureRepository
import com.pdfreader.app.model.PdfDocumentHandle
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfEditEngine
import com.pdfreader.app.pdfengine.PdfEngine
import com.pdfreader.app.reader.ExportState
import com.pdfreader.app.reader.PageLayoutMode
import com.pdfreader.app.reader.ReaderUiState
import com.pdfreader.app.reader.ReaderViewModel
import com.pdfreader.app.signing.BytesUriReader
import com.pdfreader.app.signing.Pkcs12SigningMaterialLoader
import com.pdfreader.app.signing.SigningIdentity
import com.pdfreader.app.signing.SigningIdentityManager
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private const val FIXTURE_PAGE_COUNT = 1
private const val MULTI_PAGE_COUNT = 3
private const val FAKE_RENDER_WIDTH = 400
private const val FAKE_RENDER_HEIGHT = 500

/** A throwaway key/certificate pair for signing-related fakes — never inspected by them. */
private fun generateTestKeyAndCertificate(): Pair<PrivateKey, X509Certificate> {
    if (java.security.Security.getProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME) == null) {
        java.security.Security.addProvider(org.bouncycastle.jce.provider.BouncyCastleProvider())
    }
    val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val now = System.currentTimeMillis()
    val subject = org.bouncycastle.asn1.x500.X500Name("CN=Fake Test Signer")
    val certBuilder = org.bouncycastle.cert.X509v3CertificateBuilder(
        subject,
        java.math.BigInteger.valueOf(now),
        java.util.Date(now),
        java.util.Date(now + 365L * 24 * 60 * 60 * 1000),
        subject,
        org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
    )
    val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
    val certificate = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
        .setProvider(org.bouncycastle.jce.provider.BouncyCastleProvider.PROVIDER_NAME)
        .getCertificate(certBuilder.build(signer))
    return keyPair.private to certificate
}

/**
 * Phase 1/2 vertical-slice smoke tests (MASTER_PROMPT.md workflow step 3), actually
 * executed on the JVM via Robolectric in this environment (no Android emulator or
 * physical device is available here; see planning/ADR.md ADR-006).
 *
 * These exercise the real [AssetPdfCopier] (copies the bundled fixture out of app
 * assets into app-private cache) and the real [ReaderViewModel] state-machine
 * (open, page navigation), wired to a [FakePdfEngine] test double standing in for
 * `android.graphics.pdf.PdfRenderer`.
 *
 * The real `PlatformPdfRendererEngine` (backed by the actual platform `PdfRenderer`) is
 * deliberately NOT exercised here: empirically, Robolectric ships no shadow or native
 * binding for `PdfRenderer` in this toolchain (`shadows-framework` and `nativeruntime`
 * jars for Robolectric 4.13 contain no PDF-related classes), so `PdfRenderer.pageCount`
 * reads back as 0 under Robolectric regardless of the input file's validity. That real
 * engine is covered instead by `PdfRenderInstrumentedTest` in `src/androidTest`, which
 * requires `./gradlew connectedAndroidTest` against a real device/emulator — not
 * available in this environment and therefore not executed in this pass (see the
 * milestone report).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfReaderViewModelSmokeTest {

    /**
     * [ReaderViewModel.recentDocuments] stays subscribed to its Room `Flow` for as long as
     * `viewModelScope` is alive. Unlike production (where the Activity/ViewModelProvider owns
     * that lifecycle), nothing here calls `ViewModel.clear()` unless we do it ourselves — so a
     * plain in-memory Room database created in a test and then closed out from under a still-
     * running collector logs a Robolectric `CloseGuard` resource-leak warning (harmless to test
     * results, but noisy, and worth tearing down properly rather than leaving dangling). Each
     * ViewModel built by [newViewModel] is registered in this store so [tearDown] can clear it
     * (cancelling its `viewModelScope`) before the matching database is closed.
     */
    private val viewModelStore = ViewModelStore()
    private val openDatabases = mutableListOf<RecentDocumentsDatabase>()

    @After
    fun tearDown() {
        viewModelStore.clear()
        openDatabases.forEach { it.close() }
        openDatabases.clear()
    }

    /** Builds a [ReaderViewModel] with a real (in-memory) recents DB and fakes for everything else. */
    private fun newViewModel(
        context: Context,
        engine: PdfEngine,
        editEngine: PdfEditEngine = FakePdfEditEngine(),
        destinationWriter: DestinationWriter = FakeDestinationWriter(),
        signatureRepository: SignatureRepository = FakeSignatureRepository(),
        imageUriDecoder: ImageUriDecoder = FakeImageUriDecoder(),
        signingIdentityManager: SigningIdentityManager = FakeSigningIdentityManager(),
        pkcs12Loader: Pkcs12SigningMaterialLoader = FakePkcs12SigningMaterialLoader(),
        bytesUriReader: BytesUriReader = FakeBytesUriReader(),
    ): ReaderViewModel {
        val db = Room.inMemoryDatabaseBuilder(context, RecentDocumentsDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        openDatabases += db
        val viewModel = ReaderViewModel(
            pdfEngine = engine,
            pdfEditEngine = editEngine,
            assetPdfCopier = AssetPdfCopier(context, ioDispatcher = kotlinx.coroutines.Dispatchers.Unconfined),
            recentDocumentsRepository = RecentDocumentsRepository(db.recentDocumentDao()),
            metadataResolver = FakeDocumentMetadataResolver(),
            destinationWriter = destinationWriter,
            signatureRepository = signatureRepository,
            imageUriDecoder = imageUriDecoder,
            signingIdentityManager = signingIdentityManager,
            pkcs12Loader = pkcs12Loader,
            bytesUriReader = bytesUriReader,
        )
        viewModelStore.put(db.hashCode().toString(), viewModel)
        return viewModel
    }

    @Test
    fun opensFixtureAndRendersFirstPageThroughViewModel() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        val state = viewModel.uiState.value
        assertTrue("expected Loaded state but got $state", state is ReaderUiState.Loaded)
        val loaded = state as ReaderUiState.Loaded

        assertEquals(0, loaded.pageIndex)
        assertEquals(FIXTURE_PAGE_COUNT, loaded.pageCount)
        assertEquals(FAKE_RENDER_WIDTH, loaded.pageBitmap.width)
        assertEquals(FAKE_RENDER_HEIGHT, loaded.pageBitmap.height)

        val openedSource = fakeEngine.lastOpenedSource
        assertTrue("expected a LocalFile source but got $openedSource", openedSource is PdfSource.LocalFile)
        assertTrue(
            "AssetPdfCopier should have actually copied a non-empty fixture file",
            (openedSource as PdfSource.LocalFile).file.length() > 0,
        )
    }

    @Test
    fun nextAndPreviousPageNavigateWithinBounds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = MULTI_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        viewModel.nextPage()
        var loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(1, loaded.pageIndex)

        viewModel.nextPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(2, loaded.pageIndex)

        // Already on the last page (index 2 of 3) — next is a no-op.
        viewModel.nextPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(2, loaded.pageIndex)

        viewModel.previousPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(1, loaded.pageIndex)

        viewModel.previousPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(0, loaded.pageIndex)

        // Already on the first page — previous is a no-op.
        viewModel.previousPage()
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(0, loaded.pageIndex)
    }

    @Test
    fun openDocumentOpensAContentUriSource() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        val pickedUri = Uri.parse("content://com.android.providers.downloads.documents/document/42")
        viewModel.openDocument(pickedUri)

        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(0, loaded.pageIndex)

        val openedSource = fakeEngine.lastOpenedSource
        assertTrue("expected a ContentUri source but got $openedSource", openedSource is PdfSource.ContentUri)
        assertEquals(pickedUri, (openedSource as PdfSource.ContentUri).uri)
    }

    @Test
    fun jumpToPageGoesDirectlyToTheRequestedPage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = MULTI_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)

        viewModel.jumpToPage(2)
        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(2, loaded.pageIndex)

        // Out-of-range jumps are ignored rather than crashing or clamping silently.
        viewModel.jumpToPage(99)
        assertEquals(2, (viewModel.uiState.value as ReaderUiState.Loaded).pageIndex)
    }

    @Test
    fun pageBitmapCachesRenderedPagesPerSizeKey() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = MULTI_PAGE_COUNT)

        val viewModel = newViewModel(context, fakeEngine)
        // Opening the document already rendered page 0 at FULL_PAGE_WIDTH once.
        assertEquals(1, fakeEngine.renderCallCount)

        viewModel.pageBitmap(0, ReaderViewModel.FULL_PAGE_WIDTH)
        assertEquals("a cached render should not call the engine again", 1, fakeEngine.renderCallCount)

        viewModel.pageBitmap(0, ReaderViewModel.THUMBNAIL_WIDTH)
        assertEquals("a different size key is not cached yet, so this renders", 2, fakeEngine.renderCallCount)

        viewModel.pageBitmap(1, ReaderViewModel.FULL_PAGE_WIDTH)
        assertEquals("a different page is not cached yet, so this renders", 3, fakeEngine.renderCallCount)
    }

    @Test
    fun toggleLayoutModeFlipsBetweenSingleAndContinuous() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))

        assertEquals(PageLayoutMode.SINGLE, viewModel.layoutMode.value)
        viewModel.toggleLayoutMode()
        assertEquals(PageLayoutMode.CONTINUOUS, viewModel.layoutMode.value)
        viewModel.toggleLayoutMode()
        assertEquals(PageLayoutMode.SINGLE, viewModel.layoutMode.value)
    }

    /**
     * Room's `@Query` [kotlinx.coroutines.flow.Flow] invalidation is delivered by posting
     * back to [ReaderViewModel]'s collector on the main dispatcher from a different
     * (Room executor) thread. Under Robolectric's default paused main Looper, that posted
     * task only runs once something pumps the Looper's queue — so this test idles it in a
     * short poll loop instead of asserting on [ReaderViewModel.recentDocuments]
     * immediately after a write, or relying on virtual test time to move it along.
     */
    @Test
    fun openDocumentRecordsARecentEntryButTheFixtureDoesNot() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT)
        val viewModel = newViewModel(context, fakeEngine)

        // The bundled fixture (loaded on init) must never show up in "Recent".
        assertEquals(emptyList<Any>(), viewModel.recentDocuments.value)

        val pickedUri = Uri.parse("content://com.android.providers.downloads.documents/document/7")
        viewModel.openDocument(pickedUri)

        val recents = awaitRecentDocuments(viewModel) { it.isNotEmpty() }
        assertEquals(1, recents.size)
        assertEquals(pickedUri.toString(), recents.first().uri)
        assertEquals(FIXTURE_PAGE_COUNT, recents.first().pageCount)

        viewModel.clearRecentDocuments()
        val cleared = awaitRecentDocuments(viewModel) { it.isEmpty() }
        assertEquals(emptyList<Any>(), cleared)
    }

    private suspend fun awaitRecentDocuments(
        viewModel: ReaderViewModel,
        predicate: (List<com.pdfreader.app.data.recents.RecentDocumentEntity>) -> Boolean,
    ): List<com.pdfreader.app.data.recents.RecentDocumentEntity> {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val current = viewModel.recentDocuments.value
            if (predicate(current)) return current
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            kotlinx.coroutines.delay(10)
        }
        throw AssertionError("recentDocuments never satisfied the predicate within the timeout")
    }

    @Test
    fun addNoteAtRecordsAMarkerOnlyWhenTheEditEngineIsAvailable() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        var loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertTrue("edit engine opened successfully, so annotating should be available", loaded.canAnnotate)
        assertEquals(emptyList<Pair<Float, Float>>(), loaded.annotationMarkers)

        viewModel.addNoteAt(0.25f, 0.5f)
        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(listOf(0.25f to 0.5f), loaded.annotationMarkers)
        assertEquals(1, editEngine.addedAnnotations.size)
        assertEquals(0, editEngine.addedAnnotations.first().pageIndex)
    }

    @Test
    fun addNoteAtIsANoOpWhenTheEditEngineFailedToOpen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(openSucceeds = false)
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        val loadedBefore = viewModel.uiState.value as ReaderUiState.Loaded
        assertTrue("edit engine failed to open, so annotating must be disabled", !loadedBefore.canAnnotate)

        viewModel.addNoteAt(0.25f, 0.5f)
        val loadedAfter = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(emptyList<Pair<Float, Float>>(), loadedAfter.annotationMarkers)
        assertEquals(0, editEngine.addedAnnotations.size)
    }

    @Test
    fun rotateCurrentPageUpdatesTheVisualRotationFeedback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))

        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Loaded).pageRotationDegrees)

        viewModel.rotateCurrentPage()
        assertEquals(90, (viewModel.uiState.value as ReaderUiState.Loaded).pageRotationDegrees)

        viewModel.rotateCurrentPage()
        viewModel.rotateCurrentPage()
        viewModel.rotateCurrentPage()
        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Loaded).pageRotationDegrees)
    }

    @Test
    fun rotateCurrentPageIsANoOpWhenTheEditEngineFailedToOpen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            editEngine = FakePdfEditEngine(openSucceeds = false),
        )

        viewModel.rotateCurrentPage()
        assertEquals(0, (viewModel.uiState.value as ReaderUiState.Loaded).pageRotationDegrees)
    }

    @Test
    fun deleteCurrentPageReportsStagedFeedback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(initialPageCount = MULTI_PAGE_COUNT)
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = MULTI_PAGE_COUNT), editEngine = editEngine)

        assertEquals(null, viewModel.pageOpMessage.value)
        viewModel.deleteCurrentPage()
        assertTrue(viewModel.pageOpMessage.value?.contains("deleted", ignoreCase = true) == true)
        assertEquals(MULTI_PAGE_COUNT - 1, editEngine.pageCount())

        viewModel.acknowledgePageOpMessage()
        assertEquals(null, viewModel.pageOpMessage.value)
    }

    @Test
    fun movePageToMovesAndReportsStagedFeedback() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(initialPageCount = MULTI_PAGE_COUNT)
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = MULTI_PAGE_COUNT), editEngine = editEngine)

        assertEquals(null, viewModel.pageOpMessage.value)
        viewModel.movePageTo(3)
        assertTrue(viewModel.pageOpMessage.value?.contains("moved", ignoreCase = true) == true)
    }

    @Test
    fun movePageToIsANoOpForTheCurrentPositionOrAnOutOfRangeTarget() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(initialPageCount = MULTI_PAGE_COUNT)
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = MULTI_PAGE_COUNT), editEngine = editEngine)

        viewModel.movePageTo(1) // already on page 1 (index 0)
        assertEquals(null, viewModel.pageOpMessage.value)

        viewModel.movePageTo(0)
        assertEquals(null, viewModel.pageOpMessage.value)

        viewModel.movePageTo(MULTI_PAGE_COUNT + 1)
        assertEquals(null, viewModel.pageOpMessage.value)
    }

    @Test
    fun movePageToIsANoOpWhenTheEditEngineFailedToOpen() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = MULTI_PAGE_COUNT),
            editEngine = FakePdfEditEngine(openSucceeds = false),
        )

        viewModel.movePageTo(2)
        assertEquals(null, viewModel.pageOpMessage.value)
    }

    @Test
    fun mergeDocumentReportsFailureWhenTheEditEngineRejectsIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(appendSucceeds = false)
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        viewModel.mergeDocument(Uri.parse("content://example/document/other"))
        assertTrue(viewModel.pageOpMessage.value?.contains("Could not", ignoreCase = true) == true)
    }

    @Test
    fun insertDocumentAtAwaitsPositionThenInsertsOnSubmit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(initialPageCount = MULTI_PAGE_COUNT)
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = MULTI_PAGE_COUNT), editEngine = editEngine)
        assertEquals(false, viewModel.insertAwaitingPosition.value)

        viewModel.pickedDocumentToInsert(Uri.parse("content://example/document/to-insert"))
        assertEquals(true, viewModel.insertAwaitingPosition.value)

        viewModel.insertDocumentAt(2)
        assertEquals("position prompt should be dismissed once submitted", false, viewModel.insertAwaitingPosition.value)
        assertTrue(viewModel.pageOpMessage.value?.contains("inserted", ignoreCase = true) == true)
    }

    @Test
    fun insertDocumentAtIsANoOpWhenNoDocumentWasPicked() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = MULTI_PAGE_COUNT))

        viewModel.insertDocumentAt(1)

        assertEquals(null, viewModel.pageOpMessage.value)
    }

    @Test
    fun cancelInsertDismissesThePositionPrompt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = MULTI_PAGE_COUNT))
        viewModel.pickedDocumentToInsert(Uri.parse("content://example/document/to-insert"))
        assertEquals(true, viewModel.insertAwaitingPosition.value)

        viewModel.cancelInsert()

        assertEquals(false, viewModel.insertAwaitingPosition.value)
    }

    @Test
    fun extractCurrentPageToSucceedsAndCommitsToTheDestination() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destinationWriter = FakeDestinationWriter(writeSucceeds = true)
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            destinationWriter = destinationWriter,
        )

        val destination = Uri.parse("content://example/document/extracted")
        viewModel.extractCurrentPageTo(destination)

        val state = awaitExportState(viewModel, viewModel::extractState) { it !is ExportState.Saving }
        assertTrue("expected Success but got $state", state is ExportState.Success)
        assertEquals(destination, destinationWriter.lastDestination)
    }

    @Test
    fun extractCurrentPageToReportsFailureWhenTheEngineRejectsIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A zero-page edit-engine document makes any page index (including the current one,
        // 0) out of range, so FakePdfEditEngine.extractPages reports failure.
        val editEngine = FakePdfEditEngine(initialPageCount = 0)
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        viewModel.extractCurrentPageTo(Uri.parse("content://example/document/extracted"))

        val state = awaitExportState(viewModel, viewModel::extractState) { it !is ExportState.Saving }
        assertTrue("expected Failure but got $state", state is ExportState.Failure)
    }

    @Test
    fun createSigningIdentityAddsItToSigningIdentities() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))
        assertEquals(emptyList<SigningIdentity>(), viewModel.signingIdentities.value)

        viewModel.createSigningIdentity("Jane Doe")

        assertEquals(1, viewModel.signingIdentities.value.size)
        assertEquals("Jane Doe", viewModel.signingIdentities.value.first().displayName)
    }

    @Test
    fun deleteSigningIdentityRemovesIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))
        viewModel.createSigningIdentity("Jane Doe")
        val alias = viewModel.signingIdentities.value.first().alias

        viewModel.deleteSigningIdentity(alias)

        assertEquals(emptyList<SigningIdentity>(), viewModel.signingIdentities.value)
    }

    @Test
    fun signWithIdentitySignsAndCommitsToTheDestination() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destinationWriter = FakeDestinationWriter(writeSucceeds = true)
        val editEngine = FakePdfEditEngine()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            editEngine = editEngine,
            destinationWriter = destinationWriter,
        )
        viewModel.createSigningIdentity("Jane Doe")
        val alias = viewModel.signingIdentities.value.first().alias

        val destination = Uri.parse("content://example/document/signed")
        viewModel.signWithIdentity(alias, destination)

        val state = awaitExportState(viewModel, viewModel::signState) { it !is ExportState.Saving }
        assertTrue("expected Success but got $state", state is ExportState.Success)
        assertEquals(destination, destinationWriter.lastDestination)
        assertEquals(listOf("Jane Doe"), editEngine.signRequests)
    }

    @Test
    fun signWithIdentityFailsWhenTheEngineRejectsTheSignature() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine().apply { signWithDetachedCmsSucceeds = false }
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)
        viewModel.createSigningIdentity("Jane Doe")
        val alias = viewModel.signingIdentities.value.first().alias

        viewModel.signWithIdentity(alias, Uri.parse("content://example/document/signed"))

        val state = awaitExportState(viewModel, viewModel::signState) { it !is ExportState.Saving }
        assertTrue("expected Failure but got $state", state is ExportState.Failure)
    }

    @Test
    fun importPkcs12AwaitsPasswordThenSignsOnSubmit() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destinationWriter = FakeDestinationWriter(writeSucceeds = true)
        val editEngine = FakePdfEditEngine()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            editEngine = editEngine,
            destinationWriter = destinationWriter,
            bytesUriReader = FakeBytesUriReader(result = byteArrayOf(1, 2, 3)),
        )
        assertEquals(false, viewModel.pkcs12AwaitingPassword.value)

        viewModel.importPkcs12(Uri.parse("content://example/certificate.p12"))
        assertEquals(true, viewModel.pkcs12AwaitingPassword.value)

        val destination = Uri.parse("content://example/document/signed")
        viewModel.signWithImportedPkcs12("secret".toCharArray(), destination)

        assertEquals("password prompt should be dismissed once submitted", false, viewModel.pkcs12AwaitingPassword.value)
        val state = awaitExportState(viewModel, viewModel::signState) { it !is ExportState.Saving }
        assertTrue("expected Success but got $state", state is ExportState.Success)
        assertEquals(destination, destinationWriter.lastDestination)
    }

    @Test
    fun signWithImportedPkcs12FailsWhenThePasswordIsWrong() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            pkcs12Loader = FakePkcs12SigningMaterialLoader(loadSucceeds = false),
            bytesUriReader = FakeBytesUriReader(result = byteArrayOf(1, 2, 3)),
        )
        viewModel.importPkcs12(Uri.parse("content://example/certificate.p12"))

        viewModel.signWithImportedPkcs12("wrong".toCharArray(), Uri.parse("content://example/document/signed"))

        val state = awaitExportState(viewModel, viewModel::signState) { it !is ExportState.Saving }
        assertTrue("expected Failure but got $state", state is ExportState.Failure)
    }

    @Test
    fun cancelPkcs12ImportDismissesThePasswordPrompt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            bytesUriReader = FakeBytesUriReader(result = byteArrayOf(1, 2, 3)),
        )
        viewModel.importPkcs12(Uri.parse("content://example/certificate.p12"))
        assertEquals(true, viewModel.pkcs12AwaitingPassword.value)

        viewModel.cancelPkcs12Import()

        assertEquals(false, viewModel.pkcs12AwaitingPassword.value)
    }

    @Test
    fun openingASignedDocumentExposesItsSignatureVerificationResults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val expected = com.pdfreader.app.model.SignatureVerificationResult(
            signerName = "Jane Doe",
            signDateEpochMillis = 0L,
            cryptographicallyValid = true,
            certificateSubject = "CN=Jane Doe",
            certificateCurrentlyValid = true,
            coversEntireDocument = true,
        )
        val editEngine = FakePdfEditEngine(signatureVerificationResults = listOf(expected))
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(listOf(expected), loaded.signatureVerifications)
    }

    @Test
    fun openingAnUnsignedDocumentHasNoSignatureVerificationResults() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))

        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(emptyList<Any>(), loaded.signatureVerifications)
    }

    @Test
    fun saveSignatureAddsItToSavedSignaturesAndSelectsIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val signatureRepository = FakeSignatureRepository()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            signatureRepository = signatureRepository,
        )
        assertEquals(emptyList<SavedSignature>(), viewModel.savedSignatures.value)

        val bitmap = Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888)
        viewModel.saveSignature(bitmap)

        assertEquals(1, viewModel.savedSignatures.value.size)
        assertEquals(viewModel.savedSignatures.value.first().id, viewModel.selectedSignatureId.value)
    }

    @Test
    fun importSignatureImageDecodesAndSavesTheBitmap() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val importedBitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888)
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            imageUriDecoder = FakeImageUriDecoder(result = importedBitmap),
        )

        viewModel.importSignatureImage(Uri.parse("content://example/picked-image"))

        assertEquals(1, viewModel.savedSignatures.value.size)
        assertEquals(importedBitmap, viewModel.savedSignatures.value.first().bitmap)
    }

    @Test
    fun placeSignatureAtStampsTheSelectedSignatureAndOverlaysIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        viewModel.saveSignature(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888))
        var loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(emptyList<Any>(), loaded.signatureStamps)

        viewModel.placeSignatureAt(0.1f, 0.2f)

        loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(1, loaded.signatureStamps.size)
        assertEquals(0.1f, loaded.signatureStamps.first().xFraction)
        assertEquals(1, editEngine.imageStamps.size)
    }

    @Test
    fun placeSignatureAtIsANoOpWithoutASelectedSignature() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        viewModel.placeSignatureAt(0.1f, 0.2f)

        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals(emptyList<Any>(), loaded.signatureStamps)
        assertEquals(0, editEngine.imageStamps.size)
    }

    @Test
    fun deleteSignatureRemovesItAndClearsSelectionIfItWasSelected() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))

        viewModel.saveSignature(Bitmap.createBitmap(40, 20, Bitmap.Config.ARGB_8888))
        val savedId = viewModel.selectedSignatureId.value
        assertTrue(savedId != null)

        viewModel.deleteSignature(savedId!!)

        assertEquals(emptyList<SavedSignature>(), viewModel.savedSignatures.value)
        assertEquals(null, viewModel.selectedSignatureId.value)
    }

    @Test
    fun exportAnnotatedCopySucceedsAndCommitsToTheDestination() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val destinationWriter = FakeDestinationWriter(writeSucceeds = true)
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            destinationWriter = destinationWriter,
        )

        val destination = Uri.parse("content://com.android.providers.downloads.documents/document/out")
        viewModel.exportAnnotatedCopy(destination)

        val state = awaitExportState(viewModel) { it !is ExportState.Saving }
        assertTrue("expected Success but got $state", state is ExportState.Success)
        assertEquals(destination, destinationWriter.lastDestination)

        viewModel.acknowledgeExportState()
        assertEquals(ExportState.Idle, viewModel.exportState.value)
    }

    @Test
    fun exportAnnotatedCopyReportsFailureWhenTheDestinationWriteFails() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            destinationWriter = FakeDestinationWriter(writeSucceeds = false),
        )

        viewModel.exportAnnotatedCopy(Uri.parse("content://example/document/out"))

        val state = awaitExportState(viewModel) { it !is ExportState.Saving }
        assertTrue("expected Failure but got $state", state is ExportState.Failure)
    }

    @Test
    fun openingAPasswordProtectedDocumentShowsThePasswordPrompt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT, alwaysFailsWithPasswordProtected = true),
            editEngine = FakePdfEditEngine(requiredPassword = "secret"),
        )

        assertTrue(viewModel.passwordRequired.value)
        assertEquals(null, viewModel.passwordError.value)
        assertTrue(
            "expected Error state but got ${viewModel.uiState.value}",
            viewModel.uiState.value is ReaderUiState.Error,
        )
    }

    @Test
    fun submittingTheWrongPasswordKeepsThePromptWithAnErrorMessage() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT, alwaysFailsWithPasswordProtected = true),
            editEngine = FakePdfEditEngine(requiredPassword = "secret"),
        )

        viewModel.submitPassword("wrong")

        assertTrue(viewModel.passwordRequired.value)
        assertEquals("Incorrect password.", viewModel.passwordError.value)
    }

    /** PDF-013 / ADR-020: once the password is confirmed correct, rendering falls back to
     * [PdfEditEngine.renderPage] — [PdfEngine] never succeeds for an encrypted document at
     * this app's minSdk, correct password or not. */
    @Test
    fun submittingTheCorrectPasswordUsesTheFallbackRendererAndLoadsTheDocument() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fakeEngine = FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT, alwaysFailsWithPasswordProtected = true)
        val fakeEditEngine = FakePdfEditEngine(requiredPassword = "secret")
        val viewModel = newViewModel(context, fakeEngine, editEngine = fakeEditEngine)

        viewModel.submitPassword("secret")

        assertTrue(!viewModel.passwordRequired.value)
        val state = viewModel.uiState.value
        assertTrue("expected Loaded state but got $state", state is ReaderUiState.Loaded)
        assertEquals("the platform engine must never be used to render this document", 0, fakeEngine.renderCallCount)
        assertTrue("expected the fallback renderer to have been used", fakeEditEngine.renderPageCallCount > 0)
    }

    @Test
    fun cancelPasswordPromptDismissesIt() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT, alwaysFailsWithPasswordProtected = true),
            editEngine = FakePdfEditEngine(requiredPassword = "secret"),
        )

        viewModel.cancelPasswordPrompt()

        assertTrue(!viewModel.passwordRequired.value)
    }

    @Test
    fun setFormFieldValueUpdatesTheFieldAndRefreshesTheList() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(
            initialFormFields = listOf(
                com.pdfreader.app.model.PdfFormField(fullyQualifiedName = "name", fieldType = "Tx", value = "old"),
            ),
        )
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        viewModel.setFormFieldValue("name", "new")

        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals("new", loaded.formFields.single { it.fullyQualifiedName == "name" }.value)
    }

    @Test
    fun setFormFieldValueIsANoOpForAnUnknownFieldName() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(
            initialFormFields = listOf(
                com.pdfreader.app.model.PdfFormField(fullyQualifiedName = "name", fieldType = "Tx", value = "old"),
            ),
        )
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT), editEngine = editEngine)

        viewModel.setFormFieldValue("does-not-exist", "new")

        val loaded = viewModel.uiState.value as ReaderUiState.Loaded
        assertEquals("old", loaded.formFields.single { it.fullyQualifiedName == "name" }.value)
    }

    @Test
    fun extractAttachmentToSucceedsAndCommitsToTheDestination() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val editEngine = FakePdfEditEngine(
            initialAttachments = listOf(com.pdfreader.app.model.PdfAttachment(name = "notes.txt", sizeBytes = 10L)),
        )
        val destinationWriter = FakeDestinationWriter()
        val viewModel = newViewModel(
            context,
            FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT),
            editEngine = editEngine,
            destinationWriter = destinationWriter,
        )
        val destination = Uri.parse("content://example/document/attachment-out")

        viewModel.extractAttachmentTo("notes.txt", destination)

        val state = awaitExportState(viewModel, viewModel::attachmentExtractState) { it !is ExportState.Saving }
        assertTrue("expected Success but got $state", state is ExportState.Success)
        assertEquals(destination, destinationWriter.lastDestination)
    }

    @Test
    fun extractAttachmentToReportsFailureWhenTheAttachmentIsMissing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val viewModel = newViewModel(context, FakePdfEngine(pageCount = FIXTURE_PAGE_COUNT))

        viewModel.extractAttachmentTo("does-not-exist.txt", Uri.parse("content://example/document/out"))

        val state = awaitExportState(viewModel, viewModel::attachmentExtractState) { it !is ExportState.Saving }
        assertTrue("expected Failure but got $state", state is ExportState.Failure)
    }

    private suspend fun awaitExportState(
        viewModel: ReaderViewModel,
        predicate: (ExportState) -> Boolean,
    ): ExportState = awaitExportState(viewModel, viewModel::exportState, predicate)

    private suspend fun awaitExportState(
        viewModel: ReaderViewModel,
        stateAccessor: () -> kotlinx.coroutines.flow.StateFlow<ExportState>,
        predicate: (ExportState) -> Boolean,
    ): ExportState = withTimeout(5_000) {
        var current = stateAccessor().value
        while (!predicate(current)) {
            delay(5)
            current = stateAccessor().value
        }
        current
    }

    private class FakeDocumentMetadataResolver : DocumentMetadataResolver {
        override suspend fun resolve(uriString: String): DocumentMetadata =
            DocumentMetadata(displayName = "fake-document.pdf", sizeBytes = 1234L)
    }

    private data class FakeNoteAnnotation(val pageIndex: Int, val xFraction: Float, val yFraction: Float, val text: String)

    /** Stands in for [PdfBoxEditEngine] — no real PDFBox document is involved. */
    private class FakePdfEditEngine(
        private val openSucceeds: Boolean = true,
        private val initialPageCount: Int = FIXTURE_PAGE_COUNT,
        private val appendSucceeds: Boolean = true,
        /** Non-null simulates a password-protected document (PDF-013); [open] fails with
         * [com.pdfreader.app.model.PdfOpenError.PasswordProtected] unless [PdfSource.password]
         * matches exactly. */
        private val requiredPassword: String? = null,
        var signatureVerificationResults: List<com.pdfreader.app.model.SignatureVerificationResult> = emptyList(),
        initialFormFields: List<com.pdfreader.app.model.PdfFormField> = emptyList(),
        initialAttachments: List<com.pdfreader.app.model.PdfAttachment> = emptyList(),
    ) : PdfEditEngine {
        val addedAnnotations = mutableListOf<FakeNoteAnnotation>()
        private var pageCount = 0
        var renderPageCallCount: Int = 0
        var formFieldsResult: List<com.pdfreader.app.model.PdfFormField> = initialFormFields
        var attachmentsResult: List<com.pdfreader.app.model.PdfAttachment> = initialAttachments

        override suspend fun open(source: PdfSource): PdfOpenResult {
            if (!openSucceeds) {
                return PdfOpenResult.Failure(com.pdfreader.app.model.PdfOpenError.Unexpected("fake open failure"))
            }
            if (requiredPassword != null && source.password != requiredPassword) {
                return PdfOpenResult.Failure(com.pdfreader.app.model.PdfOpenError.PasswordProtected("locked"))
            }
            pageCount = initialPageCount
            return PdfOpenResult.Success(PdfDocumentHandle(pageCount = pageCount))
        }

        override suspend fun addNoteAnnotation(pageIndex: Int, xFraction: Float, yFraction: Float, text: String): Boolean {
            if (pageIndex < 0 || pageIndex >= pageCount) return false
            addedAnnotations += FakeNoteAnnotation(pageIndex, xFraction, yFraction, text)
            return true
        }

        override suspend fun annotationCount(pageIndex: Int): Int =
            addedAnnotations.count { it.pageIndex == pageIndex }

        override suspend fun pageCount(): Int = pageCount

        override suspend fun rotatePage(pageIndex: Int, deltaDegrees: Int): Boolean =
            pageIndex in 0 until pageCount

        override suspend fun deletePage(pageIndex: Int): Boolean {
            if (pageIndex < 0 || pageIndex >= pageCount) return false
            pageCount--
            return true
        }

        override suspend fun movePage(fromIndex: Int, toIndex: Int): Boolean =
            fromIndex in 0 until pageCount && toIndex in 0 until pageCount

        override suspend fun appendDocument(source: PdfSource): Boolean {
            if (!appendSucceeds) return false
            pageCount += FIXTURE_PAGE_COUNT
            return true
        }

        var insertSucceeds: Boolean = true

        override suspend fun insertDocument(source: PdfSource, atIndex: Int): Boolean {
            if (!insertSucceeds || atIndex < 0 || atIndex > pageCount) return false
            pageCount += FIXTURE_PAGE_COUNT
            return true
        }

        var extractedFile: File? = null

        override suspend fun extractPages(pageIndices: List<Int>): File? {
            if (pageIndices.isEmpty() || pageIndices.any { it < 0 || it >= pageCount }) return null
            return extractedFile ?: File.createTempFile("fake-extracted", ".pdf")
        }

        val imageStamps = mutableListOf<Int>()
        var addImageStampSucceeds: Boolean = true

        override suspend fun addImageStamp(
            pageIndex: Int,
            xFraction: Float,
            yFraction: Float,
            widthFraction: Float,
            pngBytes: ByteArray,
        ): Boolean {
            if (!addImageStampSucceeds || pageIndex < 0 || pageIndex >= pageCount) return false
            imageStamps += pageIndex
            return true
        }

        var signWithDetachedCmsSucceeds: Boolean = true
        val signRequests = mutableListOf<String>()

        override suspend fun signWithDetachedCms(
            signerName: String,
            privateKey: java.security.PrivateKey,
            certificateChain: List<java.security.cert.X509Certificate>,
        ): File? {
            if (!signWithDetachedCmsSucceeds) return null
            signRequests += signerName
            return File.createTempFile("fake-signed", ".pdf")
        }

        override suspend fun verifySignatures(): List<com.pdfreader.app.model.SignatureVerificationResult> =
            signatureVerificationResults

        override suspend fun listFormFields(): List<com.pdfreader.app.model.PdfFormField> = formFieldsResult

        override suspend fun setFormFieldValue(fullyQualifiedName: String, value: String): Boolean {
            val index = formFieldsResult.indexOfFirst { it.fullyQualifiedName == fullyQualifiedName }
            if (index < 0) return false
            formFieldsResult = formFieldsResult.toMutableList().also {
                it[index] = it[index].copy(value = value)
            }
            return true
        }

        var extractAttachmentSucceeds: Boolean = true

        override suspend fun listAttachments(): List<com.pdfreader.app.model.PdfAttachment> = attachmentsResult

        override suspend fun extractAttachment(name: String): File? {
            if (!extractAttachmentSucceeds || attachmentsResult.none { it.name == name }) return null
            return File.createTempFile("fake-attachment", ".bin")
        }

        override suspend fun extractAllAttachments(): List<Pair<String, File>> {
            if (!extractAttachmentSucceeds) return emptyList()
            return attachmentsResult.map { it.name to File.createTempFile("fake-attachment", ".bin") }
        }

        var searchResults: List<com.pdfreader.app.model.PdfSearchMatch> = emptyList()

        override suspend fun searchText(query: String): List<com.pdfreader.app.model.PdfSearchMatch> =
            if (query.isBlank()) emptyList() else searchResults

        var outlineResult: List<com.pdfreader.app.model.PdfOutlineItem> = emptyList()

        override suspend fun listOutline(): List<com.pdfreader.app.model.PdfOutlineItem> = outlineResult

        override suspend fun writeValidatedTempCopy(): File = File.createTempFile("fake-edit", ".pdf")

        override suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap? {
            renderPageCallCount++
            if (pageIndex < 0 || pageIndex >= pageCount) return null
            return Bitmap.createBitmap(FAKE_RENDER_WIDTH, FAKE_RENDER_HEIGHT, Bitmap.Config.ARGB_8888)
        }

        override suspend fun close() {
            // no-op fake
        }
    }

    /** Stands in for [ContentResolverDestinationWriter]. */
    private class FakeDestinationWriter(private val writeSucceeds: Boolean = true) : DestinationWriter {
        var lastDestination: Uri? = null
        var lastTreeUri: Uri? = null
        val writtenIntoTreeNames = mutableListOf<String>()

        override suspend fun writeFileToUri(source: File, destination: Uri): Boolean {
            lastDestination = destination
            return writeSucceeds
        }

        override suspend fun writeFileIntoTree(source: File, treeUri: Uri, displayName: String): Boolean {
            lastTreeUri = treeUri
            writtenIntoTreeNames += displayName
            return writeSucceeds
        }
    }

    /** In-memory stand-in for [LocalFileSignatureRepository] — no real file I/O involved. */
    private class FakeSignatureRepository(initial: List<SavedSignature> = emptyList()) : SignatureRepository {
        private val signatures = initial.toMutableList()

        override suspend fun listAll(): List<SavedSignature> = signatures.toList()

        override suspend fun save(bitmap: Bitmap): SavedSignature {
            val saved = SavedSignature(id = "sig-${signatures.size}", bitmap = bitmap, createdAtEpochMillis = 0L)
            signatures += saved
            return saved
        }

        override suspend fun delete(id: String) {
            signatures.removeAll { it.id == id }
        }
    }

    /** Stands in for [ContentResolverImageUriDecoder]. */
    private class FakeImageUriDecoder(private val result: Bitmap? = null) : ImageUriDecoder {
        override suspend fun decode(uri: Uri): Bitmap? = result
    }

    /** In-memory stand-in for [AndroidKeystoreSigningIdentityManager] — no real Keystore involved. */
    private class FakeSigningIdentityManager(initial: List<SigningIdentity> = emptyList()) : SigningIdentityManager {
        private val identities = initial.toMutableList()

        override suspend fun listIdentities(): List<SigningIdentity> = identities.toList()

        override suspend fun createIdentity(displayName: String): SigningIdentity {
            val (_, certificate) = generateTestKeyAndCertificate()
            val identity = SigningIdentity(
                alias = "fake-alias-${identities.size}",
                displayName = displayName,
                certificate = certificate,
                createdAtEpochMillis = 0L,
            )
            identities += identity
            return identity
        }

        override suspend fun deleteIdentity(alias: String) {
            identities.removeAll { it.alias == alias }
        }

        override suspend fun loadSigningMaterial(alias: String): Pair<PrivateKey, List<X509Certificate>>? {
            if (identities.none { it.alias == alias }) return null
            val (privateKey, certificate) = generateTestKeyAndCertificate()
            return privateKey to listOf(certificate)
        }
    }

    /** Stands in for [BouncyCastlePkcs12SigningMaterialLoader]. */
    private class FakePkcs12SigningMaterialLoader(private val loadSucceeds: Boolean = true) : Pkcs12SigningMaterialLoader {
        override suspend fun load(pkcs12Bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>>? {
            if (!loadSucceeds) return null
            val (privateKey, certificate) = generateTestKeyAndCertificate()
            return privateKey to listOf(certificate)
        }
    }

    /** Stands in for [ContentResolverBytesUriReader]. */
    private class FakeBytesUriReader(private val result: ByteArray? = ByteArray(0)) : BytesUriReader {
        override suspend fun read(uri: Uri): ByteArray? = result
    }

    /** Stands in for [PlatformPdfRendererEngine] — see class-level doc for why. */
    private class FakePdfEngine(
        private val pageCount: Int,
        /** Mirrors the real `android.graphics.pdf.PdfRenderer`'s actual behavior (PDF-013,
         * planning/ADR.md ADR-020): it can never open a password-protected document at this
         * app's minSdk, correct password or not — there is no way to supply one. */
        private val alwaysFailsWithPasswordProtected: Boolean = false,
    ) : PdfEngine {
        var lastOpenedSource: PdfSource? = null
        var renderCallCount: Int = 0

        override suspend fun open(source: PdfSource): PdfOpenResult {
            lastOpenedSource = source
            if (alwaysFailsWithPasswordProtected) {
                return PdfOpenResult.Failure(com.pdfreader.app.model.PdfOpenError.PasswordProtected("locked"))
            }
            return PdfOpenResult.Success(PdfDocumentHandle(pageCount = pageCount))
        }

        override suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap {
            renderCallCount++
            return Bitmap.createBitmap(FAKE_RENDER_WIDTH, FAKE_RENDER_HEIGHT, Bitmap.Config.ARGB_8888)
        }

        override suspend fun close() {
            // no-op fake
        }
    }
}
