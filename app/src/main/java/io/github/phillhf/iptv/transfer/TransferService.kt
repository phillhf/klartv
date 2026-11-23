package io.github.phillhf.iptv.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.phillhf.iptv.MainActivity
import io.github.phillhf.iptv.R
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Reminders
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.t
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Lädt Downloads nacheinander und nimmt mehrere Sender gleichzeitig auf, auch wenn die App zu ist. */
class TransferService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val running: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        Transfers.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Downloads und Aufnahmen"), NotificationManager.IMPORTANCE_LOW))
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "klartv:transfer").apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        try {
            ServiceCompat.startForeground(this, 1, notification(), type)
        } catch (e: RuntimeException) {
            // Android verbietet den Start im Hintergrund oder das Tageslimit ist erreicht
            Transfers.list.filter { it.state == TransferState.QUEUED && it.type == TransferType.RECORD }.forEach { x ->
                Transfers.update(x.id) { it.copy(state = TransferState.FAILED, error = "Android") }
            }
            stopSelf()
            return START_NOT_STICKY
        }
        wake?.acquire(6 * 3_600_000L)
        next()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000)
        return START_NOT_STICKY
    }

    /** Ab Android 15: Das System erlaubt diese Dienstart nur sechs Stunden am Tag. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        running.forEach { cancelled += it }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        running.forEach { cancelled += it }
        wake?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private val tick = object : Runnable {
        override fun run() {
            next()
            if (running.isEmpty() && Transfers.list.none { it.state == TransferState.QUEUED }) {
                ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                getSystemService(NotificationManager::class.java).notify(1, notification())
                handler.postDelayed(this, 1000)
            }
        }
    }

    /** Startet, was dran ist: alle fälligen Aufnahmen, aber immer nur einen Download. */
    private fun next() {
        val queued = Transfers.list.filter { it.state == TransferState.QUEUED && it.id !in running }
        queued.filter { it.type == TransferType.RECORD }.forEach { launch(it) }
        val loading = Transfers.list.any { it.type == TransferType.DOWNLOAD && it.id in running }
        if (!loading) queued.lastOrNull { it.type == TransferType.DOWNLOAD }?.let { launch(it) }
    }

    private fun launch(item: Transfer) {
        running += item.id
        cancelled -= item.id
        Transfers.update(item.id) { it.copy(state = TransferState.RUNNING, error = null) }
        thread(name = "transfer-${item.id.take(6)}") {
            var lastReport = 0L
            val loader = Loader({ item.id in cancelled }) { bytes, total ->
                val now = System.currentTimeMillis()
                if (now - lastReport > 700) {
                    lastReport = now
                    Transfers.update(item.id, persist = false) { it.copy(bytes = bytes, total = total) }
                }
            }
            val (store, name) = Stores.resolve(item.file)
            val length = { runCatching { store.size(name) }.getOrDefault(0L) }
            try {
                if (item.type == TransferType.DOWNLOAD) loader.download(item.url, store, name) else loader.record(item.url, store, name, item.endAt)
                val size = length()
                Transfers.update(item.id) {
                    if (size > 0) {
                        it.copy(state = TransferState.DONE, bytes = size, total = size)
                    } else {
                        it.copy(state = TransferState.FAILED, error = "Es kam kein Bild an.")
                    }
                }
            } catch (e: Loader.Cancelled) {
                Transfers.update(item.id) { it.copy(state = TransferState.FAILED, error = "Angehalten") }
            } catch (e: Exception) {
                val size = length()
                Transfers.update(item.id) {
                    // Eine abgerissene Aufnahme ist immer noch etwas wert
                    if (item.type == TransferType.RECORD && size > 0) {
                        it.copy(state = TransferState.DONE, bytes = size, total = size, error = friendlyError(e))
                    } else {
                        it.copy(state = TransferState.FAILED, error = friendlyError(e))
                    }
                }
            } finally {
                // Erst nach der Zustandsänderung freigeben, sonst würde derselbe Auftrag doppelt starten
                handler.post { running -= item.id }
            }
        }
    }

    private fun notification(): Notification {
        val active = Transfers.list.filter { it.state == TransferState.RUNNING }
        val first = active.firstOrNull()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentTitle(if (first?.type == TransferType.RECORD) t("Aufnahme läuft") else t("Download läuft"))
            .setContentText(listOfNotNull(first?.title, if (active.size > 1) "+${active.size - 1}" else null).joinToString(" "))
        if (first != null && first.total > 0) b.setProgress(100, (first.progress * 100).toInt(), false)
        return b.build()
    }

    companion object {
        private const val CHANNEL = "transfers"
        private val cancelled: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

        fun cancel(id: String) {
            cancelled += id
        }
    }
}

/** Startet geplante Aufnahmen und stellt die Wecker nach einem Neustart neu. */
class RecordAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppSettings.init(context)
        Transfers.init(context)
        val id = intent.getStringExtra("id")
        if (id != null) {
            Transfers.due(id)
        } else {
            Transfers.rearm()
            Reminders.init(context)
            Reminders.rearm()
        }
    }
}
