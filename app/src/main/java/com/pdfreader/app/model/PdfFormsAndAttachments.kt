package com.pdfreader.app.model

/**
 * One AcroForm field (PDF-009). [value] is read via PDFBox's generic `PDField.getValueAsString()`.
 * [checkboxOnValue] is non-null only for a `PDCheckBox` field — its "checked" export value
 * (e.g. "Yes"), so the UI can render a toggle instead of free text (checked when [value]
 * equals this, unchecked otherwise, typically "Off"). [choiceOptions] is non-empty only for
 * a `PDChoice` (dropdown/list) field — its selectable export values, so the UI can render a
 * dropdown instead of free text. A radio-button group (`PDRadioButton`, also `fieldType ==
 * "Btn"` but neither a plain checkbox nor a choice) still falls back to free text — not yet
 * special-cased.
 */
data class PdfFormField(
    val fullyQualifiedName: String,
    val fieldType: String,
    val value: String?,
    val checkboxOnValue: String? = null,
    val choiceOptions: List<String> = emptyList(),
)

/** One embedded file/attachment (PDF-009) found in the document's `/Names/EmbeddedFiles` tree. */
data class PdfAttachment(
    val name: String,
    val sizeBytes: Long?,
)
