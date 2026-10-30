package com.pdfreader.app.reader

/** State of exporting an annotated copy to a user-chosen destination (PDF-005/PDF-010). */
sealed class ExportState {
    data object Idle : ExportState()
    data object Saving : ExportState()
    data object Success : ExportState()
    data class Failure(val reason: String) : ExportState()
}
