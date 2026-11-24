package io.github.phillhf.iptv.platform

import java.io.File
import java.io.InputStream

// Was der gemeinsame Code vom System braucht, für den Server. Gegenstücke liegen in app/src/main und desktop/.

/** Datenordner mit den verschlüsselten Einstellungsdateien. */
class AppContext(val dataDir: File) {
    init {
        dataDir.mkdirs()
    }

    private val open = HashMap<String, Prefs>()

    @Synchronized
    fun prefs(name: String): Prefs = open.getOrPut(name) { Prefs(File(dataDir, "$name.dat")) }

    @Synchronized
    fun delete(name: String) {
        open.remove(name)
        File(dataDir, "$name.dat").delete()
    }
}

fun AppContext.openPrefs(name: String): Prefs = prefs(name)

fun AppContext.deletePrefs(name: String) = delete(name)

fun AppContext.openAsset(path: String): InputStream =
    AppContext::class.java.getResourceAsStream("/$path") ?: throw java.io.FileNotFoundException(path)

val appVersion: String by lazy { AppContext::class.java.getResource("/version.txt")?.readText()?.trim() ?: "?" }
