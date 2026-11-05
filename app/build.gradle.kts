import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    // PDF-011 (cloud sync): reads app/google-services.json, generated from the real Firebase
    // project created 2026-11-04 (see docs/DECISIONS_AND_OPEN_QUESTIONS.md). That file is not
    // a secret — Firebase's own docs say it's safe in a public repo; access is enforced by
    // Firestore/Storage security rules, not by hiding the API key.
    alias(libs.plugins.google.services)
}

// Phase 7 (release, backlog item 12): release signing scaffold. Reads from
// `local.properties` (never committed — see .gitignore) rather than a committed keystore,
// so this file is safe to check in with no real secret in it. Nothing here has been
// exercised: there is no actual keystore file, Play Console account, or JDK/Android SDK on
// this machine to run `./gradlew bundleRelease` and confirm it produces a working signed
// AAB. Until `local.properties` provides real values, `signingConfigs.release` falls back
// to the debug signing config so `assembleRelease`/`bundleRelease` still work locally (as an
// unsigned-for-the-Play-Store debug-signed build) rather than failing outright.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseKeystorePath = localProperties.getProperty("RELEASE_KEYSTORE_PATH")
val hasReleaseSigningConfig = releaseKeystorePath != null && rootProject.file(releaseKeystorePath).exists()

android {
    namespace = "com.pdfreader.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pdfreader.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-slice"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = rootProject.file(releaseKeystorePath!!)
                storePassword = localProperties.getProperty("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseSigningConfig) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // Forces androidx.fragment's resolved version above 1.3.0 (androidx.activity's own
    // ActivityResult API requirement — lintVitalRelease fails a release build otherwise).
    // Firebase Auth's transitive play-services-auth 20.7.0 pulls fragment 1.1.0 and nothing
    // else in the graph requested higher, so Gradle picked that stale version by default.
    implementation(libs.androidx.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // PDF-005/PDF-006 (annotations/page-ops) engine. docs/ARCHITECTURE.md §2 gate passed via
    // a POC (app/src/test/.../poc/PdfBoxAndroidEnginePocTest.kt); approved as DEC-001 in
    // docs/DECISIONS_AND_OPEN_QUESTIONS.md. android.graphics.pdf.PdfRenderer (PlatformPdfRendererEngine)
    // remains the engine for baseline rendering/navigation (PDF-002) — the two are not merged.
    implementation(libs.pdfbox.android)

    // PDF-011 (cloud sync), gated behind the real Firebase project created 2026-11-04 (see
    // docs/DECISIONS_AND_OPEN_QUESTIONS.md). Auth/Firestore/Storage only — no Analytics (not
    // enabled on the Firebase project) and no Crashlytics/Performance (not decided yet).
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)

    // Mobile ads (DEC-004, docs/DECISIONS_AND_OPEN_QUESTIONS.md): AdMob + the UMP consent
    // SDK that gates every ad request. Ships with Google's own published test App
    // ID/ad-unit ID (see AndroidManifest.xml / AdIds.kt) until a real AdMob account exists.
    implementation(libs.play.services.ads)
    implementation(libs.user.messaging.platform)

    // PDF-011 (cloud sync): real Google Sign-In via Credential Manager, gated behind
    // Authentication being enabled for the Google provider in the real Firebase project
    // (done 2026-11-05 — google-services.json now has an oauth_client web client entry,
    // which the google-services plugin turns into R.string.default_web_client_id below).
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    // Already present transitively (pulled in by the Credential Manager/Firebase stack
    // above) — declared explicitly because app code calls its Task.await() extension
    // directly in FirebaseCloudAuthRepository/FirebaseCloudSyncRepository.
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.junit)
    // Robolectric pulls in org.bouncycastle:bcprov-jdk18on transitively (its own test
    // infra), which collides on the classpath with the bcprov/bcpkix-jdk15to18:1.72 that
    // PDFBox-Android brings as a *production* dependency (same java package, different
    // artifact, so Gradle doesn't dedupe them) — causes NoSuchFieldError when both versions'
    // classes get mixed at runtime (see PdfSignaturePocTest, DEC-002's signing POC). This
    // collision is test-classpath-only; confirmed via `./gradlew :app:dependencies
    // --configuration debugRuntimeClasspath` that only the jdk15to18 variant reaches the
    // shipped app. Excluding the jdk18on variant here keeps the test classpath consistent
    // with production.
    testImplementation(libs.robolectric) {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcpkix-jdk18on")
        exclude(group = "org.bouncycastle", module = "bcutil-jdk18on")
    }
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Robolectric's PdfRenderer shadow uses reflection into java.io.FileDescriptor, which the
// JDK 17 module system blocks by default. Required for ":app:testDebugUnitTest" to pass
// on JDK 17+ (see planning/ADR.md ADR-006).
tasks.withType<Test>().configureEach {
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
    )
    testLogging {
        showStandardStreams = true
    }
}
