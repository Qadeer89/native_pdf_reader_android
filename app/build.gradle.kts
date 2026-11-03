plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pdfreader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pdfreader.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-slice"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
