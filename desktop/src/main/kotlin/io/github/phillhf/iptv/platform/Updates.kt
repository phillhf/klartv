package io.github.phillhf.iptv.platform

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.update.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates der Windows-App: schaut beim Start und einmal am Tag bei GitHub nach und fragt dann, ob installiert werden soll.
 * Installiert wird nur ein Setup mit gültiger Signatur des Release-Schlüssels.
 */
object AppUpdates {
    const val supported = true

    private val prefs by lazy { AppContext.default.openPrefs("update") }
    private val scope = CoroutineScope(Dispatchers.IO)

    var auto by mutableStateOf(true)
        private set

    fun changeAuto(on: Boolean) {
        auto = on
        prefs.edit().putBoolean("auto", on).apply()
        if (on) scope.launch { check(manual = false) }
    }

    /** Zeile unter dem Schalter in den Einstellungen. */
    var status by mutableStateOf("")
        private set

    /** Neue Version, nach der gefragt wird. */
    var offer by mutableStateOf<Updater.Release?>(null)
        private set

    /** Fortschritt beim Laden in Prozent, -1 = lädt nicht. */
    var progress by mutableStateOf(-1)
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    private var checking = false

    fun start() {
        auto = prefs.getBoolean("auto", true)
        // Testläufe aus dem Quelltext haben keine echte Version
        if (appVersion == "?" || System.getProperty("klartv.noUpdate") != null) return
        scope.launch {
            delay(15_000)
            while (true) {
                if (auto) check(manual = false)
                delay(TimeUnit.HOURS.toMillis(24))
            }
        }
    }

    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) {
        if (checking) return
        checking = true
        status = t("Suche nach Updates …")
        try {
            val r = Updater.latest("Windows-Setup.exe")
            if (r != null && Updater.newer(r.version, appVersion)) {
                status = t("Klar TV %1 ist da", r.version)
                // „Später“ gilt bis zum nächsten Start; von Hand gesucht wird wieder gefragt
                if (manual || r.version != dismissed) offer = r
            } else {
                status = t("Klar TV ist aktuell.")
            }
        } catch (e: Exception) {
            status = t("Update-Suche fehlgeschlagen: %1", e.message ?: e.javaClass.simpleName)
        } finally {
            checking = false
        }
    }

    private var dismissed: String? = null

    fun later() {
        dismissed = offer?.version
        offer = null
        failure = null
    }

    /** Lädt und prüft das Setup und startet es. Bei Erfolg beendet sich die App danach über [quit]. */
    fun install(quit: () -> Unit) {
        val r = offer ?: return
        if (progress >= 0) return
        failure = null
        progress = 0
        scope.launch {
            try {
                val dir = File(AppContext.default.dataDir, "updates")
                dir.listFiles()?.forEach { it.delete() }
                val setup = Updater.download(r, dir) { done, total -> if (total > 0) progress = (done * 100 / total).toInt().coerceIn(0, 100) }
                // Ohne Rückfragen, aber mit Fortschrittsfenster; das Setup startet Klar TV danach wieder
                ProcessBuilder(setup.path, "/SILENT", "/SUPPRESSMSGBOXES", "/NORESTART", "/SP-", "/CLOSEAPPLICATIONS", "/RELAUNCH=1").start()
                withContext(Dispatchers.Main) { quit() }
            } catch (e: Exception) {
                failure = t("Update fehlgeschlagen: %1", e.message ?: e.javaClass.simpleName)
            } finally {
                progress = -1
            }
        }
    }
}
