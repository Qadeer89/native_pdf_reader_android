package com.pdfreader.app.signing

import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.jce.provider.BouncyCastleProvider

/**
 * The other of DEC-002's two approved signing-key sources: a user's existing PKCS#12
 * (.p12/.pfx) certificate, decrypted only in memory for a single signing operation — never
 * persisted, logged, or kept around after the caller is done with it (docs/SECURITY_PRIVACY.md
 * §5, "keep private keys out of logs, app preferences, cloud document storage").
 */
interface Pkcs12SigningMaterialLoader {
    /** Returns null if [password] is wrong or [pkcs12Bytes] isn't a valid PKCS#12 file. */
    suspend fun load(pkcs12Bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>>?
}

/**
 * Uses this project's own bundled BouncyCastle provider (already a transitive dependency via
 * PDFBox-Android, DEC-001) to parse the PKCS#12 file, rather than relying on Android's
 * built-in "PKCS12" `KeyStore` type — DEC-002 flagged that the exact Android API level for
 * built-in PKCS12 support wasn't confirmed, and separately, Android's own bundled "BC"
 * provider had its crypto algorithm support removed in API 31 (see `planning/ADR.md`
 * ADR-013). Explicitly requesting our own registered `BouncyCastleProvider` by name sidesteps
 * both gaps rather than depending on whatever the OS happens to provide.
 */
class BouncyCastlePkcs12SigningMaterialLoader(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Pkcs12SigningMaterialLoader {

    init {
        if (java.security.Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            java.security.Security.addProvider(BouncyCastleProvider())
        }
    }

    override suspend fun load(pkcs12Bytes: ByteArray, password: CharArray): Pair<PrivateKey, List<X509Certificate>>? =
        withContext(ioDispatcher) {
            try {
                val keyStore = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
                ByteArrayInputStream(pkcs12Bytes).use { keyStore.load(it, password) }

                val alias = keyStore.aliases().asSequence().firstOrNull { keyStore.isKeyEntry(it) } ?: return@withContext null
                val privateKey = keyStore.getKey(alias, password) as? PrivateKey ?: return@withContext null
                val chain = keyStore.getCertificateChain(alias)?.map { it as X509Certificate } ?: return@withContext null
                privateKey to chain
            } catch (_: Exception) {
                null
            } finally {
                // password is the same array the caller holds (never copied) — zeroing it
                // here, once both KeyStore calls above are done with it, clears it for the
                // caller too, instead of leaving the plaintext for the GC's own schedule.
                password.fill('\u0000')
            }
        }
}
