package io.github.phillhf.iptv

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.phillhf.iptv.transfer.Transfers
import io.github.phillhf.iptv.ui.KlarTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Die App ist immer dunkel, also helle Symbole in Status- und Navigationsleiste
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        if (savedInstanceState == null) Transfers.resumePending()
        setContent {
            val vm: AppViewModel = viewModel()
            LaunchedEffect(Unit) { openFrom(intent, vm) }
            KlarTheme { App(vm) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent, androidx.lifecycle.ViewModelProvider(this)[AppViewModel::class.java])
    }

    /** Aus einer Erinnerung: den Sender öffnen. */
    private fun openFrom(intent: Intent?, vm: AppViewModel) {
        val channel = intent?.getStringExtra(CHANNEL) ?: return
        val account = intent.getStringExtra(ACCOUNT) ?: return
        intent.removeExtra(CHANNEL)
        vm.watchChannel(account, channel)
    }

    companion object {
        const val CHANNEL = "reminder_channel"
        const val ACCOUNT = "reminder_account"
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** Liegt der Server im eigenen Netz (z. B. Tvheadend), verlangt neues Android dafür eine Erlaubnis. */
@Composable
internal fun LocalNetworkPermission(server: String?) {
    if (Build.VERSION.SDK_INT < 37 || server.isNullOrBlank() || LocalInspectionMode.current) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(server) {
        val missing = ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
        if (missing && isLocalHost(server)) runCatching { launcher.launch(LOCAL_NETWORK) }
    }
}
