package com.pdfreader.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pdfreader.app.data.documents.ContentResolverDocumentMetadataResolver
import com.pdfreader.app.data.recents.RecentDocumentsDatabase
import com.pdfreader.app.data.recents.RecentDocumentsRepository
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PlatformPdfRendererEngine
import com.pdfreader.app.reader.ReaderScreen
import com.pdfreader.app.reader.ReaderViewModel
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
                    assetPdfCopier = AssetPdfCopier(applicationContext),
                    recentDocumentsRepository = RecentDocumentsRepository(
                        RecentDocumentsDatabase.getInstance(applicationContext).recentDocumentDao(),
                    ),
                    metadataResolver = ContentResolverDocumentMetadataResolver(applicationContext),
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handleViewOrShareIntent(intent)

        setContent {
            PdfReaderTheme {
                ReaderScreen(
                    viewModel = readerViewModel,
                    onOpenDocumentClick = { openDocumentLauncher.launch(arrayOf("application/pdf")) },
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
