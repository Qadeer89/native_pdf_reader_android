package com.pdfreader.app.reader

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pdfreader.app.R
import com.pdfreader.app.data.recents.RecentDocumentEntity
import com.pdfreader.app.data.signatures.SavedSignature

private const val MIN_ZOOM_SCALE = 1f
private const val MAX_ZOOM_SCALE = 5f
private const val MARKER_RADIUS_DP = 6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onOpenDocumentClick: () -> Unit,
    onExportAnnotatedCopyClick: (suggestedName: String) -> Unit,
    onMergeDocumentClick: () -> Unit,
    onImportSignatureImageClick: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val layoutMode by viewModel.layoutMode.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val pageOpMessage by viewModel.pageOpMessage.collectAsState()
    val recentDocuments by viewModel.recentDocuments.collectAsState()
    val savedSignatures by viewModel.savedSignatures.collectAsState()
    var showRecentMenu by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }
    var showSignMenu by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var showDrawSignatureDialog by remember { mutableStateOf(false) }
    var showTypeSignatureDialog by remember { mutableStateOf(false) }
    var annotateMode by rememberSaveable { mutableStateOf(false) }
    var signatureMode by rememberSaveable { mutableStateOf(false) }

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
                    val loaded = state as? ReaderUiState.Loaded
                    if (loaded != null) {
                        TextButton(onClick = { showPropertiesDialog = true }) {
                            Text(stringResource(R.string.reader_properties_action))
                        }
                        TextButton(onClick = viewModel::toggleLayoutMode) {
                            val modeLabel = if (layoutMode == PageLayoutMode.SINGLE) {
                                stringResource(R.string.reader_mode_continuous)
                            } else {
                                stringResource(R.string.reader_mode_single)
                            }
                            Text(modeLabel)
                        }
                        if (loaded.canAnnotate && layoutMode == PageLayoutMode.SINGLE) {
                            TextButton(
                                onClick = {
                                    annotateMode = !annotateMode
                                    if (annotateMode) signatureMode = false
                                },
                            ) {
                                Text(
                                    if (annotateMode) {
                                        stringResource(R.string.reader_annotate_done)
                                    } else {
                                        stringResource(R.string.reader_annotate_start)
                                    },
                                )
                            }
                            TextButton(
                                onClick = {
                                    val baseName = loaded.documentMetadata?.displayName
                                        ?.removeSuffix(".pdf")
                                        ?: "document"
                                    onExportAnnotatedCopyClick("$baseName-annotated.pdf")
                                },
                            ) {
                                Text(stringResource(R.string.reader_export_action))
                            }
                            Box {
                                TextButton(onClick = { showPageMenu = true }) {
                                    Text(stringResource(R.string.reader_page_menu_action))
                                }
                                PageOpsMenu(
                                    expanded = showPageMenu,
                                    onDismiss = { showPageMenu = false },
                                    onRotate = {
                                        showPageMenu = false
                                        viewModel.rotateCurrentPage()
                                    },
                                    onDelete = {
                                        showPageMenu = false
                                        viewModel.deleteCurrentPage()
                                    },
                                    onMerge = {
                                        showPageMenu = false
                                        onMergeDocumentClick()
                                    },
                                )
                            }
                            Box {
                                TextButton(
                                    onClick = {
                                        if (signatureMode) {
                                            signatureMode = false
                                        } else {
                                            showSignMenu = true
                                        }
                                    },
                                ) {
                                    Text(
                                        if (signatureMode) {
                                            stringResource(R.string.reader_signature_done)
                                        } else {
                                            stringResource(R.string.reader_signature_action)
                                        },
                                    )
                                }
                                SignatureMenu(
                                    expanded = showSignMenu,
                                    savedSignatures = savedSignatures,
                                    onDismiss = { showSignMenu = false },
                                    onDrawNew = {
                                        showSignMenu = false
                                        showDrawSignatureDialog = true
                                    },
                                    onTypeNew = {
                                        showSignMenu = false
                                        showTypeSignatureDialog = true
                                    },
                                    onImportNew = {
                                        showSignMenu = false
                                        onImportSignatureImageClick()
                                    },
                                    onSelect = { id ->
                                        showSignMenu = false
                                        viewModel.selectSignature(id)
                                        signatureMode = true
                                        annotateMode = false
                                    },
                                    onDelete = viewModel::deleteSignature,
                                )
                            }
                        }
                    }
                    Box {
                        TextButton(onClick = { showRecentMenu = true }) {
                            Text(stringResource(R.string.reader_recent_action))
                        }
                        RecentDocumentsMenu(
                            expanded = showRecentMenu,
                            recentDocuments = recentDocuments,
                            onDismiss = { showRecentMenu = false },
                            onDocumentSelected = { uri ->
                                showRecentMenu = false
                                viewModel.openRecentDocument(uri)
                            },
                            onClearHistory = {
                                showRecentMenu = false
                                viewModel.clearRecentDocuments()
                            },
                        )
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
                                ZoomablePdfPage(
                                    state = current,
                                    tapModeActive = annotateMode || signatureMode,
                                    showAnnotationMarkers = annotateMode,
                                    onTap = { xFraction, yFraction ->
                                        if (annotateMode) {
                                            viewModel.addNoteAt(xFraction, yFraction)
                                        } else if (signatureMode) {
                                            viewModel.placeSignatureAt(xFraction, yFraction)
                                        }
                                    },
                                )
                            } else {
                                ContinuousPdfPages(pageCount = current.pageCount, viewModel = viewModel)
                            }
                        }
                    }
                }
            }

            androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            ) {
                PageOpMessageBanner(message = pageOpMessage, onDismiss = viewModel::acknowledgePageOpMessage)
                ExportStateBanner(exportState = exportState, onDismiss = viewModel::acknowledgeExportState)
            }
        }

        val loadedState = state as? ReaderUiState.Loaded
        if (showPropertiesDialog && loadedState != null) {
            DocumentPropertiesDialog(
                state = loadedState,
                onDismiss = { showPropertiesDialog = false },
            )
        }
        if (showDrawSignatureDialog) {
            DrawSignatureDialog(
                onDismiss = { showDrawSignatureDialog = false },
                onSave = { bitmap ->
                    viewModel.saveSignature(bitmap)
                    showDrawSignatureDialog = false
                },
            )
        }
        if (showTypeSignatureDialog) {
            TypeSignatureDialog(
                onDismiss = { showTypeSignatureDialog = false },
                onSave = { bitmap ->
                    viewModel.saveSignature(bitmap)
                    showTypeSignatureDialog = false
                },
            )
        }
    }
}

