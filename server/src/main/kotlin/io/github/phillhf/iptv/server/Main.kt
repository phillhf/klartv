package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.I18n
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.SecureFile
import io.github.phillhf.iptv.platform.appVersion
import java.io.File
import java.util.Properties
import java.util.logging.FileHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import kotlin.system.exitProcess

/** Einstellungen, die vor dem Start gebraucht werden. Nichts Geheimes, deshalb als einfache Textdatei. */
class Config(private val file: File) {
    private val props = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }

    var port: Int
        get() = props.getProperty("port")?.toIntOrNull() ?: DEFAULT_PORT
        set(value) {
            props.setProperty("port", value.toString())
            file.outputStream().use { props.store(it, "Klar TV Server") }
        }

    companion object {
        const val DEFAULT_PORT = 8095
    }
}

/** Datenordner: beim Dienst unter ProgramData, für Tests frei wählbar. */
fun dataDir(): File = File(
    System.getProperty("klartv.data")
        ?: ((System.getenv("ProgramData") ?: System.getProperty("user.home")) + File.separator + "Klar TV Server"),
)

/** FFmpeg liegt im Programmordner unter tools. */
fun toolsDir(): File = System.getProperty("klartv.tools")?.let(::File)
    ?: System.getProperty("compose.application.resources.dir")?.let { File(it, "tools") }
    ?: File("tools")

fun main(args: Array<String>) {
    val dir = dataDir()
    dir.mkdirs()
    // Java braucht für interne Verbindungen einen kurzen Pfad; ein langer TEMP-Ordner lässt den Webserver scheitern
    if (System.getProperty("jdk.net.unixdomain.tmpdir") == null) {
        System.setProperty("jdk.net.unixdomain.tmpdir", File(dir, "tmp").apply { mkdirs() }.path)
    }
    setUpLog(File(dir, "logs"))
    val log = Logger.getLogger("Server")
    SecureFile.machine = true
    // Der Sync-Code vergleicht Ordner, der Dienst läuft ohne Fenster
    System.setProperty("java.awt.headless", "true")

    val context = AppContext(dir)
    AppSettings.init(context)
    I18n.init(context)
    val config = Config(File(dir, "server.properties"))
    fun arg(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
    val port = arg("--port")?.toIntOrNull() ?: config.port
    // Standard: im ganzen Heimnetz erreichbar; --bind 127.0.0.1 nur auf diesem Rechner
    val bind = arg("--bind") ?: "0.0.0.0"

    val core = Core(context)
    val media = Media(toolsDir())
    val web = Web(core, Auth(context), media, Images(File(dir, "bilder"), allowLocal = System.getProperty("klartv.localImages") == "true"), config)
    try {
        web.start(port, bind)
    } catch (e: Exception) {
        log.log(Level.SEVERE, "Port $port ist belegt oder gesperrt", e)
        exitProcess(2)
    }
    log.info("Klar TV Server $appVersion läuft auf Port $port, Daten in $dir, FFmpeg ${if (media.available) "da" else "fehlt"}")
    println("Klar TV Server $appVersion: http://localhost:$port")
    Runtime.getRuntime().addShutdownHook(Thread { web.stop() })
}

private fun setUpLog(dir: File) {
    dir.mkdirs()
    System.setProperty("java.util.logging.SimpleFormatter.format", "%1\$tF %1\$tT %4\$s %3\$s: %5\$s%6\$s%n")
    val handler = FileHandler(File(dir, "server-%g.log").path, 5_000_000, 3, true)
    handler.formatter = SimpleFormatter()
    handler.encoding = "UTF-8"
    Logger.getLogger("").addHandler(handler)
}
