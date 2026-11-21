package io.github.phillhf.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.phillhf.iptv.ui.AddAccountScreen
import io.github.phillhf.iptv.ui.HomeScreen
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.MovieScreen
import io.github.phillhf.iptv.ui.SeriesScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
                    is Screen.Movie -> MovieScreen(vm, top.stream)
                    is Screen.Series -> SeriesScreen(vm, top.stream)
                }
            }
        }
    }
}
