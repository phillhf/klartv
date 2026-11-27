package io.github.phillhf.iptv

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import io.github.phillhf.iptv.data.Http

fun PlayItem.toMediaItem(): MediaItem {
    val builder = MediaItem.Builder()
        .setUri(url)
        .setMediaId(key)
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
    if (url.contains(".m3u8", ignoreCase = true)) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
    return builder.build()
}

/**
 * Tonversatz für ExoPlayer, das selbst keinen kennt. Das Bild richtet sich nach der Uhr des Tons:
 * Meldet der Ton eine spätere Zeit, kommt das Bild früher, der Ton also relativ später.
 */
@OptIn(UnstableApi::class)
class AudioDelay {
    /** Positiv = Ton später. */
    @Volatile var ms: Int = 0

    fun wrap(sink: AudioSink): AudioSink = object : ForwardingAudioSink(sink) {
        override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
            val p = super.getCurrentPositionUs(sourceEnded)
            return if (p == AudioSink.CURRENT_POSITION_NOT_SET) p else (p + ms * 1000L).coerceAtLeast(0)
        }
    }
}

/** Gleich eingestellter Player für Vollbild und Live-Vorschau. */
object Players {
    @OptIn(UnstableApi::class)
    fun create(context: Context, handleAudioFocus: Boolean = true, delay: AudioDelay? = null): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        // Viele IPTV-Streams beginnen nicht mit einem Schlüsselbild; ohne diese Schalter bleibt das Bild schwarz
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        val renderers = if (delay == null) {
            DefaultRenderersFactory(context)
        } else {
            object : DefaultRenderersFactory(context) {
                override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
                    super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)?.let { delay.wrap(it) }
            }
        }
        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors))
            .setAudioAttributes(AudioAttributes.DEFAULT, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}
