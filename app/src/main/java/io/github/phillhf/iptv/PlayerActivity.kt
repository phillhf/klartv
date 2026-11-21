package io.github.phillhf.iptv

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.ui.PlayerView
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Http
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vollbild-Player. Die ganze Liste (Sender einer Kategorie, Folgen einer Staffel) liegt als Playlist im
 * Player: Weiter/Zurück schaltet um, bei Folgen geht es automatisch mit der nächsten weiter.
 */
@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var info: TextView
    private var items: List<PlayItem> = emptyList()
    private var currentIndex = 0
    private var retries = 0
    private var epgJob: Job? = null
    private val handler = Handler(Looper.getMainLooper())
    private val time = SimpleDateFormat("HH:mm", Locale.GERMANY)

    private val hideInfo = Runnable {
        if (!playerView.isControllerFullyVisible) info.visibility = View.GONE
    }

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
                    if (visibility == View.VISIBLE) showInfo(autoHide = false) else info.visibility = View.GONE
                },
            )
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setBackgroundColor(0xB3000000.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
            visibility = View.GONE
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(
            info,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START)
                .apply { setMargins(dp(24), dp(24), dp(24), dp(24)) },
        )
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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        // Viele IPTV-Streams beginnen nicht mit einem Schlüsselbild; ohne diese Schalter bleibt das Bild schwarz
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        val p = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http), extractors))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
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

    private fun PlayItem.toMediaItem(): MediaItem {
        val builder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(key)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        if (url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    private fun current(): PlayItem? = items.getOrNull(player?.currentMediaItemIndex ?: currentIndex)

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
            current()?.let {
                PlaybackQueue.onPlay?.invoke(it)
                showInfo(autoHide = true)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY) retries = 0
            if (playbackState == Player.STATE_ENDED) current()?.let { PlaybackQueue.library?.clearPosition(it.key) }
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
                    "Wiedergabe nicht möglich (${error.errorCodeName}).\n" +
                        "Anderen Eintrag wählen oder Play drücken, um es erneut zu versuchen.",
                    autoHide = false,
                )
            }
        }
    }

    private fun showMessage(text: String, autoHide: Boolean) {
        info.text = text
        info.visibility = View.VISIBLE
        handler.removeCallbacks(hideInfo)
        if (autoHide) handler.postDelayed(hideInfo, 5_000)
    }

    private fun showInfo(autoHide: Boolean) {
        val item = current() ?: return
        showMessage(listOfNotNull(item.title, item.subtitle).joinToString("\n"), autoHide)
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
            if (current()?.key != item.key || info.visibility != View.VISIBLE) return@launch
            val now = System.currentTimeMillis()
            val text = StringBuilder(item.title)
            val index = list.indexOfFirst { now >= it.start && now < it.end }
            if (index >= 0) {
                text.append("\nJetzt: ").append(format(list[index]))
                list.getOrNull(index + 1)?.let { text.append("\nDanach: ").append(format(it)) }
            } else {
                list.firstOrNull { it.start > now }?.let { text.append("\nDemnächst: ").append(format(it)) }
            }
            info.text = text
        }
    }

    private fun format(e: EpgEntry) = "${time.format(Date(e.start))}–${time.format(Date(e.end))}  ${e.title}"

    /** Fernbedienung: Bei Live-TV schalten Hoch/Runter und die Kanaltasten um, solange die Leiste zu ist. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val p = player
        if (p != null && event.action == KeyEvent.ACTION_DOWN && current()?.live == true && !playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP -> {
                    if (p.hasNextMediaItem()) p.seekToNextMediaItem()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (p.hasPreviousMediaItem()) p.seekToPreviousMediaItem()
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
            duration > 0 && position > duration - 60_000 -> lib.clearPosition(item.key)
            position > 5_000 -> lib.savePosition(item.key, position)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