@Composable
private fun ExportStateBanner(exportState: ExportState, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    when (exportState) {
        is ExportState.Idle -> Unit
        is ExportState.Saving -> Snackbar(modifier = modifier) { Text(stringResource(R.string.reader_export_saving)) }
        is ExportState.Success -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text("OK") } }) {
                Text(stringResource(R.string.reader_export_success))
            }
        }
        is ExportState.Failure -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text("OK") } }) {
                Text(exportState.reason)
            }
        }
    }
}

@Composable
private fun PageOpMessageBanner(message: String?, onDismiss: () -> Unit) {
    if (message != null) {
        Snackbar(action = { TextButton(onClick = onDismiss) { Text("OK") } }) {
            Text(message)
        }
    }
}

/** Page-operations menu (PDF-006): rotate/delete the current page, or merge another document in. */
@Composable
private fun PageOpsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onMerge: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_rotate)) }, onClick = onRotate)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_delete)) }, onClick = onDelete)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_merge)) }, onClick = onMerge)
    }
}

@Composable
private fun DocumentPropertiesDialog(state: ReaderUiState.Loaded, onDismiss: () -> Unit) {
    val metadata = state.documentMetadata
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.reader_properties_dismiss)) }
        },
        title = { Text(stringResource(R.string.reader_properties_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(R.string.reader_properties_name, metadata?.displayName ?: "—"))
                Text(stringResource(R.string.reader_properties_pages, state.pageCount))
                val sizeLabel = metadata?.sizeBytes?.let { "$it" } ?: "—"
                Text(stringResource(R.string.reader_properties_size, sizeLabel))
            }
        },
    )
}

