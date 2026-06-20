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

        // Ship only 64-bit ARM: covers all modern phones and the Apple-Silicon
        // emulator. Drops x86/x86_64 (Intel emulators) and armeabi-v7a (old
        // 32-bit phones), cutting the native libs (libvlc.so + libgojni.so)
        // from 4 ABIs to 1 (~200 MB → ~65 MB). Add "armeabi-v7a" if old
        // 32-bit devices must be supported.
        ndk {
            abiFilters += "arm64-v8a"
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

    // Player: libVLC plays/seeks virtually every container/codec (incl. large
    // OpenDML AVI, which ExoPlayer can't seek). media3/leanback removed — unused.
    implementation(libs.libvlc.all)

    implementation(libs.security.crypto)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
