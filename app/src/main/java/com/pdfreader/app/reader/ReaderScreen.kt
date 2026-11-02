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
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import com.pdfreader.app.model.PdfAttachment
import com.pdfreader.app.model.PdfFormField
import com.pdfreader.app.model.SignatureVerificationResult
import com.pdfreader.app.signing.SigningIdentity

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
    onSignWithIdentityClick: (alias: String, suggestedName: String) -> Unit,
    onImportPkcs12Click: () -> Unit,
    onPkcs12PasswordSubmittedClick: (password: CharArray, suggestedName: String) -> Unit,
    onInsertDocumentClick: () -> Unit,
    onExtractCurrentPageClick: (suggestedName: String) -> Unit,
    onExtractAttachmentClick: (name: String) -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val layoutMode by viewModel.layoutMode.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val signState by viewModel.signState.collectAsState()
    val extractState by viewModel.extractState.collectAsState()
    val attachmentExtractState by viewModel.attachmentExtractState.collectAsState()
    val printState by viewModel.printState.collectAsState()
    val pageOpMessage by viewModel.pageOpMessage.collectAsState()
    val recentDocuments by viewModel.recentDocuments.collectAsState()
    val savedSignatures by viewModel.savedSignatures.collectAsState()
    val signingIdentities by viewModel.signingIdentities.collectAsState()
    val pkcs12AwaitingPassword by viewModel.pkcs12AwaitingPassword.collectAsState()
    val insertAwaitingPosition by viewModel.insertAwaitingPosition.collectAsState()
    val passwordRequired by viewModel.passwordRequired.collectAsState()
    val passwordError by viewModel.passwordError.collectAsState()
    var showRecentMenu by remember { mutableStateOf(false) }
    var showPageMenu by remember { mutableStateOf(false) }
    var showSignMenu by remember { mutableStateOf(false) }
    var showDigitalSignMenu by remember { mutableStateOf(false) }
    var showPropertiesDialog by remember { mutableStateOf(false) }
    var showDrawSignatureDialog by remember { mutableStateOf(false) }
    var showTypeSignatureDialog by remember { mutableStateOf(false) }
    var showCreateIdentityDialog by remember { mutableStateOf(false) }
    var showSignatureStatusDialog by remember { mutableStateOf(false) }
    var showMovePageDialog by remember { mutableStateOf(false) }
    var showFormFieldsDialog by remember { mutableStateOf(false) }
    var showAttachmentsDialog by remember { mutableStateOf(false) }
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
                        if (loaded.signatureVerifications.isNotEmpty()) {
                            TextButton(onClick = { showSignatureStatusDialog = true }) {
                                Text(stringResource(R.string.reader_signature_status_action))
                            }
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
                            TextButton(
                                onClick = {
                                    val baseName = loaded.documentMetadata?.displayName ?: "document.pdf"
                                    viewModel.requestPrint(baseName)
                                },
                            ) {
                                Text(stringResource(R.string.reader_print_action))
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
                                    onMove = {
                                        showPageMenu = false
                                        showMovePageDialog = true
                                    },
                                    onInsert = {
                                        showPageMenu = false
                                        onInsertDocumentClick()
                                    },
                                    onExtract = {
                                        showPageMenu = false
                                        val baseName = loaded.documentMetadata?.displayName
                                            ?.removeSuffix(".pdf")
                                            ?: "document"
                                        onExtractCurrentPageClick("$baseName-page${loaded.pageIndex + 1}.pdf")
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
                            Box {
                                TextButton(onClick = { showDigitalSignMenu = true }) {
                                    Text(stringResource(R.string.reader_digital_sign_action))
                                }
                                val baseName = loaded.documentMetadata?.displayName
                                    ?.removeSuffix(".pdf")
                                    ?: "document"
                                DigitalSignMenu(
                                    expanded = showDigitalSignMenu,
                                    signingIdentities = signingIdentities,
                                    onDismiss = { showDigitalSignMenu = false },
                                    onCreateNewIdentity = {
                                        showDigitalSignMenu = false
                                        showCreateIdentityDialog = true
                                    },
                                    onSignWithIdentity = { alias ->
                                        showDigitalSignMenu = false
                                        onSignWithIdentityClick(alias, "$baseName-signed.pdf")
                                    },
                                    onDeleteIdentity = viewModel::deleteSigningIdentity,
                                    onImportPkcs12 = {
                                        showDigitalSignMenu = false
                                        onImportPkcs12Click()
                                    },
                                )
                            }
                            if (loaded.formFields.isNotEmpty()) {
                                TextButton(onClick = { showFormFieldsDialog = true }) {
                                    Text(stringResource(R.string.reader_forms_action))
                                }
                            }
                            if (loaded.attachments.isNotEmpty()) {
                                TextButton(onClick = { showAttachmentsDialog = true }) {
                                    Text(stringResource(R.string.reader_attachments_action))
                                }
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
                SignStateBanner(signState = signState, onDismiss = viewModel::acknowledgeSignState)
                ExtractStateBanner(extractState = extractState, onDismiss = viewModel::acknowledgeExtractState)
                AttachmentExtractStateBanner(
                    extractState = attachmentExtractState,
                    onDismiss = viewModel::acknowledgeAttachmentExtractState,
                )
                PrintStateBanner(printState = printState, onDismiss = viewModel::acknowledgePrintState)
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
        if (showCreateIdentityDialog) {
            CreateSigningIdentityDialog(
                onDismiss = { showCreateIdentityDialog = false },
                onCreate = { displayName ->
                    viewModel.createSigningIdentity(displayName)
                    showCreateIdentityDialog = false
                },
            )
        }
        if (pkcs12AwaitingPassword) {
            val baseName = loadedState?.documentMetadata?.displayName?.removeSuffix(".pdf") ?: "document"
            Pkcs12PasswordDialog(
                onDismiss = { viewModel.cancelPkcs12Import() },
                onSubmit = { password -> onPkcs12PasswordSubmittedClick(password, "$baseName-signed.pdf") },
            )
        }
        if (showSignatureStatusDialog && loadedState != null) {
            SignatureVerificationDialog(
                results = loadedState.signatureVerifications,
                onDismiss = { showSignatureStatusDialog = false },
            )
        }
        if (showMovePageDialog && loadedState != null) {
            MovePageDialog(
                currentPageNumber = loadedState.pageIndex + 1,
                pageCount = loadedState.pageCount,
                onDismiss = { showMovePageDialog = false },
                onMove = { targetPageNumber ->
                    viewModel.movePageTo(targetPageNumber)
                    showMovePageDialog = false
                },
            )
        }
        if (insertAwaitingPosition && loadedState != null) {
            InsertPositionDialog(
                pageCount = loadedState.pageCount,
                onDismiss = { viewModel.cancelInsert() },
                onInsert = { targetPageNumber -> viewModel.insertDocumentAt(targetPageNumber) },
            )
        }
        if (passwordRequired) {
            PasswordPromptDialog(
                errorMessage = passwordError,
                onDismiss = { viewModel.cancelPasswordPrompt() },
                onSubmit = { password -> viewModel.submitPassword(password) },
            )
        }
        if (showFormFieldsDialog && loadedState != null) {
            FormFieldsDialog(
                fields = loadedState.formFields,
                onDismiss = { showFormFieldsDialog = false },
                onSave = { fullyQualifiedName, value -> viewModel.setFormFieldValue(fullyQualifiedName, value) },
            )
        }
        if (showAttachmentsDialog && loadedState != null) {
            AttachmentsDialog(
                attachments = loadedState.attachments,
                onDismiss = { showAttachmentsDialog = false },
                onExtract = { name ->
                    showAttachmentsDialog = false
                    onExtractAttachmentClick(name)
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
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(stringResource(R.string.reader_export_success))
            }
        }
        is ExportState.Failure -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(exportState.reason)
            }
        }
    }
}

@Composable
private fun SignStateBanner(signState: ExportState, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    when (signState) {
        is ExportState.Idle -> Unit
        is ExportState.Saving -> Snackbar(modifier = modifier) { Text(stringResource(R.string.digital_sign_saving)) }
        is ExportState.Success -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(stringResource(R.string.digital_sign_success))
            }
        }
        is ExportState.Failure -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(signState.reason)
            }
        }
    }
}

