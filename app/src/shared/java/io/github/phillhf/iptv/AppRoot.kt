package io.github.phillhf.iptv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.platform.BackHandler
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.platform.ServerPermission
import io.github.phillhf.iptv.ui.AddAccountScreen
import io.github.phillhf.iptv.ui.HomeScreen
import io.github.phillhf.iptv.ui.MovieScreen
import io.github.phillhf.iptv.ui.MultiViewScreen
import io.github.phillhf.iptv.ui.PinRequest
import io.github.phillhf.iptv.ui.ReminderPopup
import io.github.phillhf.iptv.ui.SeriesScreen
import io.github.phillhf.iptv.ui.SyncScreen

@Composable
internal fun App(vm: AppViewModel) {
    // Richtung nach der App-Sprache, nicht nach der Systemsprache
    CompositionLocalProvider(LocalLayoutDirection provides if (I18n.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) { AppContent(vm) }
}

@Composable
private fun AppContent(vm: AppViewModel) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            BackHandler(enabled = vm.syncOpen) { vm.closeSync() }
            if (vm.syncOpen) {
                SyncScreen(vm)
            } else if (vm.active == null) {
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
            ReminderPopup(vm)
            val context = LocalAppContext.current
            val live = vm.readyCatalog(Kind.LIVE)
            LaunchedEffect(vm.pendingChannel, live) { if (live != null) vm.playPending(context) }
        }
        ServerPermission(vm.active?.let { it.server.ifBlank { it.m3uUrl } })
    }
}
