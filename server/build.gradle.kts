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
    sourceSets.main {
        // Aus der App: Anbieter, Katalog, Programmführer, Einstellungen und Abgleich – ohne Oberfläche
        kotlin.srcDir("../app/src/shared/java")
        kotlin.srcDir("../jvm/src")
        kotlin.include(
            "io/github/phillhf/iptv/data/**",
            "io/github/phillhf/iptv/sync/**",
            "io/github/phillhf/iptv/I18n.kt",
            "io/github/phillhf/iptv/LocalHost.kt",
            "io/github/phillhf/iptv/platform/**",
            "io/github/phillhf/iptv/server/**",
        )
        kotlin.exclude("**/Reminders.kt")
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
    implementation(compose.runtime)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.sqlite)
    implementation(libs.json)
    implementation(libs.zxing)
    testImplementation(libs.junit)
}

tasks.processResources {
    val version = appVersion
    inputs.property("version", version)
    doLast { destinationDir.resolve("version.txt").writeText(version) }
}

// ---------- Fremdprogramme, unverändert und mit fester Prüfsumme ----------

fun download(address: String, sha: String, cache: File) {
    fun hash() = MessageDigest.getInstance("SHA-256").digest(cache.readBytes()).joinToString("") { "%02x".format(it) }
    if (cache.exists() && hash() == sha) return
    cache.parentFile.mkdirs()
    URI(address).toURL().openStream().use { i -> cache.outputStream().use { i.copyTo(it) } }
    check(hash() == sha) { "Falsche Prüfsumme: $address" }
}

// FFmpeg (GPL, Build von gyan.dev) wandelt Ton und bei Bedarf Bild für den Browser um.
val ffmpegVersion = "9.1"
val ffmpegSha256 = "b9bbbb3acece000cbeec994f856649354e52c773807841e78908374b24d658fd"
// WinSW (MIT) startet den Server als Windows-Dienst.
val winswVersion = "2.14.0"
val winswSha256 = "4ebd9dc3e817f80b2bd8c18f5bfe0d550241c1d08b57bd9694777a6e56a7b24f"
val toolsDir = layout.buildDirectory.dir("appResources/windows-x64/tools")

val tools by tasks.registering {
    inputs.property("sha", ffmpegSha256 + winswSha256)
    outputs.dir(toolsDir)
    val target = toolsDir.get().asFile
    val dienst = layout.buildDirectory.dir("dienst").get().asFile.apply { mkdirs() }
    outputs.dir(dienst)
    val ffZip = layout.buildDirectory.file("ffmpeg-$ffmpegVersion.zip").get().asFile
    val winsw = layout.buildDirectory.file("winsw-$winswVersion.exe").get().asFile
    val ffAddress = "https://github.com/GyanD/codexffmpeg/releases/download/$ffmpegVersion/ffmpeg-$ffmpegVersion-essentials_build.zip"
    val swAddress = "https://github.com/winsw/winsw/releases/download/v$winswVersion/WinSW-x64.exe"
    val ffSha = ffmpegSha256
    val swSha = winswSha256
    doLast {
        download(ffAddress, ffSha, ffZip)
        download(swAddress, swSha, winsw)
        target.deleteRecursively()
        target.mkdirs()
        ZipFile(ffZip).use { zip ->
            zip.entries().asSequence().filter { !it.isDirectory }.forEach { e ->
                val name = e.name.substringAfterLast('/')
                val keep = name == "ffmpeg.exe" || name == "ffprobe.exe" || name == "LICENSE" || name == "README.txt"
                if (!keep) return@forEach
                val out = target.resolve(if (name == "LICENSE") "FFMPEG-LICENSE.txt" else if (name == "README.txt") "FFMPEG-README.txt" else name)
                zip.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
            }
        }
        // Der Dienst-Starter liegt im Setup neben dem Programm, nicht bei den Programmdateien
        winsw.copyTo(dienst.resolve("KlarTV-Server-Dienst.exe"), overwrite = true)
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(tools) }

tasks.test {
    dependsOn(tools)
    systemProperty("klartv.tools", toolsDir.get().asFile.path)
    systemProperty("klartv.data", layout.buildDirectory.dir("testdaten").get().asFile.path)
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

tasks.withType<JavaExec>().configureEach {
    systemProperty("klartv.tools", toolsDir.get().asFile.path)
}

compose.desktop {
    application {
        mainClass = "io.github.phillhf.iptv.server.MainKt"
        jvmArgs += listOf("-Xmx1024m", "-Dfile.encoding=UTF-8", "--enable-native-access=ALL-UNNAMED")
        nativeDistributions {
            packageName = "KlarTV-Server"
            packageVersion = appVersion
            description = "Klar TV Server"
            vendor = "phillhf"
            modules("java.sql", "java.naming", "java.management", "jdk.unsupported", "jdk.crypto.ec", "jdk.localedata", "jdk.httpserver")
            appResourcesRootDir.set(layout.buildDirectory.dir("appResources"))
            windows {
                iconFile.set(file("icon.ico"))
                // Mit Konsole: Der Dienst-Starter beendet den Server sauber mit Strg+C
                console = true
            }
        }
    }
}