@Composable
private fun ExtractStateBanner(extractState: ExportState, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    when (extractState) {
        is ExportState.Idle -> Unit
        is ExportState.Saving -> Snackbar(modifier = modifier) { Text(stringResource(R.string.extract_saving)) }
        is ExportState.Success -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(stringResource(R.string.extract_success))
            }
        }
        is ExportState.Failure -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(extractState.reason)
            }
        }
    }
}

/**
 * [PrintState.Ready] is intentionally silent here — by the time it arrives, the system
 * print dialog is about to appear (or already has), so a snackbar for it would just be
 * noise. Only the preparing/failure states need feedback.
 */
@Composable
private fun PrintStateBanner(printState: PrintState, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    when (printState) {
        is PrintState.Idle, is PrintState.Ready -> Unit
        is PrintState.Preparing -> Snackbar(modifier = modifier) { Text(stringResource(R.string.reader_print_preparing)) }
        is PrintState.Failure -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(printState.reason)
            }
        }
    }
}

@Composable
private fun AttachmentExtractStateBanner(extractState: ExportState, modifier: Modifier = Modifier, onDismiss: () -> Unit) {
    when (extractState) {
        is ExportState.Idle -> Unit
        is ExportState.Saving -> Snackbar(modifier = modifier) { Text(stringResource(R.string.attachment_extract_saving)) }
        is ExportState.Success -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(stringResource(R.string.attachment_extract_success))
            }
        }
        is ExportState.Failure -> {
            Snackbar(modifier = modifier, action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
                Text(extractState.reason)
            }
        }
    }
}

