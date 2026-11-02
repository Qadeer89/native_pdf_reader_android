package com.pdfreader.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pdfreader.app.data.documents.ContentResolverDestinationWriter
import com.pdfreader.app.data.documents.ContentResolverDocumentMetadataResolver
import com.pdfreader.app.data.recents.RecentDocumentsDatabase
import com.pdfreader.app.data.recents.RecentDocumentsRepository
import com.pdfreader.app.data.signatures.ContentResolverImageUriDecoder
import com.pdfreader.app.data.signatures.LocalFileSignatureRepository
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PdfBoxEditEngine
import com.pdfreader.app.pdfengine.PlatformPdfRendererEngine
import com.pdfreader.app.print.PdfPrintDocumentAdapter
import com.pdfreader.app.reader.PrintState
import com.pdfreader.app.reader.ReaderScreen
import com.pdfreader.app.reader.ReaderViewModel
import com.pdfreader.app.signing.AndroidKeystoreSigningIdentityManager
import com.pdfreader.app.signing.BouncyCastlePkcs12SigningMaterialLoader
import com.pdfreader.app.signing.ContentResolverBytesUriReader
import com.pdfreader.app.ui.theme.PdfReaderTheme

/**
 * Single-activity app shell. Manual DI per planning/ADR.md ADR-003: a tiny factory wires
 * [ReaderViewModel]'s dependencies without a DI framework.
 */
class MainActivity : ComponentActivity() {

    private val readerViewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ReaderViewModel(
                    pdfEngine = PlatformPdfRendererEngine(context = applicationContext),
                    pdfEditEngine = PdfBoxEditEngine(context = applicationContext),
                    assetPdfCopier = AssetPdfCopier(applicationContext),
                    recentDocumentsRepository = RecentDocumentsRepository(
                        RecentDocumentsDatabase.getInstance(applicationContext).recentDocumentDao(),
                    ),
                    metadataResolver = ContentResolverDocumentMetadataResolver(applicationContext),
                    destinationWriter = ContentResolverDestinationWriter(applicationContext),
                    signatureRepository = LocalFileSignatureRepository(applicationContext),
                    imageUriDecoder = ContentResolverImageUriDecoder(applicationContext),
                    signingIdentityManager = AndroidKeystoreSigningIdentityManager(),
                    pkcs12Loader = BouncyCastlePkcs12SigningMaterialLoader(),
                    bytesUriReader = ContentResolverBytesUriReader(applicationContext),
                ) as T
            }
        }
    }

    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                takePersistableReadPermission(uri)
                readerViewModel.openDocument(uri)
            }
        }

    /** PDF-010: user picks where to save the annotated copy; never overwrites the original. */
    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) {
                readerViewModel.exportAnnotatedCopy(uri)
            } else {
                readerViewModel.acknowledgeExportState()
            }
        }

    /** PDF-006: user picks a second PDF whose pages get appended to the one currently open. */
    private val mergeDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                readerViewModel.mergeDocument(uri)
            }
        }

    /** PDF-007: user picks an image to import as a visual signature. */
    private val importSignatureImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                readerViewModel.importSignatureImage(uri)
            }
        }

    /** PDF-006: user picks a second PDF whose pages get inserted at a chosen position. */
    private val insertDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                readerViewModel.pickedDocumentToInsert(uri)
            }
        }

    /** PDF-006: user picks where to save the current page extracted as its own PDF. */
    private val extractDestinationLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            if (uri != null) {
                readerViewModel.extractCurrentPageTo(uri)
            } else {
                readerViewModel.acknowledgeExtractState()
            }
        }

    /**
     * PDF-008: which signing operation [signDestinationLauncher]'s result should finish —
     * set right before launching it, since the destination picker is the last step of
     * either signing flow (Keystore identity or PKCS#12 import) and both need to share one
     * `CreateDocument` launcher.
     */
    private sealed class PendingSignAction {
        data class WithIdentity(val alias: String) : PendingSignAction()
        data class WithPkcs12(val password: CharArray) : PendingSignAction()
    }

    private var pendingSignAction: PendingSignAction? = null

    /** PDF-008: user picks where to save the signed copy; never overwrites the original. */
    private val signDestinationLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
            val action = pendingSignAction
            pendingSignAction = null
            if (uri != null && action != null) {
                when (action) {
                    is PendingSignAction.WithIdentity -> readerViewModel.signWithIdentity(action.alias, uri)
                    is PendingSignAction.WithPkcs12 -> readerViewModel.signWithImportedPkcs12(action.password, uri)
                }
            } else {
                readerViewModel.acknowledgeSignState()
            }
        }

    /** PDF-008: user picks a PKCS#12 (.p12/.pfx) file to sign with. */
    private val importPkcs12Launcher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                readerViewModel.importPkcs12(uri)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleViewOrShareIntent(intent)

        setContent {
            PdfReaderTheme {
                val printState by readerViewModel.printState.collectAsState()
                LaunchedEffect(printState) {
                    val ready = printState as? PrintState.Ready ?: return@LaunchedEffect
                    val printManager = getSystemService(PrintManager::class.java)
                    printManager.print(
                        ready.documentName,
                        PdfPrintDocumentAdapter(ready.file, ready.documentName),
                        null,
                    )
                    readerViewModel.acknowledgePrintState()
                }
                ReaderScreen(
                    viewModel = readerViewModel,
                    onOpenDocumentClick = { openDocumentLauncher.launch(arrayOf("application/pdf")) },
                    onExportAnnotatedCopyClick = { suggestedName ->
                        createDocumentLauncher.launch(suggestedName)
                    },
                    onMergeDocumentClick = { mergeDocumentLauncher.launch(arrayOf("application/pdf")) },
                    onImportSignatureImageClick = { importSignatureImageLauncher.launch("image/*") },
                    onInsertDocumentClick = { insertDocumentLauncher.launch(arrayOf("application/pdf")) },
                    onExtractCurrentPageClick = { suggestedName -> extractDestinationLauncher.launch(suggestedName) },
                    onSignWithIdentityClick = { alias, suggestedName ->
                        pendingSignAction = PendingSignAction.WithIdentity(alias)
                        signDestinationLauncher.launch(suggestedName)
                    },
                    onImportPkcs12Click = { importPkcs12Launcher.launch("*/*") },
                    onPkcs12PasswordSubmittedClick = { password, suggestedName ->
                        pendingSignAction = PendingSignAction.WithPkcs12(password)
                        signDestinationLauncher.launch(suggestedName)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleViewOrShareIntent(intent)
    }

    /**
     * PDF-001: support opening via a share intent (`ACTION_SEND`) or a file-association
     * view intent (`ACTION_VIEW`) in addition to the explicit SAF picker.
     */
    private fun handleViewOrShareIntent(intent: Intent) {
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.extraStreamUri()
            else -> null
        }
        if (uri != null) {
            takePersistableReadPermission(uri)
            readerViewModel.openDocument(uri)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.extraStreamUri(): Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        getParcelableExtra(Intent.EXTRA_STREAM)
    }

    /**
     * Persists read access to a SAF-picked Uri across process restarts. Share-intent and
     * file-association Uris do not always support this grant; failure here is expected
     * and non-fatal — the document still opens for the current session.
     */
    private fun takePersistableReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Expected for share-intent/view-intent Uris that don't grant persistable access.
        }
    }
}
