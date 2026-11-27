plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "io.github.phillhf.iptv"
    compileSdk = 37

    defaultConfig {
        // Paket-ID ist im Play Store dauerhaft, der Anzeigename (strings.xml) nicht
        applicationId = "io.github.phillhf.iptv"
        minSdk = 26
        targetSdk = 37
        // Jeder CI-Lauf bekommt eine höhere Nummer, damit Android und Play das Update annehmen
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "0.10.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Autos brauchen im Play Store ein eigenes Paket mit eigener Nummer
    flavorDimensions += "device"
    productFlavors {
        create("mobile") {
            dimension = "device"
            isDefault = true
        }
        create("car") {
            dimension = "device"
            minSdk = 29
            versionCode = 1_000_000 + (System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1)
        }
    }

    // libVLC bringt je Prozessor rund 25 MB mit: lieber eine APK pro Prozessor.
    // Beim App-Bundle trennt der Play Store selbst.
    splits {
        abi {
            isEnable = gradle.startParameter.taskNames.none { it.contains("bundle", ignoreCase = true) }
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }
    packaging {
        jniLibs.excludes += "lib/x86/**"
    }

    signingConfigs {
        // Fester, bewusst nicht geheimer Debug-Schlüssel: sonst erzeugt jeder CI-Lauf einen neuen
        // und Android verweigert das Update der installierten Testversion.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Upload-Schlüssel für den Play Store: nur in GitHub Actions aus den Secrets, nie im Repo
        val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    // Code, den sich Android und Windows (desktop/) teilen
    sourceSets.getByName("main").kotlin.directories += "src/shared/java"

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-test"
        }
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
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

    // Screenshots der Oberfläche mit Beispieldaten (Robolectric + Roborazzi), siehe ScreenshotTest
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Wiedergabe: Media3/ExoPlayer mit HLS, MPEG-TS und MP4/MKV
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)

    // Zweite Engine mit eigenen Decodern (LGPL 2.1, dynamisch eingebunden)
    implementation(libs.vlc)

    // Übertragen auf Chromecast und Google TV
    implementation(libs.cast.framework)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.fragment)

    // „Weiterschauen“ und Favoriten auf dem Startbildschirm des Fernsehers
    implementation(libs.androidx.tvprovider)

    // Senderlogos und Filmplakate
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.smbj)
    implementation(libs.zxing)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.coil.test)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Läuft in GitHub Actions im Emulator gegen tools/demo_server.py
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext)
}
