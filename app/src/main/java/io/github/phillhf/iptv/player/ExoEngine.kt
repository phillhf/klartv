package io.github.phillhf.iptv.player

import io.github.phillhf.iptv.t
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.Players
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.toMediaItem
import java.util.Locale

@OptIn(UnstableApi::class)
class ExoEngine(context: Context) : Engine {
    override val name = "ExoPlayer"
    override var listener: Engine.Listener? = null

    private val player: ExoPlayer = Players.create(context)
    private val playerView = PlayerView(context).apply {
        useController = false
        isFocusable = false
        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        setShutterBackgroundColor(Color.BLACK)
        subtitleView?.let { styleSubtitles(it) }
    }
    override val view: View get() = playerView

    private var item: PlayItem? = null
    private var subtitles: List<MediaItem.SubtitleConfiguration> = emptyList()
    private var reportedReady = false
    private var reportedSilent = false

    private val events = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            listener?.onBuffering(state == Player.STATE_BUFFERING)
            if (state == Player.STATE_READY && !reportedReady) {
                reportedReady = true
                listener?.onReady()
            }
            if (state == Player.STATE_ENDED) listener?.onEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            listener?.onPlaying(isPlaying)
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Ton ist da, aber das Gerät kann ihn nicht dekodieren (oft AC3 oder DTS): sonst bliebe es stumm
            val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            if (audio.isNotEmpty() && audio.none { it.isSupported } && !reportedSilent) {
                reportedSilent = true
                listener?.onError(t("Tonformat nicht unterstützt"), unsupported = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val code = error.errorCode
            if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            // 3xxx: Datei nicht lesbar, 4xxx: Decoder, 5xxx: Ton
            listener?.onError(error.errorCodeName, unsupported = code in 3000..5999)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0) listener?.onVideoSize(videoSize.width, videoSize.height)
            player.videoFormat?.frameRate?.takeIf { it > 0f }?.let { listener?.onFrameRate(it) }
        }
    }

    init {
        player.addListener(events)
        val params = player.trackSelectionParameters.buildUpon()
        if (AppSettings.audioLanguage.isNotEmpty()) params.setPreferredAudioLanguage(AppSettings.audioLanguage)
        if (AppSettings.subtitlesOn && AppSettings.subtitleLanguage.isNotEmpty()) {
            params.setPreferredTextLanguage(AppSettings.subtitleLanguage)
        }
        params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !AppSettings.subtitlesOn)
        player.trackSelectionParameters = params.build()
        playerView.player = player
    }

    override fun load(item: PlayItem, positionMs: Long) {
        if (this.item?.key != item.key) subtitles = emptyList()
        this.item = item
        reportedReady = false
        reportedSilent = false
        val media = item.toMediaItem().buildUpon().setSubtitleConfigurations(subtitles).build()
        if (positionMs > 0) player.setMediaItem(media, positionMs) else player.setMediaItem(media)
        player.prepare()
        player.playWhenReady = true
    }

    override fun play() {
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    override fun pause() = player.pause()

    override fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0))

    override val positionMs: Long get() = player.currentPosition

    override val durationMs: Long
        get() = if (player.isCurrentMediaItemDynamic) 0 else player.duration.takeIf { it != C.TIME_UNSET } ?: 0

    override val isPlaying: Boolean get() = player.isPlaying

    override val isActive: Boolean
        get() = player.playWhenReady && (player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_BUFFERING)

    private fun tracks(type: Int): List<Track> = player.currentTracks.groups.withIndex()
        .filter { (_, g) -> g.type == type }
        .flatMap { (gi, g) ->
            (0 until g.length).filter { g.isTrackSupported(it) }.map { ti ->
                Track("$gi:$ti", label(g.getTrackFormat(ti), type, ti), g.isTrackSelected(ti))
            }
        }

    override fun audioTracks() = tracks(C.TRACK_TYPE_AUDIO)

    override fun textTracks() = tracks(C.TRACK_TYPE_TEXT)

    private fun label(f: Format, type: Int, index: Int): String {
        val language = f.language?.takeIf { it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage }
        val channels = if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 2) "${f.channelCount - 1}.1" else null
        val codec = if (type == C.TRACK_TYPE_AUDIO) f.sampleMimeType?.substringAfter('/')?.uppercase() else null
        return listOfNotNull(f.label, language, codec, channels).distinct().joinToString(" · ").ifEmpty { "#${index + 1}" }
    }

    private fun select(id: String) {
        val (gi, ti) = id.split(':').map { it.toInt() }
        val group = player.currentTracks.groups.getOrNull(gi) ?: return
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(group.type, false)
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, ti))
            .build()
    }

    override fun selectAudio(id: String) = select(id)

    override fun selectText(id: String?) {
        if (id != null) {
            select(id)
        } else {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build()
        }
    }

    override fun addSubtitle(uri: Uri, label: String) {
        val current = item ?: return
        val mime = when (label.substringAfterLast('.').lowercase()) {
            "vtt" -> MimeTypes.TEXT_VTT
            "ass", "ssa" -> MimeTypes.TEXT_SSA
            "ttml", "xml" -> MimeTypes.APPLICATION_TTML
            else -> MimeTypes.APPLICATION_SUBRIP
        }
        subtitles = subtitles + MediaItem.SubtitleConfiguration.Builder(uri)
            .setMimeType(mime)
            .setLabel(label)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
            .build()
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        load(current, player.currentPosition)
    }

    override fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    override fun setScale(mode: ScaleMode) {
        playerView.resizeMode = when (mode) {
            ScaleMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            ScaleMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            ScaleMode.STRETCH -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }
    }

    override fun stats(): PlaybackStats {
        val counters = player.videoDecoderCounters
        counters?.ensureUpdated()
        val offset = if (player.isCurrentMediaItemLive) player.currentLiveOffset else C.TIME_UNSET
        val bitrate = player.videoFormat?.bitrate?.takeIf { it != Format.NO_VALUE } ?: -1
        return PlaybackStats(
            dropped = counters?.droppedBufferCount?.toLong() ?: -1,
            shown = counters?.renderedOutputBufferCount?.toLong() ?: -1,
            bufferedMs = player.totalBufferedDuration,
            liveOffsetMs = if (offset == C.TIME_UNSET) -1 else offset,
            bitrateKbps = if (bitrate > 0) bitrate / 1000 else -1,
        )
    }

    override fun release() {
        listener = null
        player.removeListener(events)
        playerView.player = null
        player.release()
    }

    private fun styleSubtitles(v: SubtitleView) {
        val color = runCatching { Color.parseColor(AppSettings.subtitleColor) }.getOrDefault(Color.WHITE)
        val background = Color.argb(AppSettings.subtitleBackground.coerceIn(0, 100) * 255 / 100, 0, 0, 0)
        v.setApplyEmbeddedStyles(false)
        v.setApplyEmbeddedFontSizes(false)
        v.setStyle(CaptionStyleCompat(color, background, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null))
        v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * AppSettings.subtitleSize / 100f)
    }
}
