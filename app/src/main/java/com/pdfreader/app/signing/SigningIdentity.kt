package com.pdfreader.app.signing

import java.security.cert.X509Certificate

/**
 * A self-signed, device-bound signing identity backed by Android Keystore (DEC-002,
 * docs/DECISIONS_AND_OPEN_QUESTIONS.md): [alias] names the key entry inside the
 * "AndroidKeyStore" provider, and [certificate] is the self-signed cert Android generates
 * alongside it — safe to keep around in memory/UI, since it's public by definition. The
 * private key itself never leaves secure hardware/the Keystore and is never exposed here.
 *
 * Per DEC-002, this is device-bound by design: there is no recovery across a reinstall or
 * device change, and the UI must disclose that (not implemented separately — [createdAtEpochMillis]
 * exists to let the UI show "created on <date>" alongside such a disclosure).
 */
data class SigningIdentity(
    val alias: String,
    val displayName: String,
    val certificate: X509Certificate,
    val createdAtEpochMillis: Long,
)