@Composable
private fun PageOpMessageBanner(message: String?, onDismiss: () -> Unit) {
    if (message != null) {
        Snackbar(action = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } }) {
            Text(message)
        }
    }
}

/** Page-operations menu (PDF-006): rotate/delete/move the current page, or merge another document in. */
@Composable
private fun PageOpsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onMerge: () -> Unit,
    onMove: () -> Unit,
    onInsert: () -> Unit,
    onExtract: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_rotate)) }, onClick = onRotate)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_delete)) }, onClick = onDelete)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_move)) }, onClick = onMove)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_merge)) }, onClick = onMerge)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_insert)) }, onClick = onInsert)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_page_extract)) }, onClick = onExtract)
    }
}

/**
 * Lets the user move the current page to a new 1-based position (PDF-006). A plain numeric
 * target rather than drag-and-drop on the thumbnail strip: `movePage` only affects
 * [ReaderViewModel]'s edit engine, not the live-rendered page bitmaps (same staged pattern as
 * delete/merge, see `planning/ADR.md` ADR-011/ADR-017) — dragging thumbnails that don't
 * actually move would be more confusing than a direct "move to position N" control.
 */
@Composable
private fun MovePageDialog(currentPageNumber: Int, pageCount: Int, onDismiss: () -> Unit, onMove: (targetPageNumber: Int) -> Unit) {
    var targetText by rememberSaveable(currentPageNumber) { mutableStateOf(currentPageNumber.toString()) }
    val target = targetText.toIntOrNull()
    val targetIsValid = target != null && target in 1..pageCount

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.move_page_title, currentPageNumber, pageCount)) },
        text = {
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text(stringResource(R.string.move_page_label, pageCount)) },
                isError = !targetIsValid,
            )
        },
        confirmButton = {
            TextButton(enabled = targetIsValid && target != currentPageNumber, onClick = { onMove(target!!) }) {
                Text(stringResource(R.string.move_page_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.move_page_cancel)) }
        },
    )
}

/**
 * Asks where to insert a just-picked document's pages (PDF-006) — a 1-based position from
 * 1 to `pageCount + 1` inclusive (the latter means "after the last page", same range
 * [ReaderViewModel.insertDocumentAt] accepts).
 */
