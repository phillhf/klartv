package io.github.phillhf.iptv

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.phillhf.iptv.transfer.Transfers
import io.github.phillhf.iptv.ui.AddAccountScreen
import io.github.phillhf.iptv.ui.HomeScreen
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.MovieScreen
import io.github.phillhf.iptv.ui.MultiViewScreen
import io.github.phillhf.iptv.ui.PinRequest
import io.github.phillhf.iptv.ui.SeriesScreen
import java.net.InetAddress
import java.net.URI

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Die App ist immer dunkel, also helle Symbole in Status- und Navigationsleiste
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) Transfers.resumePending()
        setContent {
            KlarTheme { App() }
        }
    }
}

@Composable
internal fun App(vm: AppViewModel = viewModel()) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (vm.active == null) {
                AddAccountScreen(vm, onCancel = null)
            } else {
                BackHandler(enabled = vm.stack.size > 1) { vm.back() }
                when (val top = vm.stack.last()) {
                    Screen.Home -> HomeScreen(vm)
                    Screen.AddAccount -> AddAccountScreen(vm, onCancel = { vm.back() })
                    Screen.MultiView -> MultiViewScreen(vm)
                    is Screen.Movie -> MovieScreen(vm, top.stream)
                    is Screen.Series -> SeriesScreen(vm, top.stream)
                }
            }
            PinRequest(vm)
        }
        LocalNetworkPermission(vm.active?.let { it.server.ifBlank { it.m3uUrl } })
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Liegt der Server im eigenen Netz (z. B. Tvheadend), verlangt neues Android dafür eine Erlaubnis. */
@Composable
private fun LocalNetworkPermission(server: String?) {
    if (Build.VERSION.SDK_INT < 37 || server.isNullOrBlank() || LocalInspectionMode.current) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(server) {
        val missing = ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (missing && isLocalHost(server)) runCatching { launcher.launch(LOCAL_NETWORK) }
    }
}

/** Nur nach der Schreibweise, ohne Namensauflösung. */
fun isLocalHost(address: String): Boolean {
    val host = runCatching { URI(address.trim()).host }.getOrNull()?.lowercase() ?: return false
    if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".fritz.box") || !host.contains('.')) return true
    if (!Regex("[0-9.]+").matches(host)) return false
    return runCatching { InetAddress.getByName(host).let { it.isSiteLocalAddress || it.isLinkLocalAddress } }.getOrDefault(false)
}
