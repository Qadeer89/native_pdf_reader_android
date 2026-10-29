package com.pdfreader.app.pdfengine

import android.graphics.Bitmap
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource

/**
 * Adapter boundary between the reader UI/ViewModel and whichever PDF engine backs it.
 *
 * For the Phase 1 vertical slice this is implemented by [PlatformPdfRendererEngine] using
 * the platform `android.graphics.pdf.PdfRenderer`. Per docs/ARCHITECTURE.md §2, the platform
 * renderer is a baseline-rendering-only candidate: it cannot do annotations, page
 * operations, forms, or signing. Those features require a separate, gated PDF-engine
 * decision (see planning/ADR.md ADR-002). Call sites (feature:reader today, other
 * feature areas later) depend only on this interface so the backing engine can be
 * swapped without touching UI code.
 *
 * All methods are suspend and expected to run off the main thread (MASTER_PROMPT.md
 * "Security release blockers": keep expensive PDF operations off main thread).
 */
interface PdfEngine {

    /** Opens [source] and returns the page count on success, or a typed error on failure. */
    suspend fun open(source: PdfSource): PdfOpenResult

    /**
     * Renders [pageIndex] (0-based) of the currently open document at approximately
     * [targetWidthPx] wide, preserving aspect ratio. Returns null if no document is open
     * or the page index is out of range.
     */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap?

    /** Releases any native resources held by the currently open document, if any. */
    suspend fun close()
}
