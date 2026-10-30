package com.pdfreader.app.signing

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val ALIAS_PREFIX = "pdfreader-signing-"
private const val KEY_SIZE_BITS = 2048
private const val VALIDITY_MILLIS = 100L * 365 * 24 * 60 * 60 * 1000 // ~100 years; a device-bound key has no separate "expiry" concern of its own.

/**
 * Real [SigningIdentityManager], backed by the "AndroidKeyStore" provider. Cannot be
 * exercised under Robolectric — there is no real Keystore/secure-hardware backing on the
 * host JVM, same class of gap as `PlatformPdfRendererEngine` (planning/ADR.md ADR-006) —
 * so this has no unit test of its own; `PdfReaderViewModelSmokeTest` uses a fake instead.
 */
class AndroidKeystoreSigningIdentityManager(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SigningIdentityManager {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    override suspend fun listIdentities(): List<SigningIdentity> = withContext(ioDispatcher) {
        val keyStore = keyStore()
        keyStore.aliases().asSequence()
            .filter { it.startsWith(ALIAS_PREFIX) }
            .mapNotNull { alias ->
                val certificate = keyStore.getCertificate(alias) as? X509Certificate ?: return@mapNotNull null
                SigningIdentity(
                    alias = alias,
                    displayName = certificate.subjectX500Principal.name.removePrefix("CN=").substringBefore(","),
                    certificate = certificate,
                    createdAtEpochMillis = certificate.notBefore.time,
                )
            }
            .sortedByDescending { it.createdAtEpochMillis }
            .toList()
    }

    override suspend fun createIdentity(displayName: String): SigningIdentity = withContext(ioDispatcher) {
        val alias = ALIAS_PREFIX + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, ANDROID_KEY_STORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setKeySize(KEY_SIZE_BITS)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(X500Principal("CN=$displayName"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now))
                .setCertificateNotAfter(Date(now + VALIDITY_MILLIS))
                .build(),
        )
        generator.generateKeyPair()

        val certificate = keyStore().getCertificate(alias) as X509Certificate
        SigningIdentity(alias = alias, displayName = displayName, certificate = certificate, createdAtEpochMillis = now)
    }

    override suspend fun deleteIdentity(alias: String) = withContext(ioDispatcher) {
        keyStore().deleteEntry(alias)
    }

    override suspend fun loadSigningMaterial(alias: String): Pair<PrivateKey, List<X509Certificate>>? =
        withContext(ioDispatcher) {
            val keyStore = keyStore()
            if (!keyStore.containsAlias(alias)) return@withContext null
            val privateKey = keyStore.getKey(alias, null) as? PrivateKey ?: return@withContext null
            val chain = keyStore.getCertificateChain(alias)?.map { it as X509Certificate } ?: return@withContext null
            privateKey to chain
        }
}
