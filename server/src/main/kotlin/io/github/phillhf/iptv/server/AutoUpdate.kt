package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.appVersion
import io.github.phillhf.iptv.platform.openPrefs
import io.github.phillhf.iptv.update.Updater
import java.io.File
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Hält den Server aktuell: schaut alle sechs Stunden bei GitHub nach, lädt eine neue Version und prüft ihre Signatur.
 * Installiert wird nachts zwischen 3 und 5 Uhr, wenn gerade niemand schaut, oder sofort auf Knopfdruck.
 * Das Setup läuft über die Aufgabenplanung als SYSTEM, weil es den Dienst und damit diesen Prozess beendet.
 */
class AutoUpdate(context: AppContext, private val dir: File, private val busy: () -> Boolean) {
    private val log = Logger.getLogger("Update")
    private val prefs = context.openPrefs("update")

    var auto: Boolean
        get() = prefs.getBoolean("auto", true)
        set(value) = prefs.edit().putBoolean("auto", value).apply()

    @Volatile var latest: String = ""
        private set
    @Volatile var state: String = "" // "", checking, downloading, ready, installing, error
        private set
    @Volatile var error: String = ""
        private set
    @Volatile var lastCheck: Long = prefs.getLong("lastCheck", 0)
        private set
    @Volatile private var ready: File? = null

    fun start() {
        cleanUp()
        Thread({
            Thread.sleep(TimeUnit.MINUTES.toMillis(2))
            while (true) {
                runCatching { tick() }.onFailure { log.log(Level.WARNING, "Update-Prüfung", it) }
                Thread.sleep(TimeUnit.MINUTES.toMillis(10))
            }
        }, "update").apply { isDaemon = true }.start()
    }

    private fun tick() {
        if (!auto) return
        if (System.currentTimeMillis() - lastCheck > TimeUnit.HOURS.toMillis(6) || (state == "error" && System.currentTimeMillis() - lastCheck > TimeUnit.HOURS.toMillis(1))) check()
        val now = LocalTime.now()
        if (ready != null && now.hour in 3..4 && !busy()) install()
    }

    /** Sucht nach einer neuen Version und lädt sie gleich. */
    @Synchronized
    fun check() {
        state = "checking"
        error = ""
        try {
            val r = Updater.latest("Windows-Server-Setup.exe")
            lastCheck = System.currentTimeMillis()
            prefs.edit().putLong("lastCheck", lastCheck).apply()
            if (r == null || !Updater.newer(r.version, appVersion)) {
                latest = r?.version ?: latest
                state = ""
                return
            }
            latest = r.version
            if (ready?.name != r.file) {
                state = "downloading"
                ready = Updater.download(r, File(dir, "updates"))
                log.info("Update ${r.version} geladen und geprüft")
            }
            state = "ready"
        } catch (e: Exception) {
            lastCheck = System.currentTimeMillis()
            state = "error"
            error = e.message ?: e.javaClass.simpleName
            log.log(Level.WARNING, "Update fehlgeschlagen", e)
        }
    }

    /** Startet das geprüfte Setup. Der Dienst hält dabei an und kommt mit der neuen Version wieder. */
    @Synchronized
    fun install(): Boolean {
        val setup = ready?.takeIf { it.exists() } ?: return false
        state = "installing"
        log.info("Installiere Update ${setup.name}")
        if (!schedule(setupCommand(setup, File(dir, "logs/update.log")), File(dir, "logs/schtasks.log"))) {
            state = "error"
            error = "Die Aufgabenplanung hat das Setup nicht gestartet."
            return false
        }
        return true
    }

    /** Nach dem Neustart: Aufgabe und alte Setups wegräumen. */
    private fun cleanUp() {
        Thread {
            runCatching { ProcessBuilder("schtasks.exe", "/Delete", "/TN", TASK, "/F").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor() }
            File(dir, "updates").listFiles()?.forEach { it.delete() }
        }.apply { isDaemon = true }.start()
    }

    companion object {
        const val TASK = "Klar TV Server Update"

        /**
         * Befehlszeile für die Aufgabe. Die inneren Anführungszeichen sind mit \ geschützt, weil Java das ganze
         * Argument noch einmal in Anführungszeichen setzt; schtasks macht daraus wieder "Pfad" /Schalter.
         */
        fun setupCommand(setup: File, logFile: File): String =
            "\\\"${setup.path}\\\" /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /SP- /LOG=\\\"${logFile.path}\\\""

        /** Legt eine einmalige Aufgabe als SYSTEM an und startet sie sofort. */
        fun schedule(command: String, output: File, task: String = TASK): Boolean {
            output.parentFile?.mkdirs()
            fun run(vararg args: String): Int = runCatching {
                ProcessBuilder(listOf("schtasks.exe") + args).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(output)).start().waitFor()
            }.getOrDefault(-1)
            if (run("/Create", "/F", "/TN", task, "/TR", command, "/SC", "ONCE", "/ST", "00:00", "/RU", "SYSTEM", "/RL", "HIGHEST") != 0) return false
            return run("/Run", "/TN", task) == 0
        }
    }
}
