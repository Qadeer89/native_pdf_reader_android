package com.pdfreader.app.model

/**
 * One text-search match (PDF-003), found via [com.pdfreader.app.pdfengine.PdfEditEngine]'s
 * PDFBox-backed text extraction — resolved without a new PDF-engine decision, since PDFBox
 * was already approved (DEC-001) and is already opened alongside every document; this only
 * draws a new capability (text extraction) from an already-approved dependency. [snippet] is
 * a short window of surrounding page text for display, not a glyph-position highlight —
 * `android.graphics.pdf.PdfRenderer` has no text-position API at all, so on-page highlighting
 * is not attempted; navigation jumps to [pageIndex] instead.
 */
data class PdfSearchMatch(
    val pageIndex: Int,
    val snippet: String,
)

/**
 * One entry in the document's outline/bookmark tree (PDF-002/PDF-004), flattened with
 * [depth] (0 = top level) rather than kept as a nested structure, since the UI renders it as
 * a flat indented list. [pageIndex] is null if the outline entry's destination couldn't be
 * resolved to a page in this document (e.g. an external/URI destination).
 */
data class PdfOutlineItem(
    val title: String,
    val depth: Int,
    val pageIndex: Int?,
)
