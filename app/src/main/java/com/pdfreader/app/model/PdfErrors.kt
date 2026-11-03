package com.pdfreader.app.model

/**
 * Typed failure reasons for PDF open/render operations (MASTER_PROMPT.md "Code quality":
 * typed models and structured errors instead of raw exceptions leaking to the UI).
 */
sealed class PdfOpenError(val userMessage: String) {
    data class NotFound(val path: String) : PdfOpenError("The file could not be found.")
    data class Malformed(val reason: String) : PdfOpenError("This file is not a readable PDF.")
    data class PasswordProtected(val reason: String) : PdfOpenError("This PDF is password protected.")
    data class Unexpected(val reason: String) : PdfOpenError("Something went wrong while opening this PDF.")

    /**
     * Hardening (backlog item 11): the source file is larger than this app is willing to
     * attempt to parse in memory — see [com.pdfreader.app.pdfengine.PdfBoxEditEngine]'s
     * `MAX_SOURCE_FILE_SIZE_BYTES`. Deliberately checked before any parsing is attempted, so
     * an oversized file (accidental or a deliberate resource-exhaustion attempt) fails fast
     * with a clear reason instead of risking an OOM while PDFBox parses it.
     */
    data class TooLarge(val sizeBytes: Long) : PdfOpenError("This PDF is too large to open (${sizeBytes / (1024 * 1024)} MB).")
}

/** Result of attempting to open a PDF through [com.pdfreader.app.pdfengine.PdfEngine]. */
sealed class PdfOpenResult {
    data class Success(val document: PdfDocumentHandle) : PdfOpenResult()
    data class Failure(val error: PdfOpenError) : PdfOpenResult()
}

/** Opaque handle to a successfully opened document; page count is known up front. */
data class PdfDocumentHandle(
    val pageCount: Int,
)
