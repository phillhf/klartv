package io.github.phillhf.iptv.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.common.images.WebImage
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.t

private const val RECEIVER = CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder().setReceiverApplicationId(RECEIVER).setStopReceiverApplicationWhenEndingSession(true).build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Geräte fürs Übertragen suchen und dort abspielen. Ohne Google-Dienste (z. B. Fire TV) bleibt die Liste leer. */
object Caster {
    data class Route(val id: String, val name: String)

    val routes = mutableStateListOf<Route>()
    var connectedTo by mutableStateOf<String?>(null)
        private set
    var problem by mutableStateOf<String?>(null)
        private set

    private var router: MediaRouter? = null
    private val main = Handler(Looper.getMainLooper())

    private val selector = MediaRouteSelector.Builder()
        .addControlCategory(CastMediaControlIntent.categoryForCast(RECEIVER))
        .build()

    private val callback = object : MediaRouter.Callback() {
        override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private fun castContext(context: Context): CastContext? = try {
        @Suppress("DEPRECATION")
        CastContext.getSharedInstance(context.applicationContext)
    } catch (e: Throwable) {
        // Keine Google-Play-Dienste: CastContext wirft je nach Gerät verschiedene Fehler
        null
    }

    fun startDiscovery(context: Context) {
        val cast = castContext(context)
        if (cast == null) {
            problem = t("Auf diesem Gerät fehlen die Google-Dienste fürs Übertragen.")
            return
        }
        problem = null
        connectedTo = cast.sessionManager.currentCastSession?.castDevice?.friendlyName
        router = MediaRouter.getInstance(context.applicationContext).also {
            it.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        }
        refresh()
    }

    fun stopDiscovery() {
        router?.removeCallback(callback)
        router = null
    }

    private fun refresh() {
        val found = router?.routes.orEmpty().filter { !it.isDefault && it.matchesSelector(selector) }.map { Route(it.id, it.name) }
        routes.clear()
        routes.addAll(found)
    }

    fun disconnect(context: Context) {
        castContext(context)?.sessionManager?.endCurrentSession(true)
        connectedTo = null
    }

    fun play(context: Context, routeId: String, item: PlayItem, position: Long, done: (String?) -> Unit) {
        if (item.local) {
            done(t("Dateien auf diesem Gerät lassen sich nicht übertragen."))
            return
        }
        val cast = castContext(context) ?: return done(t("Auf diesem Gerät fehlen die Google-Dienste fürs Übertragen."))
        val sessions = cast.sessionManager
        val current = sessions.currentCastSession
        val r = MediaRouter.getInstance(context.applicationContext)
        val route = r.routes.firstOrNull { it.id == routeId }
        if (current != null && current.isConnected && r.selectedRoute.id == routeId) {
            load(current, item, position, done)
            return
        }
        if (route == null) {
            done(t("Gerät nicht mehr gefunden."))
            return
        }
        var finished = false
        lateinit var listener: SessionManagerListener<CastSession>
        fun finish(error: String?) {
            if (finished) return
            finished = true
            sessions.removeSessionManagerListener(listener, CastSession::class.java)
            if (error != null) done(error)
        }
        listener = object : SessionManagerListener<CastSession> {
            override fun onSessionStarted(session: CastSession, id: String) {
                finish(null)
                connectedTo = session.castDevice?.friendlyName
                load(session, item, position, done)
            }

            override fun onSessionStartFailed(session: CastSession, code: Int) = finish(t("Verbindung zum Gerät fehlgeschlagen."))
            override fun onSessionStarting(session: CastSession) = Unit
            override fun onSessionEnding(session: CastSession) = Unit
            override fun onSessionEnded(session: CastSession, code: Int) = Unit
            override fun onSessionResuming(session: CastSession, id: String) = Unit
            override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = Unit
            override fun onSessionResumeFailed(session: CastSession, code: Int) = Unit
            override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
        }
        sessions.addSessionManagerListener(listener, CastSession::class.java)
        main.postDelayed({ finish(t("Verbindung zum Gerät fehlgeschlagen.")) }, 25_000)
        r.selectRoute(route)
    }

    private fun load(session: CastSession, item: PlayItem, position: Long, done: (String?) -> Unit) {
        val client = session.remoteMediaClient ?: return done(t("Verbindung zum Gerät fehlgeschlagen."))
        val url = castUrl(item)
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, item.title)
            item.subtitle?.let { putString(MediaMetadata.KEY_SUBTITLE, it) }
            item.image?.takeIf { it.startsWith("http") }?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val media = MediaInfo.Builder(url)
            .setStreamType(if (item.live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(contentType(url))
            .setMetadata(meta)
            .build()
        val request = MediaLoadRequestData.Builder().setMediaInfo(media).setAutoplay(true)
        if (!item.live && position > 0) request.setCurrentTime(position)
        client.load(request.build()).setResultCallback { result ->
            done(if (result.status.isSuccess) null else t("Das Gerät kann dieses Format nicht abspielen."))
        }
    }

    /** Chromecast spielt keinen rohen MPEG-TS-Strom; Xtream bietet denselben Sender auch als HLS an. */
    fun castUrl(item: PlayItem): String =
        if (item.live && item.url.contains("/live/") && item.url.endsWith(".ts")) item.url.removeSuffix(".ts") + ".m3u8" else item.url

    fun contentType(url: String): String = when (url.substringBefore('?').substringAfterLast('.').lowercase()) {
        "m3u8" -> "application/x-mpegurl"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mp3" -> "audio/mpeg"
        else -> "video/mp4"
    }
}
