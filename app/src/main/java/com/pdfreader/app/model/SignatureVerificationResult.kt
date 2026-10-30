package com.pdfreader.app.model

/**
 * The "validate" half of PDF-008 (docs/TRACEABILITY_MATRIX.md), for a single digital
 * signature already present in an opened document — distinct from *creating* a signature
 * ([com.pdfreader.app.pdfengine.PdfEditEngine.signWithDetachedCms]).
 *
 * Per DEC-002 (docs/DECISIONS_AND_OPEN_QUESTIONS.md), this is deliberately limited: it
 * checks whether the signature's bytes cryptographically match the signed content and
 * certificate, and whether that certificate's own validity window covers right now — it
 * does **not** check the certificate against any trust-anchor/root-CA list (there is no
 * app-curated one in this version) and does **not** check revocation (OCSP/CRL) or verify
 * a timestamp. A result here is never "this signature is trustworthy" — only "this
 * signature is/isn't internally consistent," and the UI must present it that way
 * (docs/SECURITY_PRIVACY.md §5, "state limitations for revocation, timestamp and
 * trust-anchor checks").
 */
data class SignatureVerificationResult(
    /** The signer name stored in the signature dictionary, if any — a self-reported label, not a verified identity. */
    val signerName: String?,
    val signDateEpochMillis: Long?,
    /** True if the CMS signature's bytes actually match the signed content and certificate. */
    val cryptographicallyValid: Boolean,
    /** The signing certificate's subject, e.g. "CN=Jane Doe" — unknown only if the certificate itself couldn't be read. */
    val certificateSubject: String,
    /** True if the certificate's own notBefore/notAfter window covers the current time. */
    val certificateCurrentlyValid: Boolean,
    /**
     * True if the signature's byte range reaches the end of the file — false means content
     * was appended *after* this signature (e.g. a later edit or another signature), which
     * this signature's own coverage says nothing about.
     */
    val coversEntireDocument: Boolean,
)
