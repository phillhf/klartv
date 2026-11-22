package io.github.phillhf.iptv.transfer

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.core.content.ContextCompat
import io.github.phillhf.iptv.PlayItem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

enum class TransferType { DOWNLOAD, RECORD }

enum class TransferState { SCHEDULED, QUEUED, RUNNING, DONE, FAILED }

data class Transfer(
    val id: String,
    val type: TransferType,
    /** Schlüssel des Eintrags beim Anbieter, dient auch zum Wiedererkennen. */
    val key: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val file: String,
    val state: TransferState,
    val bytes: Long = 0,
    val total: Long = 0,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val error: String? = null,
    /** Film, Serie oder Sender, zu dem der Eintrag gehört; entscheidet über die Kindersicherung. */
    val parentKey: String = key,
) {
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = state == TransferState.RUNNING || state == TransferState.QUEUED

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("key", key).put("title", title).put("subtitle", subtitle ?: "")
        .put("image", image ?: "").put("url", url).put("file", file).put("state", state.name)
        .put("bytes", bytes).put("total", total).put("startAt", startAt).put("endAt", endAt).put("error", error ?: "")
        .put("parent", parentKey)

    companion object {
        fun fromJson(o: JSONObject) = Transfer(
            id = o.getString("id"),
            type = TransferType.valueOf(o.getString("type")),
            key = o.getString("key"),
            title = o.getString("title"),
            subtitle = o.optString("subtitle").takeIf { it.isNotEmpty() },
            image = o.optString("image").takeIf { it.isNotEmpty() },
            url = o.getString("url"),
            file = o.getString("file"),
            state = TransferState.valueOf(o.getString("state")),
            bytes = o.optLong("bytes"),
            total = o.optLong("total"),
            startAt = o.optLong("startAt"),
            endAt = o.optLong("endAt"),
            error = o.optString("error").takeIf { it.isNotEmpty() },
            parentKey = o.optString("parent").ifEmpty { o.getString("key") },
        )
    }
}

/** Downloads und Aufnahmen. Alle Änderungen laufen über den Hauptthread, damit die Liste stimmig bleibt. */
object Transfers {
    val list = mutableStateListOf<Transfer>()
    private val main = Handler(Looper.getMainLooper())
    private var store: File? = null
    private lateinit var app: Context