@Composable
private fun InsertPositionDialog(pageCount: Int, onDismiss: () -> Unit, onInsert: (targetPageNumber: Int) -> Unit) {
    var targetText by rememberSaveable { mutableStateOf((pageCount + 1).toString()) }
    val target = targetText.toIntOrNull()
    val targetIsValid = target != null && target in 1..(pageCount + 1)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.insert_position_title)) },
        text = {
            OutlinedTextField(
                value = targetText,
                onValueChange = { targetText = it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text(stringResource(R.string.insert_position_label, pageCount + 1)) },
                isError = !targetIsValid,
            )
        },
        confirmButton = {
            TextButton(enabled = targetIsValid, onClick = { onInsert(target!!) }) {
                Text(stringResource(R.string.insert_position_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.insert_position_cancel)) }
        },
    )
}

/**
 * Lists every AcroForm field found in the document (PDF-009) with an editable text field
 * per row. [PdfFormField.value] is read/written as a plain string via PDFBox's generic
 * `PDField`, so a checkbox or choice field's "value" here is its raw export-value text
 * (e.g. "Yes"/"Off") rather than a checkbox toggle or a dropdown — a simpler v1 scope than
 * per-field-type widgets, same incremental approach PDF-005 started with a plain text note.
 */
@Composable
private fun FormFieldsDialog(
    fields: List<PdfFormField>,
    onDismiss: () -> Unit,
    onSave: (fullyQualifiedName: String, value: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.form_fields_title)) },
        text = {
            LazyColumn {
                items(fields, key = { it.fullyQualifiedName }) { field ->
                    var text by rememberSaveable(field.fullyQualifiedName) { mutableStateOf(field.value.orEmpty()) }
                    androidx.compose.foundation.layout.Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        Text(field.fullyQualifiedName, style = MaterialTheme.typography.labelMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = text,
                                onValueChange = { text = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = text != field.value.orEmpty(),
                                onClick = { onSave(field.fullyQualifiedName, text) },
                            ) {
                                Text(stringResource(R.string.form_field_save))
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.form_fields_done)) }
        },
    )
}

/** Lists embedded-file attachments found in the document (PDF-009) with an extract action per item. */
@Composable
private fun AttachmentsDialog(
    attachments: List<PdfAttachment>,
    onDismiss: () -> Unit,
    onExtract: (name: String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.attachments_title)) },
        text = {
            LazyColumn {
                items(attachments, key = { it.name }) { attachment ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                            Text(attachment.name)
                            val sizeLabel = attachment.sizeBytes?.let { "$it bytes" } ?: "—"
                            Text(sizeLabel, style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = { onExtract(attachment.name) }) {
                            Text(stringResource(R.string.attachment_extract_action))
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.attachments_done)) }
        },
    )
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
    val deleteSignatureDescription = stringResource(R.string.reader_signature_menu_delete_description)
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
                        TextButton(
                            onClick = { onDelete(signature.id) },
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = deleteSignatureDescription
                            },
                        ) {
                            Text(stringResource(R.string.reader_signature_menu_delete))
                        }
                    },
                )
            }
        }
    }
}

/**
 * Menu for certificate-based digital signing (PDF-008, DEC-002): create/select a device-bound
 * Keystore identity, or sign with an imported PKCS#12 certificate. Deliberately a separate
 * menu/button from [SignatureMenu] (visual signatures, PDF-007) — the two are different
 * things (an image stamp vs. a cryptographic signature) and this project's security doc
 * requires not conflating them in the UI.
 */
