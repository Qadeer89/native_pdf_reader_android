package com.pdfreader.app.model

/**
 * One AcroForm field (PDF-009). [value] is read via PDFBox's generic `PDField.getValueAsString()`
 * — this app does not yet special-case checkboxes/choice fields with their own UI, so a
 * checkbox's "value" is whatever raw export-value string PDFBox reports (e.g. "Yes"/"Off"),
 * typed back in verbatim rather than toggled.
 */
data class PdfFormField(
    val fullyQualifiedName: String,
    val fieldType: String,
    val value: String?,
)

/** One embedded file/attachment (PDF-009) found in the document's `/Names/EmbeddedFiles` tree. */
data class PdfAttachment(
    val name: String,
    val sizeBytes: Long?,
)
