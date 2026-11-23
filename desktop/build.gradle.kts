import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}

// Eine Versionsnummer für alle Fassungen
val appVersion = Regex("versionName = \"([^\"]+)\"").find(file("../app/build.gradle.kts").readText())!!.groupValues[1]

kotlin {
    sourceSets.main { kotlin.srcDir("../app/src/shared/java") }
    // Tests ohne Android laufen auch hier
    sourceSets.test {
        kotlin.srcDir("../app/src/test/java")
        kotlin.exclude("**/FakeImages.kt", "**/FocusTest.kt", "**/ScreenshotTest.kt")
    }
    compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21 }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// Übersetzungen
sourceSets.main { resources.srcDir("../app/src/main/assets") }

dependencies {
    implementation(compose.desktop.windows_x64)
    implementation(libs.desktop.material3)
    implementation(libs.desktop.icons)
    implementation(libs.desktop.lifecycle.compose)
    implementation(libs.desktop.lifecycle.viewmodel)
    implementation(libs.coroutines.swing)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.sqlite)
    implementation(libs.json)
    implementation(libs.smbj)
    implementation(libs.zxing)
    testImplementation(libs.junit)
}

tasks.processResources {
    val version = appVersion
    inputs.property("version", version)
    doLast { destinationDir.resolve("version.txt").writeText(version) }
}

// ---------- libVLC (LGPL-Paket von VideoLAN, unverändert) ----------

val vlcVersion = "3.0.24"
val vlcSha256 = "3da3f37ee2b2b10e5c788d830a3b513658c36521ea1b0f966ed86eb69f41ef3c"
val vlcDir = layout.buildDirectory.dir("appResources/windows-x64/vlc")

val vlc by tasks.registering {
    inputs.property("sha", vlcSha256)
    outputs.dir(vlcDir)
    val cache = layout.buildDirectory.file("libvlc-$vlcVersion.nupkg").get().asFile
    val target = vlcDir.get().asFile
    val address = "https://api.nuget.org/v3-flatcontainer/videolan.libvlc.windows/$vlcVersion/videolan.libvlc.windows.$vlcVersion.nupkg"
    val sha = vlcSha256
    doLast {
        fun hash() = MessageDigest.getInstance("SHA-256").digest(cache.readBytes()).joinToString("") { "%02x".format(it) }
        if (!cache.exists() || hash() != sha) {
            cache.parentFile.mkdirs()
            URI(address).toURL().openStream().use { i -> cache.outputStream().use { i.copyTo(it) } }
            check(hash() == sha) { "Falsche Prüfsumme von libVLC: ${hash()}" }
        }
        target.deleteRecursively()
        ZipFile(cache).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("build/x64/") }.forEach { e ->
                val rel = e.name.removePrefix("build/x64/")
                if (rel.endsWith(".lib") || rel.endsWith(".h") || rel.endsWith(".hpp") || rel.startsWith("include/")) return@forEach
                val f = target.resolve(rel)
                f.parentFile.mkdirs()
                zip.getInputStream(e).use { i -> f.outputStream().use { i.copyTo(it) } }
            }
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(vlc) }

tasks.test {
    dependsOn(vlc)
    systemProperty("compose.application.resources.dir", layout.buildDirectory.dir("appResources/windows-x64").get().asFile.path)
    systemProperty("klartv.out", layout.buildDirectory.dir("bilder").get().asFile.path)
    // Adresse eines Films für den VLC-Test, sonst wird er übersprungen
    // Nie in die echten Daten des Nutzers schreiben
    systemProperty("klartv.data", layout.buildDirectory.dir("testdaten").get().asFile.path)
    systemProperty("klartv.window", System.getProperty("klartv.window", ""))
    systemProperty("klartv.testVideo", System.getProperty("klartv.testVideo", ""))
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

compose.desktop {
    application {
        mainClass = "io.github.phillhf.iptv.MainKt"
        jvmArgs += listOf("-Xmx1500m", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            packageName = "KlarTV"
            packageVersion = appVersion
            description = "Klar TV"
            vendor = "phillhf"
            modules("java.sql", "java.naming", "java.management", "jdk.unsupported", "jdk.crypto.ec", "jdk.localedata")
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            windows { iconFile.set(file("icon.ico")) }
        }
    }
}
