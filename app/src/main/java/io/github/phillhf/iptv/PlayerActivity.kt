package io.github.phillhf.iptv

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.ui.KlarTheme
import io.github.phillhf.iptv.ui.PlayerOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Vollbild-Player. Die ganze Liste (Sender einer Kategorie, Folgen einer Staffel) liegt als Playlist im
 * Player: Weiter/Zurück schaltet um, bei Folgen geht es automatisch mit der nächsten weiter.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var items: List<PlayItem> = emptyList()
    private var currentIndex = 0
    private var retries = 0
    private var epgJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    // Zustand der Senderinfo unten im Bild
    private var overlayItem by mutableStateOf<PlayItem?>(null)
    private var overlayEpg by mutableStateOf<List<EpgEntry>>(emptyList())
    private var overlayVisible by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)

    private val hideOverlay = Runnable { overlayVisible = false }
    private val hideMessage = Runnable { message = null }

    private val saveTick = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, 10_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Sehr lange Senderlisten auf ein Fenster um den gewählten Sender begrenzen
        val all = PlaybackQueue.items
        if (all.isEmpty()) {
            finish()
            return
        }
        val start = PlaybackQueue.startIndex.coerceIn(all.indices)
        val from = (start - 750).coerceAtLeast(0)
        val to = (from + 1500).coerceAtMost(all.size)
        items = all.subList(from, to)
        currentIndex = savedInstanceState?.getInt("index") ?: (start - from)

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            keepScreenOn = true
            setShowSubtitleButton(true)
            setControllerVisibilityListener(
                PlayerView.ControllerVisibilityListener { visibility ->
                    // Leiste des Players und Senderinfo nicht übereinander zeigen
                    if (visibility == View.VISIBLE) overlayVisible = false
                },
            )
        }
        val overlay = ComposeView(this).apply {
            setContent {
                KlarTheme {
                    PlayerOverlay(
                        item = overlayItem,
                        epg = overlayEpg,
                        visible = overlayVisible,
                        message = message,
                    )
                }
            }
        }
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        root.addView(playerView, FrameLayout.LayoutParams(match, match))
        root.addView(overlay, FrameLayout.LayoutParams(match, match))
        setContentView(root)

        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (items.isNotEmpty()) initPlayer()
    }

    override fun onStop() {
        savePosition()
        releasePlayer()
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", player?.currentMediaItemIndex ?: currentIndex)
    }

    private fun initPlayer() {
        if (player != null) return
        val p = Players.create(this)
        p.addListener(listener)
        val index = currentIndex.coerceIn(items.indices)
        val item = items[index]
        val position = if (item.live) C.TIME_UNSET else PlaybackQueue.library?.position(item.key)?.takeIf { it > 0 } ?: C.TIME_UNSET
        p.setMediaItems(items.map { it.toMediaItem() }, index, position)
        p.prepare()
        p.playWhenReady = true
        playerView.player = p
        player = p
        handler.postDelayed(saveTick, 10_000)
    }

    private fun releasePlayer() {
        handler.removeCallbacksAndMessages(null)
        epgJob?.cancel()
        player?.let {
            currentIndex = it.currentMediaItemIndex
            it.removeListener(listener)
            it.release()
        }
        player = null
        if (::playerView.isInitialized) playerView.player = null
    }

    private fun current(): PlayItem? = items.getOrNull(player?.currentMediaItemIndex ?: currentIndex)

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            current()?.let {
                PlaybackQueue.onPlay?.invoke(it)
                showOverlay(it)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) {
                retries = 0
                message = null
            }
            if (playbackState == Player.STATE_ENDED) {
                current()?.let {
                    PlaybackQueue.library?.clearPosition(it.key)
                    PlaybackQueue.library?.removeContinue(it.key)
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val item = current()
            // Live-Streams brechen öfter kurz ab: einige Male selbst neu verbinden
            if (item?.live == true && retries < 5) {
                retries++
                showMessage("Verbindung unterbrochen – neuer Versuch ($retries von 5) …", autoHide = true)
                handler.postDelayed({
                    player?.let {
                        if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) it.seekToDefaultPosition()
                        it.prepare()
                    }
                }, 1500L * retries)
            } else {
                showMessage(
                    "Wiedergabe nicht möglich (${error.errorCodeName}). " +
                        "Anderen Eintrag wählen oder Play drücken, um es erneut zu versuchen.",
                    autoHide = false,
                )
            }
        }
    }

    private fun showMessage(text: String, autoHide: Boolean) {
        message = text
        handler.removeCallbacks(hideMessage)
        if (autoHide) handler.postDelayed(hideMessage, 6_000)
    }

    private fun showOverlay(item: PlayItem) {
        if (overlayItem?.key != item.key) overlayEpg = emptyList()
        overlayItem = item
        overlayVisible = true
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 5_000)
        if (item.live) loadEpg(item)
    }

    private fun loadEpg(item: PlayItem) {
        val provider = PlaybackQueue.epg ?: return
        epgJob?.cancel()
        epgJob = lifecycleScope.launch {
            val list = try {
                provider(item)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (overlayItem?.key == item.key) overlayEpg = list
        }
    }

    /** Fernbedienung: Bei Live-TV schalten Hoch/Runter und die Kanaltasten um, solange die Leiste zu ist. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        val item = current()
        if (p != null && item != null && event.action == KeyEvent.ACTION_DOWN && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> if (item.live) {
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> if (item.live) {
                    if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> {
                    showOverlay(item)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun savePosition() {
        val p = player ?: return
        val item = items.getOrNull(p.currentMediaItemIndex) ?: return
        currentIndex = p.currentMediaItemIndex
        if (item.live) return
        val lib = PlaybackQueue.library ?: return
        val duration = p.duration
        val position = p.currentPosition
        when {
            duration > 0 && position > duration - 60_000 -> {
                lib.clearPosition(item.key)
                lib.removeContinue(item.key)
            }
            position > 5_000 -> {
                lib.savePosition(item.key, position)
                lib.updateContinue(
                    ContinueEntry(
                        key = item.key,
                        parentKey = item.recentKey,
                        title = item.title,
                        subtitle = item.subtitle,
                        image = item.image,
                        url = item.url,
                        position = position,
                        duration = duration.coerceAtLeast(0),
                    ),
                )
            }
        }
    }
}
