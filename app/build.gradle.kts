plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// versionCode auto-derived from the git commit count: monotonic, bumps every
// commit, so the in-app updater can compare against the latest release.
val commitCount: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(1)
val tgstreamVersionCode: Int = providers.gradleProperty("tgstreamVersionCode")
    .map { it.toInt() }
    .getOrElse(commitCount)
val tgstreamVersionName: String = providers.gradleProperty("tgstreamVersionName")
    .getOrElse("0.1.$commitCount")
val releaseStoreFilePath = providers.gradleProperty("TGSTREAM_RELEASE_STORE_FILE")
    .orElse(providers.environmentVariable("TGSTREAM_RELEASE_STORE_FILE"))
    .orNull
val releaseStorePassword = providers.gradleProperty("TGSTREAM_RELEASE_STORE_PASSWORD")
    .orElse(providers.environmentVariable("TGSTREAM_RELEASE_STORE_PASSWORD"))
    .orNull
val releaseKeyAlias = providers.gradleProperty("TGSTREAM_RELEASE_KEY_ALIAS")
    .orElse(providers.environmentVariable("TGSTREAM_RELEASE_KEY_ALIAS"))
    .orNull
val releaseKeyPassword = providers.gradleProperty("TGSTREAM_RELEASE_KEY_PASSWORD")
    .orElse(providers.environmentVariable("TGSTREAM_RELEASE_KEY_PASSWORD"))
    .orNull
val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "net.fatshotty.tgstream"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.fatshotty.tgstream"
        minSdk = 24
        targetSdk = 36
        versionCode = tgstreamVersionCode
        versionName = tgstreamVersionName

        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One APK per ABI (~80-85 MB each) instead of one 162 MB universal, so the
    // in-app updater downloads only the device's ABI. Default = arm64-v8a (modern
    // phones + Apple-Silicon emulator) + armeabi-v7a (32-bit TV boxes). Override
    // for one-off targets, e.g. `-PabiList=x86_64` for an x86_64 redroid container.
    // (Replaces ndk abiFilters — they can't coexist.)
    val abiList = (project.findProperty("abiList") as String?)
        ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?: listOf("arm64-v8a", "armeabi-v7a")
    splits {
        abi {
            isEnable = true
            reset()
            include(*abiList.toTypedArray())
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseSigning) {
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
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
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