    fun init(context: Context) {
        app = context.applicationContext
        val f = File(app.filesDir, "transfers.json")
        if (store == f) return
        store = f
        list.clear()
        val saved = runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { Transfer.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        // Was beim letzten Mal lief, wurde mit der App beendet
        list.addAll(
            saved.map {
                when {
                    it.state == TransferState.RUNNING && it.type == TransferType.DOWNLOAD -> it.copy(state = TransferState.QUEUED)
                    it.active && it.type == TransferType.RECORD -> it.copy(state = if (it.bytes > 0) TransferState.DONE else TransferState.FAILED)
                    else -> it
                }
            },
        )
    }

    fun folder(): File {
        val base = app.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: app.filesDir
        return File(base, "KlarTV").apply { mkdirs() }
    }

    fun find(key: String, type: TransferType): Transfer? = list.firstOrNull { it.key == key && it.type == type }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val f = store ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(arr.toString())
            tmp.renameTo(f)
        }
    }

    /** Vom Dienst aus aufrufbar. [persist] = false für reinen Fortschritt. */
    fun update(id: String, persist: Boolean = true, change: (Transfer) -> Transfer) {
        main.post {
            val i = list.indexOfFirst { it.id == id }
            if (i >= 0) {
                list[i] = change(list[i])
                if (persist) save()
            }
        }
    }

    private fun fileName(title: String, id: String, ext: String): String {
        val clean = title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").trim().take(70).ifEmpty { "Video" }
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
        val ext = item.url.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "").takeIf { it.length in 2..4 } ?: "mp4"
        add(
            Transfer(
                id, TransferType.DOWNLOAD, item.key, item.title, item.subtitle, item.image, item.url,
                fileName(item.title, id, ext), TransferState.QUEUED, parentKey = item.recentKey,
            ),
        )
        start()
    }

    /** Nimmt ab [startAt] (0 = sofort) bis [endAt] auf. */
    fun record(key: String, title: String, subtitle: String?, image: String?, url: String, startAt: Long, endAt: Long): Boolean {
        val now = System.currentTimeMillis()
        if (endAt <= now) return false
        val id = UUID.randomUUID().toString()
        val later = startAt > now + 5_000
        val t = Transfer(
            id, TransferType.RECORD, key, title, subtitle, image, url, fileName(title, id, "ts"),
            if (later) TransferState.SCHEDULED else TransferState.QUEUED,
            startAt = if (later) startAt else now,
            endAt = endAt,
        )
        add(t)
        if (later) schedule(t) else start()
        return true
    }

    fun runningRecording(key: String): Transfer? =
        list.firstOrNull { it.key == key && it.type == TransferType.RECORD && it.active }

    /** Beendet eine laufende Aufnahme; das bisher Aufgenommene bleibt. */
    fun stop(t: Transfer) {
        TransferService.cancel(t.id)
        if (t.state == TransferState.SCHEDULED) remove(t)
        if (t.state == TransferState.QUEUED) update(t.id) { it.copy(state = TransferState.FAILED) }
    }

    fun retry(t: Transfer) {
        update(t.id) { it.copy(state = TransferState.QUEUED, error = null) }
        main.post { start() }
    }

    fun remove(t: Transfer) {
        TransferService.cancel(t.id)
        alarms()?.cancel(alarmIntent(t.id))
        list.removeAll { it.id == t.id }
        save()
        // Der Dienst schließt die Datei erst gleich; danach löschen
        main.postDelayed({
            File(t.file).delete()
            File(t.file + ".part").delete()
        }, 1500)
    }

    private fun add(t: Transfer) {
        list.add(0, t)
        save()
    }

    private fun start() {
        runCatching { ContextCompat.startForegroundService(app, Intent(app, TransferService::class.java)) }
            .onFailure { e ->
                list.filter { it.state == TransferState.QUEUED && it.type == TransferType.RECORD }.forEach { t ->
                    update(t.id) { it.copy(state = TransferState.FAILED, error = e.javaClass.simpleName) }
                }
            }
    }

    // ---------- Geplante Aufnahmen ----------

    private fun alarms() = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    private fun alarmIntent(id: String): PendingIntent = PendingIntent.getBroadcast(
        app,
        id.hashCode(),
        Intent(app, RecordAlarmReceiver::class.java).setAction("io.github.phillhf.iptv.RECORD").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Ohne genaue Wecker darf Android die Aufnahme im Hintergrund nicht starten. */
    fun canSchedule(): Boolean = Build.VERSION.SDK_INT < 31 || alarms()?.canScheduleExactAlarms() == true

    private fun schedule(t: Transfer) {
        val am = alarms() ?: return
        // Eine halbe Minute früher, damit der Anfang nicht fehlt
        val at = t.startAt - 30_000
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(t.id))
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(t.id))
        }
    }

    /** Nach Neustart oder Update: Wecker neu stellen, Verpasstes kennzeichnen. */
    fun rearm() {
        val now = System.currentTimeMillis()
        list.filter { it.state == TransferState.SCHEDULED }.forEach { t ->
            if (t.endAt <= now) {
                update(t.id) { it.copy(state = TransferState.FAILED, error = "verpasst") }
            } else if (t.startAt > now) {
                schedule(t)
            } else {
                due(t.id)
            }
        }
    }

    fun due(id: String) {
        val t = list.firstOrNull { it.id == id && it.state == TransferState.SCHEDULED } ?: return
        update(t.id) { it.copy(state = TransferState.QUEUED) }
        main.post { start() }
    }

    /** Beim Öffnen der App: unterbrochene Downloads weiterführen. */
    fun resumePending() {
        rearm()
        if (list.any { it.state == TransferState.QUEUED }) start()
    }

    fun needsNotificationPermission(context: Context): Boolean = Build.VERSION.SDK_INT >= 33 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
}