@Composable
private fun DigitalSignMenu(
    expanded: Boolean,
    signingIdentities: List<SigningIdentity>,
    onDismiss: () -> Unit,
    onCreateNewIdentity: () -> Unit,
    onSignWithIdentity: (alias: String) -> Unit,
    onDeleteIdentity: (alias: String) -> Unit,
    onImportPkcs12: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // DEC-002 (docs/DECISIONS_AND_OPEN_QUESTIONS.md): this app does not check certificate
        // revocation or offer long-term validation in this version — disclosed here.
        DropdownMenuItem(
            text = {
                Text(
                    stringResource(R.string.reader_digital_sign_menu_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                )
            },
            onClick = {},
            enabled = false,
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_digital_sign_menu_new_identity)) }, onClick = onCreateNewIdentity)
        DropdownMenuItem(text = { Text(stringResource(R.string.reader_digital_sign_menu_import_pkcs12)) }, onClick = onImportPkcs12)
        if (signingIdentities.isNotEmpty()) {
            HorizontalDivider()
            signingIdentities.forEach { identity ->
                val deleteIdentityDescription = stringResource(
                    R.string.reader_digital_sign_menu_delete_identity_description,
                    identity.displayName,
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.reader_digital_sign_menu_use_identity, identity.displayName)) },
                    onClick = { onSignWithIdentity(identity.alias) },
                    trailingIcon = {
                        TextButton(
                            onClick = { onDeleteIdentity(identity.alias) },
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = deleteIdentityDescription
                            },
                        ) {
                            Text(stringResource(R.string.reader_digital_sign_menu_delete_identity))
                        }
                    },
                )
            }
        }
    }
}

/**
 * Shows the result of verifying every digital signature already in the opened document
 * (PDF-008's "validate" half, [ReaderUiState.Loaded.signatureVerifications]). Carries the
 * same DEC-002-required disclosure as [DigitalSignMenu] — this only reports cryptographic
 * self-consistency, never "trusted."
 */
@Composable
private fun SignatureVerificationDialog(results: List<SignatureVerificationResult>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.digital_sign_verify_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(R.string.digital_sign_verify_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                )
                results.forEach { result ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text(
                        stringResource(R.string.digital_sign_verify_signer, result.signerName ?: "—"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = if (result.cryptographicallyValid) {
                            stringResource(R.string.digital_sign_verify_valid)
                        } else {
                            stringResource(R.string.digital_sign_verify_invalid)
                        },
                        color = if (result.cryptographicallyValid) Color.Unspecified else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        if (result.certificateCurrentlyValid) {
                            stringResource(R.string.digital_sign_verify_cert_valid)
                        } else {
                            stringResource(R.string.digital_sign_verify_cert_invalid)
                        },
                    )
                    if (!result.coversEntireDocument) {
                        Text(stringResource(R.string.digital_sign_verify_partial_coverage))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.digital_sign_verify_dismiss)) }
        },
    )
}

@Composable
private fun CreateSigningIdentityDialog(onDismiss: () -> Unit, onCreate: (displayName: String) -> Unit) {
    var displayName by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.digital_sign_create_identity_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.digital_sign_create_identity_placeholder)) },
                )
                Text(
                    stringResource(R.string.digital_sign_create_identity_disclaimer),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(enabled = displayName.isNotBlank(), onClick = { onCreate(displayName) }) {
                Text(stringResource(R.string.digital_sign_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.signature_cancel)) }
        },
    )
}

/**
 * Prompts for the password of a document that failed to open with
 * [com.pdfreader.app.model.PdfOpenError.PasswordProtected] (PDF-013). [errorMessage] is
 * null for the first prompt and set to a specific message once a previously submitted
 * password turned out to be wrong, so the user isn't just shown the same blank field twice
 * with no feedback.
 */
@Composable
private fun PasswordPromptDialog(errorMessage: String?, onDismiss: () -> Unit, onSubmit: (password: String) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.password_prompt_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.password_prompt_label)) },
                    isError = errorMessage != null,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = password.isNotEmpty(), onClick = { onSubmit(password) }) {
                Text(stringResource(R.string.password_prompt_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.password_prompt_cancel)) }
        },
    )
}

@Composable
private fun Pkcs12PasswordDialog(onDismiss: () -> Unit, onSubmit: (password: CharArray) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.digital_sign_pkcs12_password_title)) },
        text = {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                placeholder = { Text(stringResource(R.string.digital_sign_pkcs12_password_placeholder)) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(password.toCharArray()) }) {
                Text(stringResource(R.string.digital_sign_pkcs12_sign_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.signature_cancel)) }
        },
    )
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
