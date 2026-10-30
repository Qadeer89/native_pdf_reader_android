package com.pdfreader.app.poc

import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Calendar
import java.util.Date
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder
import org.bouncycastle.cms.CMSException
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedData
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import org.bouncycastle.util.CollectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DEC-002 gate (docs/DECISIONS_AND_OPEN_QUESTIONS.md): technical proof-of-concept for the
 * proposed PAdES-B-B digital-signature profile — sign a fixture using PDFBox-Android's
 * external-signing hook plus BouncyCastle's own documented CMS API (no hand-rolled
 * ASN.1/CMS, per docs/SECURITY_PRIVACY.md §5), then verify the signature and demonstrate
 * tamper detection.
 *
 * Scope: a self-signed certificate stands in for both candidate key sources in DEC-002
 * (Android Keystore self-signed, and a PKCS#12-imported cert) — this POC only proves the
 * PDFBox+BouncyCastle signing/verification mechanics are sound, not either specific key
 * source's Android-side plumbing (keystore access, PKCS#12 parsing), which is unverified
 * and left for the real implementation increment.
 *
 * Finding carried into that later increment, NOT exercised by this POC: Android's own
 * bundled "BC" security provider had its cryptographic algorithm support deprecated in API
 * 28 and removed entirely in API 31 (only the BKS keystore type remains) — so production
 * code on a real device must explicitly bundle and register this project's own
 * `org.bouncycastle:bcprov-jdk15to18` `BouncyCastleProvider` instance (as this test does),
 * never rely on Android's "BC" provider by name. This POC runs on the host JVM under
 * Robolectric, where that Android-specific provider stripping does not apply, so it cannot
 * by itself confirm this works identically on a real device.
 *
 * Second finding, this time about the test environment itself (fixed in `app/build.gradle.kts`):
 * Robolectric transitively pulls `org.bouncycastle:bcprov-jdk18on` (a different artifact from,
 * but same Java package as, the `bcprov/bcpkix-jdk15to18:1.72` that PDFBox-Android brings as a
 * *production* dependency), which collided on the unit-test classpath and threw
 * `NoSuchFieldError` from mismatched BC class versions — confirmed via `./gradlew
 * :app:dependencies --configuration debugRuntimeClasspath` that only the jdk15to18 variant
 * reaches the shipped app, so the `jdk18on` variant is excluded from the Robolectric test
 * dependency rather than anything being wrong with the production dependency graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfSignaturePocTest {

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    private fun fixtureBytes(): ByteArray =
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .assets.open("fixtures/sample.pdf").use { it.readBytes() }

    private fun generateSelfSignedCertificate(): Pair<KeyPair, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

        val now = System.currentTimeMillis()
        val subject = X500Name("CN=PDF Reader POC Signer")
        val certBuilder = X509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now),
            Date(now),
            Date(now + 365L * 24 * 60 * 60 * 1000),
            subject,
            SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certificate = JcaX509CertificateConverter()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .getCertificate(certBuilder.build(signer))
        return keyPair to certificate
    }

    /** Builds a detached CMS/PKCS#7 signature over [content] — PDFBox's external-signing contract. */
    private fun signCmsDetached(content: ByteArray, privateKey: PrivateKey, certificate: X509Certificate): ByteArray {
        val generator = CMSSignedDataGenerator()
        val contentSigner = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val digestCalculatorProvider =
            JcaDigestCalculatorProviderBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build()
        generator.addSignerInfoGenerator(
            JcaSignerInfoGeneratorBuilder(digestCalculatorProvider).build(contentSigner, certificate),
        )
        generator.addCertificates(CollectionStore(listOf(JcaX509CertificateHolder(certificate))))
        val signedData = generator.generate(CMSProcessableByteArray(content), false)
        return signedData.encoded
    }

    /**
     * Verifies a detached CMS signature over [signedContent] against [certificate]'s public
     * key. BouncyCastle throws [CMSException] (specifically `CMSSignerDigestMismatchException`)
     * rather than returning false when [signedContent] doesn't match what was actually signed
     * — e.g. when it's been tampered with — so that case is treated as "not verified" here
     * too, same as a false cryptographic verification result.
     */
    private fun verifyCmsDetached(cmsBytes: ByteArray, signedContent: ByteArray, certificate: X509Certificate): Boolean {
        val signedData = CMSSignedData(CMSProcessableByteArray(signedContent), cmsBytes)
        val signer = signedData.signerInfos.signers.first()
        val verifier = JcaSimpleSignerInfoVerifierBuilder()
            .setProvider(BouncyCastleProvider.PROVIDER_NAME)
            .build(certificate)
        return try {
            signer.verify(verifier)
        } catch (_: CMSException) {
            false
        }
    }

    @Test
    fun signsFixtureWithPdfBoxExternalSigningAndBouncyCastleCms() {
        val sourceFile = File.createTempFile("pdf-signature-poc-source", ".pdf")
        val signedFile = File.createTempFile("pdf-signature-poc-signed", ".pdf")
        try {
            sourceFile.writeBytes(fixtureBytes())
            val (keyPair, certificate) = generateSelfSignedCertificate()

            PDDocument.load(sourceFile).use { document ->
                val signature = PDSignature().apply {
                    setFilter(PDSignature.FILTER_ADOBE_PPKLITE)
                    setSubFilter(PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED)
                    name = "PDF Reader POC Signer"
                    signDate = Calendar.getInstance()
                }
                document.addSignature(signature)

                FileOutputStream(signedFile).use { out ->
                    val externalSigning = document.saveIncrementalForExternalSigning(out)
                    val contentToSign = externalSigning.content.readBytes()
                    externalSigning.setSignature(signCmsDetached(contentToSign, keyPair.private, certificate))
                }
            }

            PDDocument.load(signedFile).use { reopened ->
                assertEquals(1, reopened.numberOfPages)
                val signatureDictionaries = reopened.signatureDictionaries
                assertEquals(1, signatureDictionaries.size)

                val sig = signatureDictionaries.first()
                val signedContent = sig.getSignedContent(signedFile.readBytes())
                val cmsBytes = sig.contents

                assertTrue(
                    "a valid signature over the actual (untampered) signed content must verify",
                    verifyCmsDetached(cmsBytes, signedContent, certificate),
                )

                val tamperedContent = signedContent.copyOf()
                tamperedContent[tamperedContent.size / 2] =
                    (tamperedContent[tamperedContent.size / 2] + 1).toByte()
                assertFalse(
                    "a single flipped byte in the signed content range must fail verification",
                    verifyCmsDetached(cmsBytes, tamperedContent, certificate),
                )
            }
        } finally {
            sourceFile.delete()
            signedFile.delete()
        }
    }
}
