import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.util.Properties

// Release signing is read from a non-committed keystore.properties at the repo root (see
// keystore.properties.example). When it is absent, assembleRelease still builds — just unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

// The `hev` flavor bundles the hev-socks5-tunnel native library; the `kernel` flavor uses the pure
// Kotlin tun2socks engine instead. Compiling the third_party C sources is slow, so we only wire the
// ndkBuild sources when a hev-flavor variant is actually being built — `kernel` builds skip the NDK
// toolchain entirely (no native compile, no timeout).
val buildingHevVariant = gradle.startParameter.taskNames.any { it.contains("hev", ignoreCase = true) }

// Short git SHA of this build, surfaced in the About screen. CI provides CI_COMMIT_SHORT_SHA; local
// builds fall back to `git rev-parse`, or "unknown" when git is unavailable.
val buildShortSha: String = System.getenv("CI_COMMIT_SHORT_SHA")
    ?: runCatching {
        ProcessBuilder("git", "rev-parse", "--short", "HEAD")
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
            .inputStream.bufferedReader().use { it.readText() }.trim()
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "unknown"

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.alphynia.pakomo"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.alphynia.pakomo"
        minSdk = 29
        targetSdk = 36
        // CI injects the version from the release tag; local builds fall back to these defaults.
        versionCode = (System.getenv("PAKOMO_VERSION_CODE")?.toIntOrNull() ?: 1)
        versionName = System.getenv("PAKOMO_VERSION_NAME") ?: "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Novi self-update wiring. Sources are a lightweight packaging-time input:
        // PAKOMO_UPDATE_SOURCES is a comma-separated, ordered (priority) list of source
        // roots, added/removed per build (empty -> update stays inert). The per-flavor
        // track subpath (kernel/ or hev/) is derived from applicationId at runtime.
        // Prefix an entry with '!' to redact it: Novi still checks/downloads from it, but
        // the UI shows "(redacted)" instead of its URL (display-only; the URL is still in
        // this BuildConfig string). E.g. PAKOMO_UPDATE_SOURCES="https://updates/,!http://127.0.0.1:49221/".
        // The trust anchors below are the only set-once, security-critical values; when
        // absent the controller disables self-update rather than trusting nothing.
        buildConfigField(
            "String",
            "NOVI_UPDATE_SOURCES",
            "\"${System.getenv("PAKOMO_UPDATE_SOURCES") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "NOVI_MANIFEST_KEY_ID",
            "\"${System.getenv("PAKOMO_NOVI_KEY_ID") ?: "manifest-2026"}\"",
        )
        buildConfigField(
            "String",
            "NOVI_MANIFEST_PUBLIC_KEY",
            "\"${System.getenv("PAKOMO_NOVI_MANIFEST_PUBKEY") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "NOVI_APK_SIGNER_SHA256",
            "\"${System.getenv("PAKOMO_NOVI_APK_SIGNER") ?: ""}\"",
        )
        buildConfigField("String", "BUILD_SHA", "\"$buildShortSha\"")

        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a")
        }
    }

    flavorDimensions += "engine"
    productFlavors {
        // Declared first, so the pure-Kotlin engine is the default selected variant (no NDK build).
        // Symmetric applicationId per flavor (com.alphynia.pakomo.<flavor>) + distinct label, so k 版 and h 版
        // install side by side. No legacy users to preserve, so both carry a suffix.
        create("kernel") {
            dimension = "engine"
            applicationIdSuffix = ".kernel"
            versionNameSuffix = "-kernel"
            buildConfigField("boolean", "USE_KOTLIN_KERNEL", "true")
            manifestPlaceholders["appLabel"] = "Pakomo"
        }
        create("hev") {
            dimension = "engine"
            applicationIdSuffix = ".hev"
            versionNameSuffix = "-hev"
            buildConfigField("boolean", "USE_KOTLIN_KERNEL", "false")
            manifestPlaceholders["appLabel"] = "Pakomo"
            if (buildingHevVariant) {
                externalNativeBuild {
                    ndkBuild {
                        arguments += listOf(
                            "APP_PLATFORM=android-29",
                            "APP_SUPPORT_FLEXIBLE_PAGE_SIZES=true",
                        )
                    }
                }
            }
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
        )
    }

    // Only the hev flavor compiles the third_party native tunnel. The path is global (AGP requires
    // it at the android level), but it is wired solely for hev-flavor invocations — see above.
    if (buildingHevVariant) {
        externalNativeBuild {
            ndkBuild {
                path = file("../third_party/hev-socks5-tunnel/Android.mk")
            }
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Self-update: core (manifest verify + download + install) and the optional M3 dialog,
    // consumed from Maven Central (com.alphynia.novi, published by AlfWorks).
    implementation("com.alphynia.novi:novi-core:0.1.1")
    implementation("com.alphynia.novi:novi-compose:0.1.1")

    testImplementation(libs.junit)
}
