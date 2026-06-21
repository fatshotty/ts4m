plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "net.fatshotty.tgstream"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.fatshotty.tgstream"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ARM only: arm64-v8a (modern phones + Apple-Silicon emulator) and
        // armeabi-v7a (32-bit Android TV boxes, e.g. Leelbox L1). Drops
        // x86/x86_64 (Intel emulators). ~125 MB vs ~265 MB for all 4 ABIs.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    // Go MTProto engine, built with `gomobile bind` (see tgengine/).
    implementation(files("libs/tgengine.aar"))

    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity.ktx)
    implementation(libs.constraintlayout)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.coroutines.android)

    // Players (hybrid): ExoPlayer/Media3 is default (great HDR/Dolby Vision via the
    // platform decoder, lightweight — no bundled codecs); libVLC is the optional
    // engine for containers ExoPlayer can't handle (e.g. large OpenDML AVI).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.libvlc.all)

    implementation(libs.security.crypto)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
