package io.github.phillhf.iptv.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.AndroidViewModel
import io.github.phillhf.iptv.BuildConfig
import io.github.phillhf.iptv.LocalNetworkPermission
import io.github.phillhf.iptv.PlayerActivity
import io.github.phillhf.iptv.R
import io.github.phillhf.iptv.ReminderReceiver
import io.github.phillhf.iptv.TvHome
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.Reminder
import io.github.phillhf.iptv.data.Stream
import java.io.InputStream

// Was der gemeinsame Code vom System braucht. Das Gegenstück für Windows liegt in desktop/.

typealias AppContext = Context
typealias AppApplication = Application
typealias BaseViewModel = AndroidViewModel
typealias Prefs = SharedPreferences

val LocalAppContext get() = LocalContext

/** Mit der Maus bedient (Windows). */
const val mouseDriven = false

val appVersion: String get() = BuildConfig.VERSION_NAME

fun AppContext.openPrefs(name: String): Prefs = applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

fun AppContext.deletePrefs(name: String) {
    applicationContext.deleteSharedPreferences(name)
}

fun AppContext.openAsset(path: String): InputStream = assets.open(path)

fun AppContext.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

fun AppContext.openPlayer() = startActivity(Intent(this, PlayerActivity::class.java))

fun AppContext.scheduleReminder(r: Reminder) = ReminderReceiver.schedule(this, r)

fun AppContext.cancelReminder(r: Reminder) = ReminderReceiver.cancel(this, r)

/** USB-Sticks und SD-Karten: Android erlaubt dort ohne Nachfrage nur den Ordner der App. */
fun storageChoices(context: AppContext): List<Pair<String, String>> {
    val sm = context.getSystemService(android.os.storage.StorageManager::class.java)
    return context.getExternalFilesDirs(android.os.Environment.DIRECTORY_MOVIES).drop(1).filterNotNull().map { dir ->
        val label = runCatching { sm?.getStorageVolume(dir)?.getDescription(context) }.getOrNull() ?: dir.path
        "file:" + java.io.File(dir, "KlarTV").path to label
    }
}

const val canPickFolder = false

fun pickFolder(): String? = null

fun isTv(context: AppContext): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/** „Weiterschauen“ und Lieblingssender auf dem Startbildschirm des Fernsehers. [account] null leert die Reihen. */
fun publishHomeRows(context: AppContext, account: String?, continueList: List<ContinueEntry>, channels: List<Stream>) {
    if (account == null) TvHome.clear(context) else TvHome.update(context, account, continueList, channels)
}

/** Gibt es Startbildschirm-Reihen auf diesem Gerät? */
fun hasHomeRows(context: AppContext): Boolean = TvHome.available(context)

/** Kann die App beim Einschalten des Geräts von selbst starten? */
fun canAutostart(@Suppress("UNUSED_PARAMETER") context: AppContext): Boolean = true

/** Ab Android 10 darf eine App nur mit „Über anderen Apps einblenden“ von selbst nach vorn kommen. */
fun autostartBlocked(context: AppContext): Boolean =
    Build.VERSION.SDK_INT >= 29 && !Settings.canDrawOverlays(context)

/** Öffnet die Systemseite für diese Erlaubnis. Gibt false zurück, wenn das Gerät keine hat. */
fun openAutostartPermission(context: AppContext): Boolean {
    val app = Uri.parse("package:" + context.packageName)
    val tries = listOf(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, app),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app),
    )
    return tries.any { i -> runCatching { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
}

fun isKeyRepeat(event: KeyEvent): Boolean = event.nativeKeyEvent.repeatCount > 0

@Composable
fun appIcon(): Painter = painterResource(R.drawable.ic_launcher_foreground)

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

/** Scrollbalken nur für die Maus. */
@Composable
fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) = Unit

@Composable
fun ListScrollbar(state: LazyGridState, modifier: Modifier = Modifier) = Unit

@Composable
fun ServerPermission(server: String?) = LocalNetworkPermission(server)

/** Android aktualisiert über den Play Store oder eine neue APK; nur Windows sucht selbst nach Updates. */
object AppUpdates {
    const val supported = false
    val auto: Boolean get() = false
    val status: String get() = ""

    fun changeAuto(@Suppress("UNUSED_PARAMETER") on: Boolean) = Unit

    fun checkNow() = Unit
}
