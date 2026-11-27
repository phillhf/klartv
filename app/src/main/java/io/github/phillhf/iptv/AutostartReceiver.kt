package io.github.phillhf.iptv

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.platform.autostartBlocked

/** Startet Klar TV nach dem Einschalten des Geräts, wenn das in den Einstellungen gewünscht ist. */
class AutostartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in BOOT_ACTIONS) return
        AppSettings.init(context)
        val mode = AppSettings.autostart
        if (mode.isEmpty()) return
        // Ohne die Erlaubnis lässt Android die App nicht von selbst nach vorn
        if (autostartBlocked(context)) return
        val start = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (mode == "last") {
            val (account, key) = AppSettings.lastChannel.split('\n').takeIf { it.size == 2 } ?: listOf("", "")
            if (account.isNotEmpty() && key.isNotEmpty()) start.putExtra(MainActivity.ACCOUNT, account).putExtra(MainActivity.CHANNEL, key)
        }
        runCatching { context.startActivity(start) }
    }

    companion object {
        private val BOOT_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            // Manche TV-Boxen melden einen Schnellstart nur so
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
        )
    }
}
