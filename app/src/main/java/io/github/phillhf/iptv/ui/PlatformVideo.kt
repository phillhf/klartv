package io.github.phillhf.iptv.ui

import android.content.Context
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.Players
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.toMediaItem
import io.github.phillhf.iptv.toPlayItem
import kotlinx.coroutines.delay

// Kleine Videobilder auf Android: Live-Vorschau und „Mehrere Sender“.

/** Hält den Vorschau-Player. Vor dem Vollbild anhalten, damit nur eine Verbindung zum Anbieter offen ist. */
class LivePreview(private val context: Context) {
    var player: ExoPlayer? = null
        private set

    fun play(url: String) {
        val p = player ?: Players.create(context).also { player = it }
        p.setMediaItem(PlayItem(key = url, title = "", url = url, live = true).toMediaItem())
        p.prepare()
        p.playWhenReady = true
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        player?.release()
        player = null
    }
}

@Composable
internal fun rememberLivePreview(): LivePreview {
    val context = LocalContext.current
    val preview = remember { LivePreview(context) }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { preview.release() }
    return preview
}

/** Startet die Vorschau erst, wenn der Fokus kurz auf einem Sender bleibt. */
@OptIn(UnstableApi::class)
@Composable
internal fun PreviewVideo(preview: LivePreview, url: String, modifier: Modifier) {
    if (LocalInspectionMode.current) {
        // Screenshots: Standbild statt Video
        AsyncImage("demo://backdrop/live", null, modifier, contentScale = ContentScale.Crop)
        return
    }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { resumes++ }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    LaunchedEffect(url, resumes) {
        preview.stop()
        delay(900)
        preview.play(url)
        player = preview.player
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setEnableComposeSurfaceSyncWorkaround(true)
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            }
        },
        update = { it.player = player },
        modifier = modifier,
    )
}

@OptIn(UnstableApi::class)
@Composable
internal fun TileVideo(s: Stream, sound: Boolean) {
    if (LocalInspectionMode.current) {
        AsyncImage("demo://backdrop/${s.id}", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        return
    }
    val context = LocalContext.current
    var starts by remember { mutableIntStateOf(0) }
    var failures by remember(s.key) { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { starts++ }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player?.release()
        player = null
    }
    DisposableEffect(s.key, starts) {
        // Ohne Audiofokus, sonst würden sich die vier Player gegenseitig anhalten
        val p = Players.create(context, handleAudioFocus = false)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failures++
            }
        })
        p.setMediaItem(s.toPlayItem().toMediaItem())
        p.prepare()
        p.playWhenReady = true
        player = p
        onDispose {
            p.release()
            if (player === p) player = null
        }
    }
    LaunchedEffect(failures) {
        if (failures in 1..5) {
            delay(1_500L * failures)
            player?.prepare()
        }
    }
    LaunchedEffect(player, sound) { player?.volume = if (sound) 1f else 0f }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
                // Android 14 zeigt Videoflächen in Compose sonst gelegentlich in falscher Größe
                setEnableComposeSurfaceSyncWorkaround(true)
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}
