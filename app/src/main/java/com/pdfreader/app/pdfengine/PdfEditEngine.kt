package com.pdfreader.app.pdfengine

import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfSource
import java.io.File
import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Adapter boundary for PDF *mutation* (PDF-005 annotations, PDF-006 page operations) —
 * separate from [PdfEngine], which only renders. `android.graphics.pdf.PdfRenderer`
 * (behind [PdfEngine]) cannot write annotations or move pages at all; this interface is
 * backed by [PdfBoxEditEngine] (PDFBox-Android), approved as DEC-001 in
 * docs/DECISIONS_AND_OPEN_QUESTIONS.md after a working proof of concept.
 *
 * The two engines are deliberately kept separate rather than merged into one interface:
 * [PdfEngine] stays the simple, fast rendering path for every page view, while this one
 * is only opened when the user actually starts annotating. Per docs/ARCHITECTURE.md §2
 * ("Do not combine multiple PDF engines until a prototype proves that file compatibility
 * ... survive round trips"), that round-trip compatibility was proven in the POC, but the
 * two engines still operate on independent file handles to the same source rather than
 * sharing internal state.
 */
interface PdfEditEngine {

    /** Opens [source] for editing; same semantics/result shape as [PdfEngine.open]. */
    suspend fun open(source: PdfSource): PdfOpenResult

    /**
     * Adds a simple text-note annotation to [pageIndex] (0-based) at ([xFraction], [yFraction])
     * — both in `0f..1f` of the page's width/height, so callers don't need to know the
     * page's point size. Returns false if no document is open or the page index is invalid.
     */
    suspend fun addNoteAnnotation(pageIndex: Int, xFraction: Float, yFraction: Float, text: String): Boolean

    /** Number of annotations currently on [pageIndex] (0-based), including ones just added. */
    suspend fun annotationCount(pageIndex: Int): Int

    /** Number of pages in the currently open document (reflects deletes/merges done so far). */
    suspend fun pageCount(): Int

    /**
     * Rotates [pageIndex] (0-based) by [deltaDegrees] relative to its current rotation
     * (normalized to one of 0/90/180/270). Pass 90 or -90 for a quarter turn. Returns false
     * if no document is open or the page index is invalid. PDF-006.
     */
    suspend fun rotatePage(pageIndex: Int, deltaDegrees: Int): Boolean

    /** Removes [pageIndex] (0-based) from the open document. PDF-006. */
    suspend fun deletePage(pageIndex: Int): Boolean

    /**
     * Moves the page at [fromIndex] to [toIndex] (both 0-based, referring to positions
     * before the move). Returns false if no document is open or either index is invalid.
     * PDF-006.
     */
    suspend fun movePage(fromIndex: Int, toIndex: Int): Boolean

    /**
     * Appends every page of [source] to the end of the currently open document (PDF-006
     * merge). [source] is opened and closed independently — it is never left open or
     * mutated. Returns false (and leaves the open document unchanged) if [source] can't be
     * opened or read.
     */
    suspend fun appendDocument(source: PdfSource): Boolean

    /**
     * Stamps a PNG image (e.g. a saved visual signature, [pngBytes]) onto [pageIndex]
     * (0-based). ([xFraction], [yFraction]) is the top-left corner of the stamp, in
     * `0f..1f` of the page, top-down (matching tap coordinates, same convention as
     * [addNoteAnnotation]). [widthFraction] is the stamp's width as a fraction of the
     * page's width; its height is derived from [pngBytes]'s own pixel aspect ratio, so the
     * image is never stretched. Returns false if no document is open, the page index is
     * invalid, or [pngBytes] can't be decoded as an image. PDF-007.
     */
    suspend fun addImageStamp(pageIndex: Int, xFraction: Float, yFraction: Float, widthFraction: Float, pngBytes: ByteArray): Boolean

    /**
     * Certificate-based (PAdES-B-B, DEC-002 in docs/DECISIONS_AND_OPEN_QUESTIONS.md) digital
     * signature over a snapshot of the currently-open document's current edits — a detached
     * CMS/PKCS#7 signature built via BouncyCastle's documented CMS API (no hand-rolled
     * ASN.1/CMS, per docs/SECURITY_PRIVACY.md §5), covering [certificateChain]'s leaf
     * certificate. [signerName] is shown as the signature's display name (e.g. in a PDF
     * viewer's signature panel), not a security property.
     *
     * Unlike [addNoteAnnotation]/[rotatePage]/etc., this does **not** mutate the engine's
     * live in-memory document — PDFBox's incremental-signing save is a one-shot, terminal
     * operation, so signing is performed against a fresh snapshot instead, leaving the live
     * document free for further edits. Returns a validated temp file (same contract as
     * [writeValidatedTempCopy]: written, reopened, and confirmed to have the expected page
     * count and an actual signature dictionary before being returned) or null on any
     * failure — no document is left partially signed either way. PDF-008.
     */
    suspend fun signWithDetachedCms(
        signerName: String,
        privateKey: PrivateKey,
        certificateChain: List<X509Certificate>,
    ): File?

    /**
     * Per docs/SECURITY_PRIVACY.md §4 ("write outputs to temporary files, validate/reopen,
     * then atomically commit"): saves the in-progress edits to a new app-private temporary
     * file, then reopens *that* file to confirm it is actually valid and has the expected
     * page count, before returning it. Returns null if saving or validation fails — the
     * original source is never touched either way. The caller is responsible for copying
     * the returned temp file to wherever the user actually wants it saved (e.g. a SAF
     * destination Uri from `ACTION_CREATE_DOCUMENT`) and deleting it afterwards.
     */
    suspend fun writeValidatedTempCopy(): File?

    /** Releases any resources held by the currently open document, if any. */
    suspend fun close()
}
