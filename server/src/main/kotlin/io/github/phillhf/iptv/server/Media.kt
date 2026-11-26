package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Http
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * Bringt Streams des Anbieters in eine Form, die der Browser abspielt: MPEG-TS mit H.264 oder H.265 und AAC-Ton.
 * Bild wird nur umgewandelt, wenn der Browser es nicht kann; Ton fast immer, denn AC3 und MP2 gehen im Browser nicht.
 */
class Media(private val tools: File) {
    private val log = Logger.getLogger("Media")
    private val ffmpeg = File(tools, "ffmpeg.exe").takeIf { it.exists() } ?: File(tools, "ffmpeg")
    private val ffprobe = File(tools, "ffprobe.exe").takeIf { it.exists() } ?: File(tools, "ffprobe")

    val available: Boolean get() = ffmpeg.exists() && ffprobe.exists()

    data class Track(val index: Int, val codec: String, val language: String, val channels: Int, val title: String)

    data class Probe(val video: String?, val interlaced: Boolean, val height: Int, val audio: List<Track>, val durationMs: Long) {
        fun toJson(): JSONObject = JSONObject()
            .put("video", video ?: "")
            .put("duration", durationMs)
            .put("audio", audio.map { JSONObject().put("index", it.index).put("codec", it.codec).put("lang", it.language).put("title", it.title).put("channels", it.channels) })
    }

    private class Cached(val probe: Probe, val time: Long)

    private val probes = ConcurrentHashMap<String, Cached>()

