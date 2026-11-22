package io.github.phillhf.iptv.transfer

import androidx.compose.runtime.mutableStateListOf
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.SecureFile
import org.json.JSONArray
import java.io.File
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.concurrent.thread

/**
 * Downloads und Aufnahmen unter Windows. Sie laufen, solange das Programm offen ist.
 * Alle Änderungen laufen über den Oberflächenfaden, damit die Liste stimmig bleibt.
 */
object Transfers {
    val list = mutableStateListOf<Transfer>()
    private var store: File? = null
    private val running: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val cancelled: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private val timer = Timer(1000) { tick() }

    fun init(context: AppContext) {
        val f = File(context.dataDir, "transfers.dat")
        if (store == f) return
        store = f
        list.clear()
        val saved = runCatching {
            val arr = JSONArray(SecureFile.read(f) ?: "[]")
            (0 until arr.length()).map { Transfer.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Was beim letzten Mal lief, wurde mit dem Programm beendet
        list.addAll(
            saved.map {
                when {
                    it.state == TransferState.RUNNING && it.type == TransferType.DOWNLOAD -> it.copy(state = TransferState.QUEUED)
                    it.active && it.type == TransferType.RECORD -> {
                        val size = File(it.file).length()
                        it.copy(state = if (size > 0) TransferState.DONE else TransferState.FAILED, bytes = size, total = size)
                    }
                    it.state == TransferState.SCHEDULED && it.endAt <= System.currentTimeMillis() -> it.copy(state = TransferState.FAILED, error = "verpasst")
                    else -> it
                }
            },
        )
        timer.start()
    }

    fun folder(): File = File(System.getProperty("klartv.videos") ?: (System.getProperty("user.home") + "\\Videos\\Klar TV")).apply { mkdirs() }

    fun find(key: String, type: TransferType): Transfer? = list.firstOrNull { it.key == key && it.type == type }

    /** Läuft gerade etwas, das beim Schließen abbräche? */
    val busy: Boolean get() = list.any { it.active }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        runCatching { SecureFile.write(store ?: return, arr.toString()) }
    }

    private fun update(id: String, persist: Boolean = true, change: (Transfer) -> Transfer) = SwingUtilities.invokeLater {
        val i = list.indexOfFirst { it.id == id }
        if (i >= 0) {
            list[i] = change(list[i])
            if (persist) save()
        }
    }

    private fun fileName(title: String, id: String, ext: String): String {
        val clean = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().trimEnd('.').take(70).ifEmpty { "Video" }
        return File(folder(), "$clean-${id.take(8)}.$ext").path
    }

    fun download(item: PlayItem) {
        val old = find(item.key, TransferType.DOWNLOAD)
        if (old != null && (old.active || old.state == TransferState.DONE)) return
        if (old != null) {
            retry(old)
            return
        }
        val id = UUID.randomUUID().toString()
        val ext = item.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "")
            .takeIf { it.length in 2..4 && it.all { c -> c.isLetterOrDigit() } } ?: "mp4"
        add(
            Transfer(
                id, TransferType.DOWNLOAD, item.key, item.title, item.subtitle, item.image, item.url,
                fileName(item.title, id, ext), TransferState.QUEUED, parentKey = item.recentKey,
            ),
        )
        next()
    }

    /** Nimmt ab [startAt] (0 = sofort) bis [endAt] auf. */
    fun record(key: String, title: String, subtitle: String?, image: String?, url: String, startAt: Long, endAt: Long): Boolean {
        val now = System.currentTimeMillis()
        if (endAt <= now) return false
        val id = UUID.randomUUID().toString()
        val later = startAt > now + 5_000
        add(
            Transfer(
                id, TransferType.RECORD, key, title, subtitle, image, url, fileName(title, id, "ts"),
                if (later) TransferState.SCHEDULED else TransferState.QUEUED,
                startAt = if (later) startAt else now,
                endAt = endAt,
            ),
        )
        next()
        return true
    }

    fun runningRecording(key: String): Transfer? =
        list.firstOrNull { it.key == key && it.type == TransferType.RECORD && it.active }

