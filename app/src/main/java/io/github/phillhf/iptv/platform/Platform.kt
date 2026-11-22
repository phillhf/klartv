package io.github.phillhf.iptv.platform

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.AndroidViewModel
import io.github.phillhf.iptv.BuildConfig
import io.github.phillhf.iptv.LocalNetworkPermission
import io.github.phillhf.iptv.PlayerActivity
import io.github.phillhf.iptv.R
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

fun isTv(context: AppContext): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

fun isKeyRepeat(event: KeyEvent): Boolean = event.nativeKeyEvent.repeatCount > 0

@Composable
fun appIcon(): Painter = painterResource(R.drawable.ic_launcher_foreground)

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) = androidx.activity.compose.BackHandler(enabled, onBack)

@Composable
fun ServerPermission(server: String?) = LocalNetworkPermission(server)
