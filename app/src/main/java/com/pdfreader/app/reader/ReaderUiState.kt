package com.pdfreader.app.reader

import android.graphics.Bitmap
import com.pdfreader.app.data.documents.DocumentMetadata
import com.pdfreader.app.model.PdfAttachment
import com.pdfreader.app.model.PdfFormField
import com.pdfreader.app.model.PdfOpenError
import com.pdfreader.app.model.SignatureVerificationResult

/**
 * A visual signature stamped onto a page this session (PDF-007). ([xFraction], [yFraction])
 * is the stamp's top-left corner and [widthFraction] its width, both normalized `0f..1f` of
 * the displayed page — same convention as [ReaderUiState.Loaded.annotationMarkers]. Height is
 * derived from [bitmap]'s own aspect ratio at draw time, so it is never stretched.
 */
data class SignatureStamp(
    val xFraction: Float,
    val yFraction: Float,
    val widthFraction: Float,
    val bitmap: Bitmap,
)

/** UI state for the reader screen. */
sealed class ReaderUiState {
    data object Loading : ReaderUiState()

    data class Loaded(
        val pageIndex: Int,
        val pageCount: Int,
        val pageBitmap: Bitmap,
        val documentMetadata: DocumentMetadata? = null,
        /** Normalized (0f..1f, top-down) positions of note annotations added this session on [pageIndex]. */
        val annotationMarkers: List<Pair<Float, Float>> = emptyList(),
        val canAnnotate: Boolean = false,
        /** Visual-only rotation feedback for [pageIndex], this session only — see [ReaderViewModel.rotateCurrentPage]. */
        val pageRotationDegrees: Int = 0,
        /** Signatures stamped onto [pageIndex] this session — see [ReaderViewModel.placeSignatureAt]. */
        val signatureStamps: List<SignatureStamp> = emptyList(),
        /**
         * Existing digital signatures already in the opened document (PDF-008's "validate"
         * half), computed once when the document was opened — not staged or session-only
         * like the fields above. Empty for a document with no signatures.
         */
        val signatureVerifications: List<SignatureVerificationResult> = emptyList(),
        /**
         * AcroForm fields and embedded-file attachments already in the opened document
         * (PDF-009), computed once when the document was opened — same shape as
         * [signatureVerifications]. [formFields] is re-fetched after a successful
         * [ReaderViewModel.setFormFieldValue] so edited values are reflected immediately.
         * Both are empty for the overwhelming majority of PDFs that have neither.
         */
        val formFields: List<PdfFormField> = emptyList(),
        val attachments: List<PdfAttachment> = emptyList(),
    ) : ReaderUiState()

    data class Error(val error: PdfOpenError) : ReaderUiState()
}
