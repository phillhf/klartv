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
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Http
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.Stream

/**
 * Ein Eintrag für den Player. [key] merkt sich die Position, [recentKey] landet in „Zuletzt gesehen“
 * und „Weiterschauen“ (bei Folgen die Serie, nicht die einzelne Folge).
 */
data class PlayItem(
    val key: String,
    val title: String,
    val url: String,
    val live: Boolean,
    val streamId: String? = null,
    val subtitle: String? = null,
    val recentKey: String = key,
    val image: String? = null,
)

fun Stream.toPlayItem(image: String? = logo) =
    PlayItem(key = key, title = name, url = url, live = kind == Kind.LIVE, streamId = id, image = image)

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

/** Übergabe an den Player. Listen mit tausenden Sendern passen nicht in einen Intent. */
object PlaybackQueue {
    var items: List<PlayItem> = emptyList()
    var startIndex: Int = 0
    var library: Library? = null
    var epg: (suspend (PlayItem) -> List<EpgEntry>)? = null
    var onPlay: ((PlayItem) -> Unit)? = null
}
