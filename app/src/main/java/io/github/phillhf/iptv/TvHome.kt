package io.github.phillhf.iptv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.platform.isTv

/**
 * „Weiterschauen“ und die Lieblingssender auf dem Startbildschirm von Android TV und Google TV.
 * Fernseher ohne diese Schnittstelle (etwa Fire TV) lassen es einfach weg.
 */
object TvHome {
    private const val CHANNEL_ID = "favoriten"
    const val CONTINUE = "home_continue"

    fun available(context: Context): Boolean = isTv(context)

    /** Gleicht beide Reihen mit dem Stand der App ab. Läuft im Hintergrund, Fehler bleiben folgenlos. */
    fun update(context: Context, account: String, continueList: List<ContinueEntry>, channels: List<Stream>) {
        if (!available(context)) return
        runCatching { updateWatchNext(context, account, continueList) }
        runCatching { updateChannel(context, account, channels) }
    }

    /** Konto weg oder alles gesperrt: Reihen leeren. */
    fun clear(context: Context) {
        if (!available(context)) return
        runCatching { context.contentResolver.delete(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null) }
        runCatching { channelId(context)?.let { context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(it), null, null) } }
    }

    private fun link(context: Context, account: String, extra: String, value: String): Uri {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(MainActivity.ACCOUNT, account)
            .putExtra(extra, value)
        return Uri.parse(intent.toUri(Intent.URI_INTENT_SCHEME))
    }

    // ---------- Weiterschauen ----------

    @SuppressLint("RestrictedApi")
    private fun updateWatchNext(context: Context, account: String, list: List<ContinueEntry>) {
        val resolver = context.contentResolver
        val helper = PreviewChannelHelper(context)
        // Nur die eigenen Einträge kommen zurück
        val existing = HashMap<String, WatchNextProgram>()
        resolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, null, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val p = WatchNextProgram.fromCursor(c)
                val key = p.internalProviderId
                if (key == null || existing.containsKey(key)) resolver.delete(TvContractCompat.buildWatchNextProgramUri(p.id), null, null)
                else existing[key] = p
            }
        }
        val now = System.currentTimeMillis()
        val wanted = list.filter { it.duration > 0 }.take(15)
        wanted.forEachIndexed { i, e ->
            val old = existing.remove(e.key)
            // Vom Nutzer auf dem Startbildschirm entfernt: nicht wieder aufdrängen, solange sich nichts tut
            if (old != null && !old.isBrowsable && old.lastPlaybackPositionMillis.toLong() == e.position) return@forEachIndexed
            val builder = WatchNextProgram.Builder()
                .setType(if (e.key.startsWith("EP:")) TvContractCompat.PreviewProgramColumns.TYPE_TV_EPISODE else TvContractCompat.PreviewProgramColumns.TYPE_MOVIE)
                .setWatchNextType(TvContractCompat.WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE)
                .setLastEngagementTimeUtcMillis(now - i * 1000L)
                .setTitle(e.title)
                .setDescription(e.subtitle.orEmpty())
                .setLastPlaybackPositionMillis(e.position.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setDurationMillis(e.duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
                .setIntentUri(link(context, account, CONTINUE, e.key))
                .setInternalProviderId(e.key)
                .setBrowsable(true)
            e.image?.let { builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_16_9) }
            if (old != null) helper.updateWatchNextProgram(builder.build(), old.id) else helper.publishWatchNextProgram(builder.build())
        }
        // Zu Ende geschaut oder aus „Weiterschauen“ entfernt
        existing.values.forEach { resolver.delete(TvContractCompat.buildWatchNextProgramUri(it.id), null, null) }
    }

    // ---------- Lieblingssender ----------

    private fun channelId(context: Context): Long? =
        PreviewChannelHelper(context).allChannels.firstOrNull { it.internalProviderId == CHANNEL_ID }?.id

    private fun updateChannel(context: Context, account: String, channels: List<Stream>) {
        val helper = PreviewChannelHelper(context)
        val id = channelId(context) ?: run {
            if (channels.isEmpty()) return
            val logo = ContextCompat.getDrawable(context, R.mipmap.ic_launcher)?.toBitmap(256, 256) ?: return
            val channel = PreviewChannel.Builder()
                .setDisplayName(t("Klar TV – Lieblingssender"))
                .setAppLinkIntentUri(Uri.parse(Intent(context, MainActivity::class.java).toUri(Intent.URI_INTENT_SCHEME)))
                .setInternalProviderId(CHANNEL_ID)
                .setLogo(logo)
                .build()
            // Der erste Kanal einer App erscheint ohne Nachfrage auf dem Startbildschirm
            helper.publishDefaultChannel(channel)
        }
        context.contentResolver.delete(TvContractCompat.buildPreviewProgramsUriForChannel(id), null, null)
        channels.take(30).forEachIndexed { i, s ->
            val builder = PreviewProgram.Builder()
                .setChannelId(id)
                .setType(TvContractCompat.PreviewProgramColumns.TYPE_CHANNEL)
                .setTitle(s.name)
                .setLive(true)
                .setWeight(1000 - i)
                .setIntentUri(link(context, account, MainActivity.CHANNEL, s.key))
                .setInternalProviderId(s.key)
            s.logo?.takeIf { it.isNotBlank() }?.let {
                builder.setPosterArtUri(Uri.parse(it)).setPosterArtAspectRatio(TvContractCompat.PreviewProgramColumns.ASPECT_RATIO_1_1)
            }
            helper.publishPreviewProgram(builder.build())
        }
    }
}
