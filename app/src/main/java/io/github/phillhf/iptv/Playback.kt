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
import androidx.media3.exoplayer.ExoPlayer
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

/** Gleich eingestellter Player für Vollbild und Live-Vorschau. */
object Players {
    @OptIn(UnstableApi::class)
    fun create(context: Context, handleAudioFocus: Boolean = true): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        // Viele IPTV-Streams beginnen nicht mit einem Schlüsselbild; ohne diese Schalter bleibt das Bild schwarz
        val extractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
        )
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http), extractors))
            .setAudioAttributes(AudioAttributes.DEFAULT, handleAudioFocus)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }
}
