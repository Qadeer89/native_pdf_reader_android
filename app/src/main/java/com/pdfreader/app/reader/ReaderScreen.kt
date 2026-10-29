package com.pdfreader.app.reader

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pdfreader.app.R

@Composable
fun ReaderScreen(viewModel: ReaderViewModel) {
    val state by viewModel.uiState.collectAsState()

    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.Center,
        ) {
            when (val current = state) {
                is ReaderUiState.Loading -> {
                    val loadingLabel = resolvedLoadingLabel()
                    CircularProgressIndicator(
                        modifier = Modifier.semantics {
                            contentDescription = loadingLabel
                        },
                    )
                }

                is ReaderUiState.Error -> {
                    Text(
                        text = current.error.userMessage,
                        modifier = Modifier.padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                is ReaderUiState.Loaded -> {
                    Image(
                        bitmap = current.pageBitmap.asImageBitmap(),
                        contentDescription = pageContentDescription(current.pageIndex, current.pageCount),
                    )
                }
            }
        }
    }
}

@Composable
private fun resolvedLoadingLabel(): String = androidx.compose.ui.res.stringResource(R.string.reader_loading)

@Composable
private fun pageContentDescription(pageIndex: Int, pageCount: Int): String =
    androidx.compose.ui.res.stringResource(R.string.reader_page_label, pageIndex + 1, pageCount)
