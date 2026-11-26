package io.github.phillhf.iptv.server

import com.sun.net.httpserver.HttpServer
import io.github.phillhf.iptv.I18n
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.platform.AppContext
import org.json.JSONObject
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Der ganze Weg mit echtem FFmpeg: ein kleiner Anbieter mit M3U-Playlist, darauf Sender mit AC3-Ton und
 * MPEG-2-Bild, die der Browser so nicht abspielen kann. Der Server muss sie umwandeln.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ServerTest {
    companion object {
        private lateinit var provider: HttpServer
        private lateinit var web: Web
        private lateinit var tools: File
        private lateinit var base: String
        private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
        private var cookie = ""
        private lateinit var context: AppContext
        private lateinit var core: Core

        private fun ffmpeg(vararg args: String) {
            val p = ProcessBuilder(listOf(File(tools, "ffmpeg.exe").path, "-hide_banner", "-loglevel", "error", "-y") + args)
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertTrue("ffmpeg: $out", p.waitFor(120, TimeUnit.SECONDS) && p.exitValue() == 0)
        }

        @BeforeClass
        @JvmStatic
        fun setUp() {
            tools = File(System.getProperty("klartv.tools"))
            val media = File(System.getProperty("klartv.data"), "anbieter").apply { deleteRecursively(); mkdirs() }
            val src = arrayOf("-f", "lavfi", "-i", "testsrc2=size=960x540:rate=25", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000")
            ffmpeg(*src, "-t", "12", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "ac3", "-b:a", "192k", "-f", "mpegts", File(media, "a.ts").path)
            ffmpeg(*src, "-t", "12", "-c:v", "mpeg2video", "-q:v", "4", "-flags", "+ildct+ilme", "-field_order", "tt", "-c:a", "mp2", "-f", "mpegts", File(media, "b.ts").path)
            ffmpeg(
                *src, "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000",
                "-map", "0:v", "-map", "1:a", "-map", "2:a", "-t", "60", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a:0", "ac3", "-c:a:1", "aac", "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=ger", File(media, "film.mkv").path,
            )

            provider = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 10)
            val p = provider.address.port
            val playlist = """
                #EXTM3U
                #EXTINF:-1 tvg-id="a.de" tvg-logo="http://127.0.0.1:$p/logo.png" group-title="Test",Kanal A
                http://127.0.0.1:$p/a.ts
                #EXTINF:-1 tvg-id="b.de" group-title="Test",Kanal B
                http://127.0.0.1:$p/b.ts
                #EXTINF:-1 group-title="Filme",Testfilm
                http://127.0.0.1:$p/movie/film.mkv
            """.trimIndent()
            provider.createContext("/") { ex ->
                val path = ex.requestURI.path
                val bytes = when (path) {
                    "/list.m3u" -> playlist.toByteArray()
                    "/movie/film.mkv" -> File(media, "film.mkv").readBytes()
                    else -> File(media, path.removePrefix("/")).takeIf { it.isFile }?.readBytes()
                }
                if (bytes == null) {
                    ex.sendResponseHeaders(404, -1)
                } else {
                    ex.sendResponseHeaders(200, bytes.size.toLong())
                    ex.responseBody.use { it.write(bytes) }
                }
                ex.close()
            }
            provider.start()

            val dir = File(System.getProperty("klartv.data"), "server").apply { deleteRecursively(); mkdirs() }
            context = AppContext(dir)
            AppSettings.init(context)
            I18n.init(context)
            core = Core(context)
            web = Web(core, Auth(context), Media(tools), Images(File(dir, "bilder")), Config(File(dir, "server.properties")))
            web.start(0, "127.0.0.1")
            base = "http://127.0.0.1:${web.port}"
        }

        @AfterClass
        @JvmStatic
        fun tearDown() {
            if (::web.isInitialized) web.stop()
            if (::provider.isInitialized) provider.stop(0)
        }
    }

    private fun request(path: String, body: JSONObject? = null, headers: Map<String, String> = emptyMap(), csrf: Boolean = true): HttpResponse<String> {
        val b = HttpRequest.newBuilder(URI(base + path)).timeout(Duration.ofSeconds(60))
        if (cookie.isNotEmpty()) b.header("Cookie", cookie)
        headers.forEach { (k, v) -> b.header(k, v) }
        if (body != null) {
            b.header("Content-Type", "application/json")
            if (csrf) b.header("X-Klar", "1")
            b.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
        }
        val r = http.send(b.build(), HttpResponse.BodyHandlers.ofString())
        r.headers().firstValue("Set-Cookie").ifPresent { c -> cookie = c.substringBefore(';') }
        return r
    }

    private fun json(r: HttpResponse<String>) = JSONObject(r.body())

    @Test
    fun a_setupOnlyFromHomeNetwork() {
        val s = json(request("/api/session"))
        assertFalse(s.getBoolean("loggedIn"))
        assertTrue(s.getBoolean("setupNeeded"))
        // Über Cloudflare: keine Ersteinrichtung
        val viaTunnel = request("/api/setup", JSONObject().put("user", "phill").put("password", "geheim123"), mapOf("Cf-Connecting-IP" to "203.0.113.9"))
        assertEquals(403, viaTunnel.statusCode())
        // Ohne eigenen Kopf: als fremde Seite abgelehnt
        assertEquals(403, request("/api/setup", JSONObject().put("user", "phill").put("password", "geheim123"), csrf = false).statusCode())
        assertEquals(400, request("/api/setup", JSONObject().put("user", "phill").put("password", "kurz")).statusCode())
        assertEquals(200, request("/api/setup", JSONObject().put("user", "phill").put("password", "geheim123")).statusCode())
        assertTrue(cookie.startsWith("klartv="))
        assertTrue(json(request("/api/session")).getBoolean("loggedIn"))
    }

    @Test
    fun b_loginAndLockout() {
        val saved = cookie
        cookie = ""
        assertEquals(401, request("/api/state").statusCode())
        assertEquals(401, request("/play?ref=LIVE:x").statusCode())
        val tunnel = mapOf("Cf-Connecting-IP" to "198.51.100.7")
        repeat(5) { assertEquals(401, request("/api/login", JSONObject().put("user", "phill").put("password", "falsch$it"), tunnel).statusCode()) }
        // Jetzt gesperrt, auch mit dem richtigen Passwort
        assertEquals(429, request("/api/login", JSONObject().put("user", "phill").put("password", "geheim123"), tunnel).statusCode())
        // Ein anderer Besucher ist davon nicht betroffen
        assertEquals(200, request("/api/login", JSONObject().put("user", "Phill").put("password", "geheim123"), mapOf("Cf-Connecting-IP" to "198.51.100.8")).statusCode())
        assertTrue(cookie.isNotEmpty() && cookie != saved)
    }

    @Test
    fun c_providerAndCatalog() {
        val r = request("/api/accounts/save", JSONObject().put("type", "M3U").put("name", "Test").put("m3uUrl", "http://127.0.0.1:${provider.address.port}/list.m3u"))
        assertEquals(r.body(), 200, r.statusCode())
        val st = json(request("/api/state"))
        assertEquals(1, st.getJSONArray("accounts").length())
        // Zugangsdaten gehen nie an den Browser
        assertFalse(st.toString().contains("list.m3u"))
        val live = json(request("/api/catalog?kind=LIVE"))
        assertEquals(2, live.getJSONArray("streams").length())
        assertEquals("Kanal A", live.getJSONArray("streams").getJSONObject(0).getString("n"))
        assertFalse("Stream-Adressen bleiben auf dem Server", live.toString().contains(".ts"))
        val movies = json(request("/api/catalog?kind=MOVIE"))
        assertEquals("Testfilm", movies.getJSONArray("streams").getJSONObject(0).getString("n"))
    }

    @Test
    fun d_imagesNotFromThisComputer() {
        val logo = json(request("/api/catalog?kind=LIVE")).getJSONArray("streams").getJSONObject(0).getString("l")
        assertEquals(404, request("/img?u=" + URLEncoder.encode(logo, Charsets.UTF_8)).statusCode())
    }

    /** Spielt ab und prüft mit ffprobe, was beim Browser ankommt. */
    private fun play(query: String, seconds: Int = 4): JSONObject {
        val req = HttpRequest.newBuilder(URI("$base/play?$query")).header("Cookie", cookie).timeout(Duration.ofSeconds(60)).build()
        val r = http.send(req, HttpResponse.BodyHandlers.ofInputStream())
        assertEquals(200, r.statusCode())
        assertEquals("video/mp2t", r.headers().firstValue("Content-Type").get())
        val file = File.createTempFile("play", ".ts")
        val end = System.currentTimeMillis() + seconds * 1000L
        r.body().use { input ->
            file.outputStream().use { out ->
                val buf = ByteArray(65536)
                while (System.currentTimeMillis() < end) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        assertTrue("Nichts angekommen", file.length() > 50_000)
        val p = ProcessBuilder(File(tools, "ffprobe.exe").path, "-v", "error", "-show_streams", "-of", "json", file.path).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(30, TimeUnit.SECONDS)
        file.delete()
        return JSONObject(text)
    }

    private fun codecs(o: JSONObject): List<String> {
        val a = o.getJSONArray("streams")
        return (0 until a.length()).map { a.getJSONObject(it).getString("codec_name") }
    }

    @Test
    fun e_ac3BecomesAac() {
        val probe = json(request("/api/probe?ref=LIVE:" + liveId(0)))
        assertEquals("h264", probe.getString("video"))
        assertEquals(listOf("h264", "aac"), codecs(play("ref=LIVE:${liveId(0)}&hevc=0")))
    }

    @Test
    fun f_mpeg2BecomesH264() {
        assertEquals(listOf("h264", "aac"), codecs(play("ref=LIVE:${liveId(1)}&hevc=0")))
    }

    @Test
    fun g_movieWithGermanSoundAndSeeking() {
        val id = json(request("/api/catalog?kind=MOVIE")).getJSONArray("streams").getJSONObject(0).getString("i")
        val probe = json(request("/api/probe?ref=MOVIE:$id"))
        assertTrue(probe.getLong("duration") in 59_000..61_000)
        assertEquals(2, probe.getJSONArray("audio").length())
        // Deutsch ist die zweite Spur
        assertEquals(1, probe.getInt("chosen"))
        val out = play("ref=MOVIE:$id&hevc=0&a=1&t=30000")
        assertEquals(listOf("h264", "aac"), codecs(out))
        // Fortschritt merken, auch für die App
        assertEquals(200, request("/api/progress", JSONObject().put("ref", "MOVIE:$id").put("parent", "MOVIE:$id").put("title", "Testfilm").put("position", 30_000).put("duration", 60_000)).statusCode())
        val st = json(request("/api/state"))
        assertEquals("MOVIE:$id", st.getJSONArray("continue").getJSONObject(0).getString("key"))
        assertEquals(30_000, json(request("/api/vod?id=$id")).getLong("position"))
        // Zu Ende geschaut: gesehen, nicht mehr in „Weiterschauen“
        request("/api/progress", JSONObject().put("ref", "MOVIE:$id").put("parent", "MOVIE:$id").put("title", "Testfilm").put("position", 590_000).put("duration", 600_000))
        val after = json(request("/api/state"))
        assertEquals("MOVIE:$id", after.getJSONArray("watched").getString(0))
        assertEquals(0, after.getJSONArray("continue").length())
        // Von Hand zurücknehmen
        assertEquals(200, request("/api/watched", JSONObject().put("keys", org.json.JSONArray(listOf("MOVIE:$id"))).put("on", false)).statusCode())
        assertEquals(0, json(request("/api/state")).getJSONArray("watched").length())
    }

    @Test
    fun h_pageAndHeaders() {
        val r = request("/")
        assertEquals(200, r.statusCode())
        assertTrue(r.body().contains("app.js"))
        assertTrue(r.headers().firstValue("Content-Security-Policy").get().contains("frame-ancestors 'none'"))
        assertEquals(200, request("/vendor/mpegts.js").statusCode())
        // Unbekannte Wege zeigen die Seite, nicht Dateien des Rechners
        assertTrue(request("/../../build.gradle.kts").body().contains("app.js"))
    }

    @Test
    fun j_profilesKeepFavoritesApart() {
        io.github.phillhf.iptv.data.ProfileStore(context).save(
            listOf(io.github.phillhf.iptv.data.Profile("", "Papa"), io.github.phillhf.iptv.data.Profile("kind", "Lena", 2)),
        )
        val st = json(request("/api/state"))
        assertTrue(st.getBoolean("chooseProfile"))
        assertEquals(2, st.getJSONArray("profiles").length())
        val key = "LIVE:" + liveId(0)
        val before = st.getJSONArray("favorites").length()
        // Als Lena: eigener Favorit
        assertEquals(400, request("/api/profile", JSONObject().put("id", "gibtsnicht")).statusCode())
        val kid = json(request("/api/profile", JSONObject().put("id", "kind")))
        assertEquals("kind", kid.getString("profile"))
        assertTrue(!kid.getBoolean("chooseProfile"))
        request("/api/favorite", JSONObject().put("key", key))
        assertEquals(listOf(key), json(request("/api/state")).getJSONArray("favorites").let { a -> (0 until a.length()).map { a.getString(it) } })
        // Zurück zu Papa: dort unverändert
        request("/api/profile", JSONObject().put("id", ""))
        assertEquals(before, json(request("/api/state")).getJSONArray("favorites").length())
        assertEquals(listOf(key), io.github.phillhf.iptv.data.Library(context, core.active!!.id, "kind").favorites())
    }

    @Test
    fun i_syncQr() {
        val r = json(request("/api/sync/start", JSONObject()))
        assertTrue(r.getString("link").startsWith("klartv://sync"))
        assertTrue(r.getString("qr").startsWith("<svg"))
        request("/api/sync/stop", JSONObject())
    }

    private fun liveId(i: Int) = json(request("/api/catalog?kind=LIVE")).getJSONArray("streams").getJSONObject(i).getString("i")
}
