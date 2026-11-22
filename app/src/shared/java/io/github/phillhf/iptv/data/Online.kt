package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.t
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Entfernt Zusätze wie „DE |“, „(2021)“ oder „S1E3“, mit denen Suchdienste nichts anfangen können. */
fun searchTitle(title: String): String = title
    .replace(Regex("^[A-Z0-9]{2,4}\\s*[|:\\-–]\\s*"), "")
    .replace(Regex("[\\[(][^\\])]*[\\])]"), " ")
    .replace(Regex("\\b(4K|UHD|FHD|HD|HEVC|MULTI|1080p|720p)\\b", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

/** Untertitelsuche mit dem eigenen API-Schlüssel des Nutzers. Ohne Schlüssel findet keine Anfrage statt. */
object OpenSubtitles {
    data class Hit(val fileId: Long, val name: String, val language: String, val downloads: Int)

    private const val BASE = "https://api.opensubtitles.com/api/v1"

    private fun key(): String = AppSettings.openSubtitlesKey.trim().ifEmpty {
        throw IOException(t("Trage zuerst in den Einstellungen deinen OpenSubtitles-Schlüssel ein."))
    }

    private fun headers() = mapOf("Api-Key" to key(), "User-Agent" to "KlarTV v1", "Accept" to "application/json")

    fun search(title: String, language: String): List<Hit> {
        val episode = Regex("S(\\d+)\\s*E(\\d+)", RegexOption.IGNORE_CASE).find(title)
        val name = searchTitle(title.substringBefore(" – S").replace(Regex("S\\d+\\s*E\\d+", RegexOption.IGNORE_CASE), ""))
        // Der Dienst verlangt alphabetisch sortierte, kleingeschriebene Angaben
        val params = sortedMapOf("languages" to language, "query" to name.lowercase())
        if (episode != null) {
            params["season_number"] = episode.groupValues[1].toInt().toString()
            params["episode_number"] = episode.groupValues[2].toInt().toString()
        }
        val query = params.entries.joinToString("&") { "${it.key}=${queryPart(it.value)}" }
        return parse(Http.get("$BASE/subtitles?$query", headers()))
    }

    fun parse(json: String): List<Hit> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            val a = data.optJSONObject(i)?.optJSONObject("attributes") ?: return@mapNotNull null
            val file = a.optJSONArray("files")?.optJSONObject(0) ?: return@mapNotNull null
            Hit(
                fileId = file.optLong("file_id"),
                name = a.optStringOrNull("release") ?: file.optStringOrNull("file_name") ?: "?",
                language = a.optString("language"),
                downloads = a.optInt("download_count"),
            )
        }.sortedByDescending { it.downloads }.take(40)
    }

    fun download(hit: Hit, folder: File): File {
        val conn = URL("$BASE/download").openConnection() as HttpURLConnection
        val link = try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            headers().forEach { (k, v) -> conn.setRequestProperty(k, v) }
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(JSONObject().put("file_id", hit.fileId).toString().toByteArray()) }
            if (conn.responseCode == 406 || conn.responseCode == 429) throw IOException(t("Das Tageslimit von OpenSubtitles ist erreicht."))
            if (conn.responseCode !in 200..299) throw IOException(t("Der Server meldet Fehler %1.", conn.responseCode))
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).getString("link")
        } finally {
            conn.disconnect()
        }
        val file = File(folder, "untertitel-${hit.fileId}.srt")
        file.writeText(Http.get(link))
        return file
    }
}

/** Bilder der Schauspieler und fehlende Beschreibungen von TMDB, mit eigenem Schlüssel des Nutzers. */
object Tmdb {
    data class Details(val people: List<Person>, val backdrop: String?, val plot: String?, val trailer: String?)

    private const val BASE = "https://api.themoviedb.org/3"
    private const val IMAGES = "https://image.tmdb.org/t/p"

    private fun get(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val key = AppSettings.tmdbKey.trim()
        // Der lange „Lesezugriffs-Token“ gehört in die Kopfzeile, der kurze Schlüssel in die Adresse
        val bearer = key.length > 60
        val all = if (bearer) params else params + ("api_key" to key)
        val query = all.entries.joinToString("&") { "${it.key}=${queryPart(it.value)}" }
        return JSONObject(Http.get("$BASE$path?$query", if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()))
    }

    fun details(title: String, year: String?, tmdbId: String?, series: Boolean, language: String): Details? {
        if (AppSettings.tmdbKey.isBlank()) return null
        val type = if (series) "tv" else "movie"
        val id = tmdbId?.toLongOrNull()?.takeIf { it > 0 } ?: find(type, searchTitle(title), year) ?: return null
        return parse(
            get("/$type/$id", mapOf("language" to language, "append_to_response" to "credits,videos", "include_video_language" to "$language,en")),
        )
    }

    private fun find(type: String, title: String, year: String?): Long? {
        val params = HashMap<String, String>()
        params["query"] = title
        if (year != null) params[if (type == "tv") "first_air_date_year" else "year"] = year
        val hits = get("/search/$type", params).optJSONArray("results") ?: return null
        return hits.optJSONObject(0)?.optLong("id")?.takeIf { it > 0 }
    }

    fun parse(o: JSONObject): Details {
        val cast = o.optJSONObject("credits")?.optJSONArray("cast")
        val people = (0 until minOf(cast?.length() ?: 0, 20)).mapNotNull { cast?.optJSONObject(it) }.map {
            Person(it.optString("name"), it.optStringOrNull("character"), it.optStringOrNull("profile_path")?.let { p -> "$IMAGES/w185$p" })
        }
        val videos = o.optJSONObject("videos")?.optJSONArray("results")
        val trailer = (0 until (videos?.length() ?: 0)).mapNotNull { videos?.optJSONObject(it) }
            .firstOrNull { it.optString("site") == "YouTube" && it.optString("type") == "Trailer" }?.optStringOrNull("key")
        return Details(
            people = people,
            backdrop = o.optStringOrNull("backdrop_path")?.let { "$IMAGES/w1280$it" },
            plot = o.optStringOrNull("overview"),
            trailer = trailer,
        )
    }
}