    /** Liest Bild- und Tonspuren. Eine Stunde zwischengespeichert, denn jede Probe ist eine Verbindung zum Anbieter. */
    fun probe(url: String, live: Boolean): Probe {
        probes[url]?.let { if (System.currentTimeMillis() - it.time < 3_600_000L) return it.probe }
        val cmd = listOf(
            ffprobe.path, "-v", "error", "-user_agent", Http.USER_AGENT,
            "-analyzeduration", if (live) "2500000" else "5000000", "-probesize", "5000000",
            "-show_streams", "-show_format", "-of", "json", url,
        )
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val text = StringBuilder()
        val reader = Thread { runCatching { text.append(p.inputStream.bufferedReader().readText()) } }.apply { start() }
        if (!p.waitFor(25, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw IOException("Der Anbieter antwortet nicht (Zeitüberschreitung).")
        }
        reader.join(2000)
        val o = runCatching { JSONObject(text.toString()) }.getOrNull() ?: throw IOException("Stream nicht lesbar.")
        val streams = o.optJSONArray("streams")
        var video: String? = null
        var interlaced = false
        var height = 0
        val audio = ArrayList<Track>()
        var audioNo = 0
        for (i in 0 until (streams?.length() ?: 0)) {
            val s = streams!!.getJSONObject(i)
            when (s.optString("codec_type")) {
                "video" -> if (video == null && s.optJSONObject("disposition")?.optInt("attached_pic") != 1) {
                    video = s.optString("codec_name")
                    height = s.optInt("height")
                    interlaced = s.optString("field_order", "progressive").let { it != "progressive" && it != "unknown" && it.isNotEmpty() }
                }
                "audio" -> {
                    val tags = s.optJSONObject("tags")
                    audio += Track(audioNo++, s.optString("codec_name"), tags?.optString("language").orEmpty(), s.optInt("channels"), tags?.optString("title").orEmpty())
                }
            }
        }
        if (video == null && audio.isEmpty()) throw IOException("Der Stream enthält weder Bild noch Ton.")
        val duration = ((o.optJSONObject("format")?.optString("duration")?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        return Probe(video, interlaced, height, audio, if (live) 0 else duration).also { probes[url] = Cached(it, System.currentTimeMillis()) }
    }

    /** Tonspur in der bevorzugten Sprache, sonst die erste. */
    fun pickAudio(p: Probe, wanted: Int?): Int? {
        if (p.audio.isEmpty()) return null
        if (wanted != null && p.audio.any { it.index == wanted }) return wanted
        val lang = AppSettings.audioLanguage.lowercase()
        val names = when (lang) {
            "de" -> setOf("de", "deu", "ger", "german", "deutsch")
            "en" -> setOf("en", "eng", "english")
            "fr" -> setOf("fr", "fra", "fre")
            "es" -> setOf("es", "spa")
            "it" -> setOf("it", "ita")
            "tr" -> setOf("tr", "tur")
            "pt" -> setOf("pt", "por")
            "nl" -> setOf("nl", "nld", "dut")
            "pl" -> setOf("pl", "pol")
            "ro" -> setOf("ro", "ron", "rum")
            "sq" -> setOf("sq", "sqi", "alb")
            "ru" -> setOf("ru", "rus")
            "ar" -> setOf("ar", "ara")
            else -> setOf(lang)
        }
        return (p.audio.firstOrNull { it.language.lowercase() in names } ?: p.audio.first()).index
    }

    // ---------- Laufende Umwandlungen ----------

    private val running = ConcurrentHashMap<String, Process>()

    /** Beendet, was diese Sitzung gerade abspielt; viele Anbieter erlauben nur eine Verbindung. */
    fun stop(owner: String) {
        running.remove(owner)?.let { kill(it) }
    }

    private fun kill(p: Process) {
        p.descendants().forEach { it.destroyForcibly() }
        p.destroyForcibly()
    }

    /**
     * Schreibt den umgewandelten Stream nach [out], bis der Browser die Verbindung schließt.
     * [hevc] = der Browser kann H.265; [startMs] = ab dieser Stelle (Filme und Serien).
     */
    fun stream(owner: String, url: String, live: Boolean, probe: Probe, audio: Int?, hevc: Boolean, startMs: Long, out: OutputStream) {
        val copyVideo = probe.video == "h264" || (probe.video == "hevc" && hevc)
        val a = audio?.let { i -> probe.audio.firstOrNull { it.index == i } }
        val cmd = ArrayList<String>()
        cmd += listOf(ffmpeg.path, "-hide_banner", "-loglevel", "error", "-nostdin", "-user_agent", Http.USER_AGENT)
        if (url.startsWith("http")) cmd += listOf("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "5")
        if (live) cmd += listOf("-fflags", "+genpts+discardcorrupt")
        if (!live && startMs > 0) cmd += listOf("-ss", "%.3f".format(java.util.Locale.ROOT, startMs / 1000.0))
        cmd += listOf("-i", url)
        if (probe.video != null) cmd += listOf("-map", "0:v:0")
        if (a != null) cmd += listOf("-map", "0:a:${a.index}")
        if (probe.video != null) {
            if (copyVideo) {
                cmd += listOf("-c:v", "copy")
            } else {
                val filters = buildList {
                    if (probe.interlaced) add("yadif=deint=interlaced")
                    if (probe.height > 1080) add("scale=-2:1080")
                    add("format=yuv420p")
                }
                cmd += listOf(
                    "-c:v", "libx264", "-preset", "veryfast", "-tune", "zerolatency", "-crf", "23",
                    "-maxrate", "8M", "-bufsize", "16M", "-g", "50", "-vf", filters.joinToString(","),
                )
            }
        }
        if (a != null) {
            if (a.codec == "aac") {
                cmd += listOf("-c:a", "copy")
            } else {
                cmd += listOf("-c:a", "aac", "-ac", "2", "-b:a", "192k")
            }
        }
        cmd += listOf("-sn", "-dn", "-f", "mpegts", "-muxdelay", "0", "-muxpreload", "0", "pipe:1")

        stop(owner)
        val p = ProcessBuilder(cmd).start()
        running[owner] = p
        val errors = StringBuilder()
        Thread {
            runCatching { p.errorStream.bufferedReader().forEachLine { if (errors.length < 4000) errors.appendLine(it) } }
        }.apply { isDaemon = true }.start()
        try {
            val buf = ByteArray(64 * 1024)
            val input = p.inputStream
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                out.flush()
            }
        } catch (_: IOException) {
            // Browser hat die Verbindung geschlossen: gewollt
        } finally {
            kill(p)
            running.remove(owner, p)
            if (errors.isNotBlank()) log.info("ffmpeg: ${errors.toString().take(1000).replace(url, "<Adresse>")}")
        }
    }

    fun stopAll() {
        running.values.forEach { kill(it) }
        running.clear()
    }
}