    /** Beendet eine laufende Aufnahme; das bisher Aufgenommene bleibt. */
    fun stop(t: Transfer) {
        cancelled += t.id
        if (t.state == TransferState.SCHEDULED) remove(t)
        if (t.state == TransferState.QUEUED) update(t.id) { it.copy(state = TransferState.FAILED) }
    }

    fun retry(t: Transfer) {
        update(t.id) { it.copy(state = TransferState.QUEUED, error = null) }
        SwingUtilities.invokeLater { next() }
    }

    fun remove(t: Transfer) {
        cancelled += t.id
        list.removeAll { it.id == t.id }
        save()
        // Die Datei wird erst gleich geschlossen; danach löschen
        Timer(1500) {
            File(t.file).delete()
            File(t.file + ".part").delete()
        }.apply { isRepeats = false }.start()
    }

    private fun add(t: Transfer) {
        list.add(0, t)
        save()
    }

    /** Vor dem Beenden: laufende Aufnahmen sauber schließen. */
    fun shutdown() {
        running.forEach { cancelled += it }
        val end = System.currentTimeMillis() + 3_000
        while (running.isNotEmpty() && System.currentTimeMillis() < end) Thread.sleep(50)
        SwingUtilities.invokeLater { save() }
    }

    fun canSchedule(): Boolean = true

    private fun tick() {
        val now = System.currentTimeMillis()
        // Eine halbe Minute früher, damit der Anfang nicht fehlt
        list.filter { it.state == TransferState.SCHEDULED && it.startAt - 30_000 <= now }.forEach { t ->
            val i = list.indexOfFirst { it.id == t.id }
            list[i] = if (t.endAt <= now) t.copy(state = TransferState.FAILED, error = "verpasst") else t.copy(state = TransferState.QUEUED)
            save()
        }
        next()
    }

    /** Startet, was dran ist: alle fälligen Aufnahmen, aber immer nur einen Download. */
    private fun next() {
        val queued = list.filter { it.state == TransferState.QUEUED && it.id !in running }
        queued.filter { it.type == TransferType.RECORD }.forEach { launch(it) }
        val loading = list.any { it.type == TransferType.DOWNLOAD && it.id in running }
        if (!loading) queued.lastOrNull { it.type == TransferType.DOWNLOAD }?.let { launch(it) }
    }

    private fun launch(item: Transfer) {
        running += item.id
        cancelled -= item.id
        update(item.id) { it.copy(state = TransferState.RUNNING, error = null) }
        thread(name = "transfer-${item.id.take(6)}", isDaemon = true) {
            var lastReport = 0L
            val loader = Loader({ item.id in cancelled }) { bytes, total ->
                val now = System.currentTimeMillis()
                if (now - lastReport > 700) {
                    lastReport = now
                    update(item.id, persist = false) { it.copy(bytes = bytes, total = total) }
                }
            }
            val file = File(item.file)
            val stopped = { item.type == TransferType.RECORD && file.length() > 0 }
            try {
                if (item.type == TransferType.DOWNLOAD) loader.download(item.url, file) else loader.record(item.url, file, item.endAt)
                val size = file.length()
                update(item.id) {
                    if (size > 0) it.copy(state = TransferState.DONE, bytes = size, total = size) else it.copy(state = TransferState.FAILED, error = "Es kam kein Bild an.")
                }
            } catch (e: Loader.Cancelled) {
                val size = file.length()
                update(item.id) {
                    if (stopped()) it.copy(state = TransferState.DONE, bytes = size, total = size) else it.copy(state = TransferState.FAILED, error = "Angehalten")
                }
            } catch (e: Exception) {
                val size = file.length()
                update(item.id) {
                    // Eine abgerissene Aufnahme ist immer noch etwas wert
                    if (stopped()) {
                        it.copy(state = TransferState.DONE, bytes = size, total = size, error = friendlyError(e))
                    } else {
                        it.copy(state = TransferState.FAILED, error = friendlyError(e))
                    }
                }
            } finally {
                // Erst nach der Zustandsänderung freigeben, sonst würde derselbe Auftrag doppelt starten
                SwingUtilities.invokeLater { running -= item.id }
            }
        }
    }
}