/** Dropdown of recently opened documents (PDF-004): tap to reopen, or clear the whole list. */
@Composable
private fun RecentDocumentsMenu(
    expanded: Boolean,
    recentDocuments: List<RecentDocumentEntity>,
    onDismiss: () -> Unit,
    onDocumentSelected: (String) -> Unit,
    onClearHistory: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (recentDocuments.isEmpty()) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_recent_empty)) },
                onClick = onDismiss,
                enabled = false,
            )
        } else {
            recentDocuments.forEach { recent ->
                DropdownMenuItem(
                    text = { Text(recent.displayName) },
                    onClick = { onDocumentSelected(recent.uri) },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.reader_recent_clear)) },
                onClick = onClearHistory,
            )
        }
    }
}

/**
 * Pinch-to-zoom and pan on the current page (PDF-002). Zoom resets when the page changes.
 * While [tapModeActive] is true (annotate mode, PDF-005, or signature-placement mode,
 * PDF-007), zoom/pan is disabled in favor of tap-to-place ([onTap], called with normalized
 * 0f..1f coordinates) — kept as distinct modes rather than trying to reconcile a tap gesture
 * with a simultaneous pinch/pan gesture. [showAnnotationMarkers] gates only the note-marker
 * overlay (matching the existing annotate-mode-only visibility); placed signature stamps are
 * always shown when present, since — unlike a transient marker — they represent a staged
 * edit the user would otherwise think had vanished when leaving placement mode.
 */
@Composable
private fun ZoomablePdfPage(
    state: ReaderUiState.Loaded,
    tapModeActive: Boolean,
    showAnnotationMarkers: Boolean,
    onTap: (xFraction: Float, yFraction: Float) -> Unit,
) {
    var scale by rememberSaveable(state.pageIndex) { mutableStateOf(MIN_ZOOM_SCALE) }
    var offsetX by rememberSaveable(state.pageIndex) { mutableStateOf(0f) }
    var offsetY by rememberSaveable(state.pageIndex) { mutableStateOf(0f) }
    var layoutSize by remember { mutableStateOf(IntSize.Zero) }

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    Box {
        val imageModifier = Modifier
            .onSizeChanged { layoutSize = it }
            .let { base ->
                if (tapModeActive) {
                    base.pointerInput(state.pageIndex) {
                        detectTapGestures { offset ->
                            if (layoutSize.width > 0 && layoutSize.height > 0) {
                                onTap(offset.x / layoutSize.width, offset.y / layoutSize.height)
                            }
                        }
                    }
                } else {
                    base
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY,
                        )
                        .transformable(state = transformState)
                }
            }
            .rotate(state.pageRotationDegrees.toFloat())

        Image(
            bitmap = remember(state.pageBitmap) { state.pageBitmap.asImageBitmap() },
            contentDescription = stringResource(R.string.reader_page_label, state.pageIndex + 1, state.pageCount),
            modifier = imageModifier,
        )

        if (showAnnotationMarkers && layoutSize.width > 0) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val radiusPx = MARKER_RADIUS_DP * density
                state.annotationMarkers.forEach { (xFraction, yFraction) ->
                    drawCircle(
                        color = Color.Red,
                        radius = radiusPx,
                        center = Offset(xFraction * size.width, yFraction * size.height),
                    )
                }
            }
        }

        if (state.signatureStamps.isNotEmpty() && layoutSize.width > 0) {
            Canvas(modifier = Modifier.matchParentSize()) {
                state.signatureStamps.forEach { stamp ->
                    val widthPx = stamp.widthFraction * size.width
                    val heightPx = widthPx * stamp.bitmap.height / stamp.bitmap.width.toFloat()
                    drawImage(
                        image = stamp.bitmap.asImageBitmap(),
                        dstOffset = IntOffset((stamp.xFraction * size.width).toInt(), (stamp.yFraction * size.height).toInt()),
                        dstSize = IntSize(widthPx.toInt().coerceAtLeast(1), heightPx.toInt().coerceAtLeast(1)),
                    )
                }
            }
        }
    }
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

