plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Permanent signing key (from GitHub secrets in CI). With it, every new APK installs OVER the old one.
val bumpKeystore: File? = System.getenv("BUMP_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
// Every CI run gets a higher versionCode, so Android always accepts the update.
val ciRun: Int = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0

android {
    namespace = "pt.johnny.bump"
    compileSdk = 36

    defaultConfig {
        applicationId = "pt.johnny.bump"
        minSdk = 26
        targetSdk = 36
        versionCode = 100 + ciRun
        versionName = "1.0.$ciRun"
    }

    signingConfigs {
        if (bumpKeystore != null) {
            create("bump") {
                storeFile = bumpKeystore
                storePassword = System.getenv("BUMP_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("BUMP_KEY_ALIAS") ?: "bump"
                keyPassword = System.getenv("BUMP_KEY_PASSWORD") ?: System.getenv("BUMP_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            // Same key for debug and release: either one updates the other in place.
            if (bumpKeystore != null) signingConfig = signingConfigs.getByName("bump")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (bumpKeystore != null) signingConfigs.getByName("bump") else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.play.services.nearby)

    testImplementation(libs.junit)
}
