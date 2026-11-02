package com.pdfreader.app.reader

import java.io.File

/**
 * State of handing the current document off to the Android print framework (PDF-010).
 * [Ready] carries a validated temp copy (with staged edits applied, same as
 * [ReaderViewModel.exportAnnotatedCopy]) that the caller owns until the print job finishes.
 */
sealed class PrintState {
    data object Idle : PrintState()
    data object Preparing : PrintState()
    data class Ready(val file: File, val documentName: String) : PrintState()
    data class Failure(val reason: String) : PrintState()
}