/** Menu for creating/selecting/deleting saved visual signatures (PDF-007). */
@Composable
private fun SignatureMenu(
    expanded: Boolean,
    savedSignatures: List<SavedSignature>,
    onDismiss: () -> Unit,
    onDrawNew: () -> Unit,
    onTypeNew: () -> Unit,
    onImportNew: () -> Unit,
    onSelect: (id: String) -> Unit,
    onDelete: (id: String) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // docs/SECURITY_PRIVACY.md §5: "Clearly label visual signatures as appearances only."
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.reader_signature_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            onClick = {},
            enabled = false,
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_signature_menu_draw)) }, onClick = onDrawNew)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_signature_menu_type)) }, onClick = onTypeNew)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_signature_menu_import)) }, onClick = onImportNew)
        if (savedSignatures.isNotEmpty()) {
            HorizontalDivider()
            savedSignatures.forEach { signature ->
                DropdownMenuItem(
                    text = {
                        Row {
                            Image(
                                bitmap = remember(signature.id) { signature.bitmap.asImageBitmap() },
                                contentDescription = null,
                                modifier = Modifier.height(24.dp),
                            )
                            Text(" " + stringResource(R.string.reader_signature_menu_place))
                        }
                    },
                    onClick = { onSelect(signature.id) },
                    trailingIcon = {
                        TextButton(onClick = { onDelete(signature.id) }) {
                            Text(stringResource(R.string.reader_signature_menu_delete))
                        }
                    },
                )
            }
        }
    }
}

/** Draw-a-signature dialog (PDF-007): finger-drawn strokes, rendered to a transparent-background bitmap on save. */
@Composable
private fun DrawSignatureDialog(onDismiss: () -> Unit, onSave: (Bitmap) -> Unit) {
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signature_draw_title)) },
        text = {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .onSizeChanged { canvasSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset -> strokes.add(mutableStateListOf(offset)) },
                            onDrag = { change, _ -> strokes.lastOrNull()?.add(change.position) },
                        )
                    },
            ) {
                strokes.forEach { points ->
                    for (i in 1 until points.size) {
                        drawLine(color = Color.Black, start = points[i - 1], end = points[i], strokeWidth = 6f)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = strokes.isNotEmpty(),
                onClick = { renderStrokesToBitmap(strokes, canvasSize)?.let(onSave) },
            ) {
                Text(stringResource(R.string.signature_save))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { strokes.clear() }) { Text(stringResource(R.string.signature_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.signature_cancel)) }
            }
        },
    )
}

private fun renderStrokesToBitmap(strokes: List<List<Offset>>, size: IntSize): Bitmap? {
    if (size.width <= 0 || size.height <= 0) return null
    val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 6f
        isAntiAlias = true
        strokeCap = android.graphics.Paint.Cap.ROUND
        strokeJoin = android.graphics.Paint.Join.ROUND
    }
    strokes.forEach { points ->
        for (i in 1 until points.size) {
            canvas.drawLine(points[i - 1].x, points[i - 1].y, points[i].x, points[i].y, paint)
        }
    }
    return bitmap
}

/** Type-a-signature dialog (PDF-007): rendered to a transparent-background bitmap on save. */
@Composable
private fun TypeSignatureDialog(onDismiss: () -> Unit, onSave: (Bitmap) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.signature_type_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.signature_type_placeholder)) },
                )
                if (text.isNotBlank()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Cursive,
                            fontStyle = FontStyle.Italic,
                        ),
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = { onSave(renderTextToBitmap(text)) }) {
                Text(stringResource(R.string.signature_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.signature_cancel)) }
        },
    )
}

private fun renderTextToBitmap(text: String): Bitmap {
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.BLACK
        textSize = 72f
        isAntiAlias = true
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
    }
    val bounds = android.graphics.Rect()
    paint.getTextBounds(text, 0, text.length, bounds)
    val padding = 24
    val width = (bounds.width() + padding * 2).coerceAtLeast(1)
    val height = (bounds.height() + padding * 2).coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.drawText(text, (padding - bounds.left).toFloat(), (padding - bounds.top).toFloat(), paint)
    return bitmap
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
