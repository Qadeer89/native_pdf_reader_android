package com.pdfreader.app

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.pdfreader.app.pdfengine.AssetPdfCopier
import com.pdfreader.app.pdfengine.PlatformPdfRendererEngine
import com.pdfreader.app.reader.ReaderScreen
import com.pdfreader.app.reader.ReaderViewModel
import com.pdfreader.app.ui.theme.PdfReaderTheme

/**
 * Single-activity app shell for the vertical slice. Manual DI per planning/ADR.md
 * ADR-003: a tiny factory wires [ReaderViewModel]'s dependencies without a DI framework.
 */
class MainActivity : ComponentActivity() {

    private val readerViewModel: ReaderViewModel by viewModels {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ReaderViewModel(
                    pdfEngine = PlatformPdfRendererEngine(),
                    assetPdfCopier = AssetPdfCopier(applicationContext),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PdfReaderTheme {
                ReaderScreen(viewModel = readerViewModel)
            }
        }
    }
}
