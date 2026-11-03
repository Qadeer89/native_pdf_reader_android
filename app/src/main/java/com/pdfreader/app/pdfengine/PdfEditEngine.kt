package com.pdfreader.app.pdfengine

import android.graphics.Bitmap
import com.pdfreader.app.model.PdfAttachment
import com.pdfreader.app.model.PdfFormField
import com.pdfreader.app.model.PdfOpenResult
import com.pdfreader.app.model.PdfOutlineItem
import com.pdfreader.app.model.PdfSearchMatch
import com.pdfreader.app.model.PdfSource
import com.pdfreader.app.model.SignatureVerificationResult
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

    /**
     * Adds a highlight annotation (PDF-005) covering the rectangle on [pageIndex] (0-based)
     * from top-left corner ([xFraction], [yFraction]) with size ([widthFraction],
     * [heightFraction]) — all in `0f..1f` of the page, top-down, same convention as
     * [addImageStamp]. Drawn as a `PDAnnotationTextMarkup`/Highlight with its `QuadPoints`
     * set to the rectangle's four corners — valid even without an underlying text selection
     * to attach to, since this app has no text-position API to select real text with (see
     * [searchText]'s doc). Returns false if no document is open or the page index is invalid.
     */
    suspend fun addHighlightAnnotation(
        pageIndex: Int,
        xFraction: Float,
        yFraction: Float,
        widthFraction: Float,
        heightFraction: Float,
    ): Boolean

    /**
     * Removes the annotation at [annotationIndex] (0-based, into `PDPage.getAnnotations()`'s
     * own list order — the same order a just-added note/highlight/stamp appended to) from
     * [pageIndex] (0-based). Used for PDF-005 undo/delete of a session-added annotation.
     * Returns false if no document is open, the page index is invalid, or [annotationIndex]
     * is out of range for that page's current annotation list.
     */
    suspend fun deleteAnnotation(pageIndex: Int, annotationIndex: Int): Boolean

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
     * Inserts every page of [source] into the currently open document starting at
     * [atIndex] (0-based; `0` inserts before the current first page, `pageCount()`
     * inserts after the current last page — same range as [appendDocument], which is
     * equivalent to `insertDocument(source, pageCount())`). [source] is opened and closed
     * independently, same as [appendDocument]. Returns false (and leaves the open document
     * unchanged) if [source] can't be opened/read or [atIndex] is out of range. PDF-006.
     */
    suspend fun insertDocument(source: PdfSource, atIndex: Int): Boolean

    /**
     * Extracts [pageIndices] (0-based, order preserved as given — may repeat an index) into
     * a brand-new, separate document and returns a validated temp file for it — same
     * contract as [writeValidatedTempCopy]: written, reopened, and confirmed to have exactly
     * [pageIndices].size pages before being returned. Does **not** modify the currently open
     * document (the pages are copied, not moved) — a separate [deletePage] call is how the
     * user would also remove them from the original. Returns null if [pageIndices] is empty,
     * contains an out-of-range index, or extraction/validation fails. PDF-006.
     */
    suspend fun extractPages(pageIndices: List<Int>): File?

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
     * The "validate" half of PDF-008: checks every existing digital signature already in
     * the currently-open document (added by some other tool, or by a prior
     * [signWithDetachedCms] call once exported and reopened) and reports, per signature,
     * what [SignatureVerificationResult] documents — cryptographic consistency and
     * certificate expiry only, explicitly **not** trust-anchor or revocation checking (see
     * that type's doc). Returns an empty list if the document has no signatures or none is
     * open; never throws for a malformed/unreadable signature — such a signature is
     * reported as not cryptographically valid rather than skipped.
     */
    suspend fun verifySignatures(): List<SignatureVerificationResult>

    /**
     * Lists every AcroForm field in the currently-open document, flattened (nested fields
     * included, via PDFBox's own `PDFieldTree`). Returns an empty list if the document has
     * no AcroForm or none is open — not an error, since most PDFs have no form fields at
     * all. PDF-009.
     */
    suspend fun listFormFields(): List<PdfFormField>

    /**
     * Sets [fullyQualifiedName]'s value to [value], via PDFBox's generic
     * `PDField.setValue(String)` (works the same for a text field, checkbox export value, or
     * choice field, without this app needing a per-type UI yet). Returns false if no
     * document is open, no field has that name, or PDFBox itself rejects the value. PDF-009.
     */
    suspend fun setFormFieldValue(fullyQualifiedName: String, value: String): Boolean

    /**
     * Lists every embedded file ("attachment") in the currently-open document's
     * `/Names/EmbeddedFiles` tree. Returns an empty list if the document has none — the
     * overwhelming majority of PDFs don't. PDF-009.
     */
    suspend fun listAttachments(): List<PdfAttachment>

    /**
     * Extracts [name]'s raw bytes into a new app-private temp file the caller owns (same
     * "caller copies it to its real destination and deletes it" contract as
     * [writeValidatedTempCopy]/[extractPages]). Returns null if no document is open, no
     * attachment has that name, or reading its bytes fails. PDF-009.
     */
    suspend fun extractAttachment(name: String): File?

    /**
     * Extracts every embedded-file attachment in the currently-open document at once —
     * batch counterpart to [extractAttachment] (PDF-009), for a document with more than
     * one attachment. Each returned temp file follows [extractAttachment]'s own contract
     * (caller copies it to its real destination and deletes it). Returns an empty list if
     * no document is open or it has no attachments; an attachment whose bytes can't be read
     * is skipped rather than failing the whole batch.
     */
    suspend fun extractAllAttachments(): List<Pair<String, File>>

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

    /**
     * Renders [pageIndex] (0-based) at approximately [targetWidthPx] wide, using this
     * engine's own rendering rather than [PdfEngine]'s. This exists for exactly one case
     * (PDF-013, see planning/ADR.md ADR-020): a password-protected document that this
     * engine can open (given the right password) but `android.graphics.pdf.PdfRenderer`
     * can never open at all, since it has no way to supply a password at this app's
     * minSdk. [ReaderViewModel] only calls this as a fallback — every other document still
     * renders exclusively through [PdfEngine], unchanged. Returns null if no document is
     * open or the page index is out of range.
     */
    suspend fun renderPage(pageIndex: Int, targetWidthPx: Int): Bitmap?

    /**
     * Case-insensitive substring search for [query] across every page of the currently
     * open document, via PDFBox's text extraction (`PDFTextStripper`) — resolves the
     * previously "blocked" PDF-003 gap without a new PDF-engine decision, since PDFBox was
     * already approved as DEC-001 and is already opened alongside every document regardless
     * of which engine renders pages. Returns one [PdfSearchMatch] per occurrence (a page
     * with 3 hits produces 3 entries), in page order; a blank [query] or no open document
     * returns an empty list, never an error.
     */
    suspend fun searchText(query: String): List<PdfSearchMatch>

    /**
     * Lists the currently open document's outline/bookmark tree (PDF-002/PDF-004),
     * flattened in traversal order with each entry's [PdfOutlineItem.depth]. Returns an
     * empty list if the document has no outline or none is open — most PDFs have none.
     */
    suspend fun listOutline(): List<PdfOutlineItem>

    /** Releases any resources held by the currently open document, if any. */
    suspend fun close()
}
