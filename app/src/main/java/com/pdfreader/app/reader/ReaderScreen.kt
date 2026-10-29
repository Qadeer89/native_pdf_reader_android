package com.pdfreader.app.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pdfreader.app.R

private const val MIN_ZOOM_SCALE = 1f
private const val MAX_ZOOM_SCALE = 5f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onOpenDocumentClick: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val layoutMode by viewModel.layoutMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val pageLabel = (state as? ReaderUiState.Loaded)?.let {
                        stringResource(R.string.reader_page_label, it.pageIndex + 1, it.pageCount)
                    } ?: stringResource(R.string.app_name)
                    Text(pageLabel)
                },
                actions = {
                    if (state is ReaderUiState.Loaded) {
                        TextButton(onClick = viewModel::toggleLayoutMode) {
                            val modeLabel = if (layoutMode == PageLayoutMode.SINGLE) {
                                stringResource(R.string.reader_mode_continuous)
                            } else {
                                stringResource(R.string.reader_mode_single)
                            }
                            Text(modeLabel)
                        }
                    }
                    TextButton(onClick = onOpenDocumentClick) {
                        Text(stringResource(R.string.reader_open_action))
                    }
                },
            )
        },
        bottomBar = {
            if (state is ReaderUiState.Loaded && layoutMode == PageLayoutMode.SINGLE) {
                PageNavigationBar(
                    onPrevious = viewModel::previousPage,
                    onNext = viewModel::nextPage,
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (val current = state) {
                is ReaderUiState.Loading -> {
                    val loadingLabel = stringResource(R.string.reader_loading)
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .semantics { contentDescription = loadingLabel },
                    )
                }

                is ReaderUiState.Error -> {
                    Text(
                        text = current.error.userMessage,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

                is ReaderUiState.Loaded -> {
                    androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxSize()) {
                        if (current.pageCount > 1) {
                            ThumbnailStrip(
                                pageCount = current.pageCount,
                                currentPageIndex = current.pageIndex,
                                viewModel = viewModel,
                                onPageSelected = viewModel::jumpToPage,
                            )
                        }
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            if (layoutMode == PageLayoutMode.SINGLE) {
                                ZoomablePdfPage(current)
                            } else {
                                ContinuousPdfPages(pageCount = current.pageCount, viewModel = viewModel)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Pinch-to-zoom and pan on the current page (PDF-002). Zoom resets when the page changes. */
@Composable
private fun ZoomablePdfPage(state: ReaderUiState.Loaded) {
    var scale by rememberSaveable(state.pageIndex) { mutableStateOf(MIN_ZOOM_SCALE) }
    var offsetX by rememberSaveable(state.pageIndex) { mutableStateOf(0f) }
    var offsetY by rememberSaveable(state.pageIndex) { mutableStateOf(0f) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    Image(
        bitmap = remember(state.pageBitmap) { state.pageBitmap.asImageBitmap() },
        contentDescription = stringResource(R.string.reader_page_label, state.pageIndex + 1, state.pageCount),
        modifier = Modifier
            .graphicsLayer(
                scaleX = scale,
                scaleY = scale,
                translationX = offsetX,
                translationY = offsetY,
            )
            .transformable(state = transformState),
    )
}

/**
 * Continuous-scroll layout (PDF-002): every page rendered on demand as it scrolls into
 * view, via [ReaderViewModel.pageBitmap] (which caches per page index so re-entering the
 * same page doesn't re-render). Pinch-zoom is not supported in this mode — switch to
 * single-page mode to zoom (tracked as a backlog follow-up).
 */
@Composable
private fun ContinuousPdfPages(pageCount: Int, viewModel: ReaderViewModel) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(pageCount) { index ->
            var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(index) {
                bitmap = viewModel.pageBitmap(index, ReaderViewModel.FULL_PAGE_WIDTH)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                val pageBitmap = bitmap
                if (pageBitmap != null) {
                    Image(
                        bitmap = remember(pageBitmap) { pageBitmap.asImageBitmap() },
                        contentDescription = stringResource(R.string.reader_page_label, index + 1, pageCount),
                    )
                } else {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

/** Horizontal strip of page thumbnails; tapping one jumps there (PDF-002). */
@Composable
private fun ThumbnailStrip(
    pageCount: Int,
    currentPageIndex: Int,
    viewModel: ReaderViewModel,
    onPageSelected: (Int) -> Unit,
) {
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        items(pageCount) { index ->
            var thumbnail by remember(index) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(index) {
                thumbnail = viewModel.pageBitmap(index, ReaderViewModel.THUMBNAIL_WIDTH)
            }
            val isCurrent = index == currentPageIndex
            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .border(
                        width = if (isCurrent) 2.dp else 0.dp,
                        color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.Transparent,
                    )
                    .clickable { onPageSelected(index) }
                    .size(width = 64.dp, height = 88.dp),
                contentAlignment = Alignment.Center,
            ) {
                val pageThumbnail = thumbnail
                if (pageThumbnail != null) {
                    Image(
                        bitmap = remember(pageThumbnail) { pageThumbnail.asImageBitmap() },
                        contentDescription = stringResource(R.string.reader_page_label, index + 1, pageCount),
                        modifier = Modifier.height(80.dp),
                    )
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun PageNavigationBar(onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        TextButton(onClick = onPrevious) {
            Text(stringResource(R.string.reader_previous_page))
        }
        TextButton(onClick = onNext) {
            Text(stringResource(R.string.reader_next_page))
        }
    }
}
