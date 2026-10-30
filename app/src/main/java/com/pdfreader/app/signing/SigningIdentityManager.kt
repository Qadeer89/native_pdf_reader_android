package com.pdfreader.app.signing

import java.security.PrivateKey
import java.security.cert.X509Certificate

/**
 * Manages device-bound, Android-Keystore-backed signing identities — one of DEC-002's two
 * approved signing-key sources (the other, PKCS#12 import, is [Pkcs12SigningMaterialLoader]
 * and is deliberately a separate, unrelated type: DEC-002 requires these ship as two
 * distinct features, not a shared abstraction).
 */
interface SigningIdentityManager {

    /** All signing identities created on this device so far, newest first. */
    suspend fun listIdentities(): List<SigningIdentity>

    /** Generates a new RSA-2048 key pair and self-signed certificate (`CN=[displayName]`), both inside "AndroidKeyStore". */
    suspend fun createIdentity(displayName: String): SigningIdentity

    /** Permanently deletes the Keystore entry for [alias]. The key cannot be recovered afterward. */
    suspend fun deleteIdentity(alias: String)

    /**
     * Loads the private key and certificate chain for [alias] for use in a single signing
     * operation. The returned [PrivateKey] is an Android-Keystore-backed handle — its raw
     * key material never leaves secure hardware and this call does not copy it anywhere.
     * Returns null if [alias] no longer exists.
     */
    suspend fun loadSigningMaterial(alias: String): Pair<PrivateKey, List<X509Certificate>>?
}
