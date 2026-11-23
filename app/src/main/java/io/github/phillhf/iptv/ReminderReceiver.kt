package io.github.phillhf.iptv

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Reminder
import io.github.phillhf.iptv.data.Reminders
import io.github.phillhf.iptv.ui.clockTime

/** Kurz vor einer Sendung: Benachrichtigung, die den Sender öffnet. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppSettings.init(context)
        I18n.init(context)
        Reminders.init(context)
        val r = Reminders.fire(intent.getStringExtra("id") ?: return) ?: return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, t("Erinnerungen"), NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context,
            r.id.hashCode(),
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.CHANNEL, r.channel)
                .putExtra(MainActivity.ACCOUNT, r.account),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(r.title)
            .setContentText(t("%1 um %2", r.channelName, clockTime(r.start)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { nm.notify(r.id.hashCode(), n) }
    }

    companion object {
        private const val CHANNEL = "reminders"

        private fun intent(context: Context, r: Reminder) = PendingIntent.getBroadcast(
            context,
            r.id.hashCode(),
            Intent(context, ReminderReceiver::class.java).setAction("io.github.phillhf.iptv.REMINDER").putExtra("id", r.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun schedule(context: Context, r: Reminder) {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            val at = r.start - Reminders.AHEAD
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(context, r))
            } catch (e: SecurityException) {
                // Ohne genaue Wecker eben ungefähr
                am.setWindow(AlarmManager.RTC_WAKEUP, at - 60_000, 60_000, intent(context, r))
            }
        }

        fun cancel(context: Context, r: Reminder) {
            context.getSystemService(AlarmManager::class.java)?.cancel(intent(context, r))
        }
    }
}
