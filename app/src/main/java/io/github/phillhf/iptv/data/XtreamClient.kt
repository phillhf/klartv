package io.github.phillhf.iptv.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name).trim().takeIf { it.isNotEmpty() }

/** Zugriff auf Anbieter mit Xtream-Codes-Schnittstelle (player_api.php). */
class XtreamClient(server: String, private val username: String, private val password: String) {
    val base = normalizeServer(server)

    private fun api(params: String = ""): Any {
        val text = Http.get("$base/player_api.php?username=${queryPart(username)}&password=${queryPart(password)}$params").trim()
        if (text.isEmpty()) return JSONArray()
        return JSONTokener(text).nextValue()
    }

    fun login(): AccountStatus {
        val root = api() as? JSONObject ?: throw IOException("Unerwartete Antwort vom Server. Ist die Adresse richtig?")
        val info = root.optJSONObject("user_info") ?: throw IOException("Unerwartete Antwort vom Server. Ist die Adresse richtig?")
        if (info.optInt("auth", 0) != 1) throw IOException("Benutzername oder Passwort falsch.")
        val status = info.optStringOrNull("status")
        if (status != null && !status.equals("Active", ignoreCase = true)) {
            throw IOException("Das Konto ist nicht aktiv (Status: $status).")
        }
        return AccountStatus(
            status = status,
            expires = info.optStringOrNull("exp_date")?.toLongOrNull()?.times(1000),
            maxConnections = info.optStringOrNull("max_connections"),
            activeConnections = info.optStringOrNull("active_cons"),
        )
    }

    fun categories(kind: Kind): List<Category> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_categories"
            Kind.MOVIE -> "get_vod_categories"
            Kind.SERIES -> "get_series_categories"
        }
        val arr = api("&action=$action") as? JSONArray ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            Category(o.optStringOrNull("category_id") ?: "", o.optStringOrNull("category_name") ?: "Ohne Namen")
        }
    }

    fun streams(kind: Kind, liveFormat: String): List<Stream> {
        val action = when (kind) {
            Kind.LIVE -> "get_live_streams"
            Kind.MOVIE -> "get_vod_streams"
            Kind.SERIES -> "get_series"
        }
        val arr = api("&action=$action") as? JSONArray ?: return emptyList()
        val out = ArrayList<Stream>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optStringOrNull("name") ?: continue
            val category = o.optStringOrNull("category_id") ?: ""
            val stream = when (kind) {
                Kind.LIVE -> {
                    val id = o.optStringOrNull("stream_id") ?: continue
                    Stream(
                        kind, id, name, o.optStringOrNull("stream_icon"), category,
                        url = streamUrl("live", id, liveFormat),
                        epgId = o.optStringOrNull("epg_channel_id"),
                        number = o.optInt("num", i + 1),
                    )
                }
                Kind.MOVIE -> {
                    val id = o.optStringOrNull("stream_id") ?: continue
                    Stream(
                        kind, id, name, o.optStringOrNull("stream_icon"), category,
                        url = streamUrl("movie", id, o.optStringOrNull("container_extension") ?: "mp4"),
                        rating = o.optStringOrNull("rating")?.takeIf { it != "0" },
                    )
                }
                Kind.SERIES -> {
                    val id = o.optStringOrNull("series_id") ?: continue
                    Stream(
                        kind, id, name, o.optStringOrNull("cover"), category,
                        url = "",
                        plot = o.optStringOrNull("plot"),
                        rating = o.optStringOrNull("rating")?.takeIf { it != "0" },
                    )
                }
            }
            out += stream
        }
        return out
    }

    fun vodInfo(id: String): VodInfo {
        val root = api("&action=get_vod_info&vod_id=${queryPart(id)}") as? JSONObject ?: return VodInfo()
        val info = root.optJSONObject("info") ?: return VodInfo()
        return VodInfo(
            plot = info.optStringOrNull("plot") ?: info.optStringOrNull("description"),
            genre = info.optStringOrNull("genre"),
            year = info.optStringOrNull("releasedate") ?: info.optStringOrNull("year"),
            duration = info.optStringOrNull("duration"),
            cover = info.optStringOrNull("movie_image") ?: info.optStringOrNull("cover_big"),
        )
    }

    fun seriesInfo(seriesId: String): SeriesInfo {
        val root = api("&action=get_series_info&series_id=${queryPart(seriesId)}") as? JSONObject
            ?: throw IOException("Serie nicht gefunden.")
        val info = root.optJSONObject("info")
        val seasons = sortedMapOf<Int, MutableList<Episode>>()

        fun add(seasonKey: Int, e: JSONObject) {
            val id = e.optStringOrNull("id") ?: return
            val season = e.optStringOrNull("season")?.toIntOrNull() ?: seasonKey
            val details = e.optJSONObject("info")
            seasons.getOrPut(season) { mutableListOf() } += Episode(
                id = id,
                season = season,
                number = e.optStringOrNull("episode_num")?.toIntOrNull() ?: 0,
                title = e.optStringOrNull("title") ?: "Folge",
                url = streamUrl("series", id, e.optStringOrNull("container_extension") ?: "mp4"),
                plot = details?.optStringOrNull("plot"),
                image = details?.optStringOrNull("movie_image"),
                duration = details?.optStringOrNull("duration"),
            )
        }

        // Je nach Server ein Objekt {"1": [...]} oder eine Liste von Listen
        when (val episodes = root.opt("episodes")) {
            is JSONObject -> for (key in episodes.keys()) {
                val arr = episodes.optJSONArray(key) ?: continue
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(key.toIntOrNull() ?: 0, it) }
            }
            is JSONArray -> for (i in 0 until episodes.length()) {
                when (val inner = episodes.opt(i)) {
                    is JSONArray -> for (j in 0 until inner.length()) inner.optJSONObject(j)?.let { add(0, it) }
                    is JSONObject -> add(0, inner)
                }
            }
        }
        return SeriesInfo(
            plot = info?.optStringOrNull("plot"),
            cover = info?.optStringOrNull("cover"),
            seasons = seasons.mapValues { (_, list) -> list.sortedBy { it.number } },
        )
    }

    fun shortEpg(streamId: String): List<EpgEntry> {
        val root = api("&action=get_short_epg&stream_id=${queryPart(streamId)}&limit=8") as? JSONObject ?: return emptyList()
        val arr = root.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.mapNotNull { o ->
            val start = o.optStringOrNull("start_timestamp")?.toLongOrNull() ?: return@mapNotNull null
            val end = o.optStringOrNull("stop_timestamp")?.toLongOrNull() ?: start
            EpgEntry(
                title = decodeMaybeBase64(o.optString("title")),
                description = decodeMaybeBase64(o.optString("description")),
                start = start * 1000,
                end = end * 1000,
            )
        }.sortedBy { it.start }
    }

    private fun streamUrl(type: String, id: String, ext: String) =
        "$base/$type/${pathPart(username)}/${pathPart(password)}/$id.$ext"
}
