package com.pdfreader.app.signing

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Real test for [BouncyCastlePkcs12SigningMaterialLoader] — DEC-002's PKCS#12 key source.
 * Builds an actual PKCS#12 file in memory (not a fake) so this exercises the same BouncyCastle
 * "PKCS12" `KeyStore` type the real app uses, same practice as `PdfBoxEditEngineTest` testing
 * against the real PDFBox engine rather than only a fake.
 */
class Pkcs12SigningMaterialLoaderTest {

    @Before
    fun setUp() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun buildPkcs12Bytes(password: CharArray): ByteArray {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=PKCS12 Test Signer")
        val certBuilder = X509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now),
            Date(now + 365L * 24 * 60 * 60 * 1000),
            subject,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate: X509Certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))

        val keyStore = KeyStore.getInstance("PKCS12", BouncyCastleProvider.PROVIDER_NAME)
        keyStore.load(null, null)
        keyStore.setKeyEntry("test-alias", keyPair.private, password, arrayOf(certificate))
        return ByteArrayOutputStream().also { keyStore.store(it, password) }.toByteArray()
    }

    @Test
    fun loadsThePrivateKeyAndCertificateChainWithTheCorrectPassword() = runTest {
        val password = "correct-password".toCharArray()
        val pkcs12Bytes = buildPkcs12Bytes(password)
        val loader = BouncyCastlePkcs12SigningMaterialLoader(ioDispatcher = Dispatchers.Unconfined)

        val material = loader.load(pkcs12Bytes, password)

        assertEquals("loading with the correct password must succeed", true, material != null)
        assertEquals(1, material!!.second.size)
        assertEquals("CN=PKCS12 Test Signer", material.second.first().subjectX500Principal.name)
    }

    @Test
    fun returnsNullForTheWrongPasswordRatherThanThrowing() = runTest {
        val pkcs12Bytes = buildPkcs12Bytes("correct-password".toCharArray())
        val loader = BouncyCastlePkcs12SigningMaterialLoader(ioDispatcher = Dispatchers.Unconfined)

        val material = loader.load(pkcs12Bytes, "wrong-password".toCharArray())

        assertNull(material)
    }

    @Test
    fun returnsNullForBytesThatAreNotAValidPkcs12File() = runTest {
        val loader = BouncyCastlePkcs12SigningMaterialLoader(ioDispatcher = Dispatchers.Unconfined)

        val material = loader.load("not a pkcs12 file".toByteArray(), "any".toCharArray())

        assertNull(material)
    }

    /**
     * SEC-003 (key/signature safety): the password must not be left sitting in memory once
     * [BouncyCastlePkcs12SigningMaterialLoader] is done with it — on success or on failure.
     */
    @Test
    fun zeroesThePasswordArrayAfterASuccessfulLoad() = runTest {
        val password = "correct-password".toCharArray()
        val pkcs12Bytes = buildPkcs12Bytes(password.copyOf())
        val loader = BouncyCastlePkcs12SigningMaterialLoader(ioDispatcher = Dispatchers.Unconfined)

        val material = loader.load(pkcs12Bytes, password)

        assertEquals("loading with the correct password must succeed", true, material != null)
        assertEquals(CharArray(password.size) { '\u0000' }.concatToString(), password.concatToString())
    }

    @Test
    fun zeroesThePasswordArrayEvenWhenTheLoadFails() = runTest {
        val pkcs12Bytes = buildPkcs12Bytes("correct-password".toCharArray())
        val wrongPassword = "wrong-password".toCharArray()
        val loader = BouncyCastlePkcs12SigningMaterialLoader(ioDispatcher = Dispatchers.Unconfined)

        val material = loader.load(pkcs12Bytes, wrongPassword)

        assertNull(material)
        assertEquals(CharArray(wrongPassword.size) { '\u0000' }.concatToString(), wrongPassword.concatToString())
    }
}
