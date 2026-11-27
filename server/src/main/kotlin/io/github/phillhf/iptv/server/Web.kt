package io.github.phillhf.iptv.server

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.phillhf.iptv.I18n
import io.github.phillhf.iptv.data.Account
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.SourceType
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.friendlyError
import io.github.phillhf.iptv.platform.appVersion
import io.github.phillhf.iptv.sync.SyncData
import io.github.phillhf.iptv.sync.SyncHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.GZIPOutputStream

/** Weboberfläche und Schnittstelle. Alles außer Anmeldung und den Dateien der Seite braucht eine Sitzung. */
class Web(
    private val core: Core,
    private val auth: Auth,
    private val media: Media,
    private val images: Images,
    private val config: Config,
    private val update: AutoUpdate? = null,
) {
    private val log = Logger.getLogger("Web")
    private lateinit var server: HttpServer

    fun start(port: Int, bind: String = "0.0.0.0") {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName(bind), port), 64)
        server.executor = Executors.newVirtualThreadPerTaskExecutor()
        server.createContext("/") { ex -> handle(ex) }
        server.start()
    }

    val port: Int get() = server.address.port

    fun stop() {
        media.stopAll()
        server.stop(1)
    }

    // ---------- Anfragen ----------

    private class Request(val ex: HttpExchange) {
        val path: String = ex.requestURI.rawPath
        val method: String = ex.requestMethod
        val query: Map<String, String> = parse(ex.requestURI.rawQuery)

        fun header(name: String): String? = ex.requestHeaders.getFirst(name)

        val cookie: String? = header("Cookie")?.split(';')?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE=") }?.substringAfter('=')

        private var bodyCache: JSONObject? = null

        fun body(): JSONObject = bodyCache ?: run {
            val bytes = ex.requestBody.use { it.readNBytes(1_000_001) }
            if (bytes.size > 1_000_000) throw BadRequest("Anfrage zu groß")
            (if (bytes.isEmpty()) JSONObject() else runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrElse { throw BadRequest("Ungültige Anfrage") })
                .also { bodyCache = it }
        }

        fun q(name: String): String = query[name] ?: throw BadRequest("$name fehlt")

        companion object {
            fun parse(raw: String?): Map<String, String> = raw.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
                val k = URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8)
                val v = URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8)
                k to v
            }
        }
    }

    private class BadRequest(message: String, val code: Int = 400) : Exception(message)

    /** Über Cloudflare oder einen anderen Vermittler – oder direkt aus dem eigenen Netz? */
    private fun viaProxy(r: Request) = listOf("Cf-Connecting-IP", "X-Forwarded-For", "Forwarded", "Cf-Ray", "X-Real-IP").any { r.header(it) != null }

    private fun isLocal(r: Request): Boolean {
        if (viaProxy(r)) return false
        val a = r.ex.remoteAddress.address
        return a.isLoopbackAddress || a.isSiteLocalAddress || a.isLinkLocalAddress ||
            // IPv6 im eigenen Netz (fc00::/7)
            (a.address.size == 16 && (a.address[0].toInt() and 0xfe) == 0xfc)
    }

    /** Für die Sperre nach Fehlversuchen: bei Cloudflare die Adresse des Besuchers. */
    private fun client(r: Request): String {
        val a = r.ex.remoteAddress.address
        val forwarded = r.header("Cf-Connecting-IP") ?: r.header("X-Forwarded-For")?.substringBefore(',')?.trim()
        return if ((a.isLoopbackAddress || a.isSiteLocalAddress) && !forwarded.isNullOrBlank()) forwarded else a.hostAddress
    }

    private fun https(r: Request) =
        r.header("X-Forwarded-Proto").equals("https", true) || r.header("Cf-Visitor")?.contains("https") == true

    private fun handle(ex: HttpExchange) {
        val r = Request(ex)
        try {
            securityHeaders(ex)
            when {
                r.path.startsWith("/api/") -> api(r)
                r.path == "/play" -> play(r)
                r.path == "/img" -> image(r)
                else -> static(r)
            }
        } catch (e: BadRequest) {
            send(ex, e.code, JSONObject().put("error", e.message))
        } catch (e: IOException) {
            // Browser weg oder Anbieter nicht erreichbar
            runCatching { send(ex, 502, JSONObject().put("error", friendlyError(e))) }
        } catch (e: Throwable) {
            log.log(Level.WARNING, "Fehler bei ${r.path}", e)
            runCatching { send(ex, 500, JSONObject().put("error", friendlyError(e))) }
        } finally {
            ex.close()
        }
    }

    private fun securityHeaders(ex: HttpExchange) {
        val h = ex.responseHeaders
        h["X-Content-Type-Options"] = "nosniff"
        h["X-Frame-Options"] = "DENY"
        h["Referrer-Policy"] = "no-referrer"
        h["Content-Security-Policy"] = "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; " +
            "script-src 'self'; style-src 'self'; connect-src 'self'; worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
    }

    // ---------- Antworten ----------

    private fun send(ex: HttpExchange, code: Int, json: Any) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders["Content-Type"] = "application/json; charset=utf-8"
        ex.responseHeaders["Cache-Control"] = "no-store"
        sendBytes(ex, code, bytes)
    }

    private fun sendBytes(ex: HttpExchange, code: Int, bytes: ByteArray) {
        val gzip = bytes.size > 2048 && ex.requestHeaders.getFirst("Accept-Encoding")?.contains("gzip") == true &&
            ex.responseHeaders.getFirst("Content-Type")?.let { it.startsWith("image/") }?.not() ?: true
        val body = if (gzip) {
            ex.responseHeaders["Content-Encoding"] = "gzip"
            ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
        } else {
            bytes
        }
        if (ex.requestMethod == "HEAD") {
            ex.sendResponseHeaders(code, -1)
            return
        }
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) ex.responseBody.use { it.write(body) }
    }

    private fun ok() = JSONObject().put("ok", true)

    // ---------- Seite ----------

    private fun static(r: Request) {
        if (r.method != "GET" && r.method != "HEAD") throw BadRequest("Nicht erlaubt", 405)
        val path = r.path.removePrefix("/").ifEmpty { "index.html" }
        val known = path.matches(Regex("[a-z0-9_-]+(/[a-z0-9_.-]+)*\\.[a-z0-9]+")) && !path.contains("..")
        // Beim Entwickeln direkt aus dem Quellordner
        val dev = System.getProperty("klartv.web")?.let { java.io.File(it) }
        fun resource(p: String) = dev?.let { d -> java.io.File(d, p).takeIf { it.isFile }?.inputStream() } ?: Web::class.java.getResourceAsStream("/web/$p")
        val res = if (known) resource(path) else null
        val bytes: ByteArray
        val name: String
        if (res == null) {
            // Alles andere ist eine Ansicht der Seite selbst
            bytes = resource("index.html")!!.use { it.readBytes() }
            name = "index.html"
        } else {
            bytes = res.use { it.readBytes() }
            name = path
        }
        val type = when (name.substringAfterLast('.')) {
            "html" -> "text/html; charset=utf-8"
            "js" -> "text/javascript; charset=utf-8"
            "css" -> "text/css; charset=utf-8"
            "svg" -> "image/svg+xml"
            "png" -> "image/png"
            "ico" -> "image/x-icon"
            "json", "webmanifest" -> "application/manifest+json"
            "txt" -> "text/plain; charset=utf-8"
            else -> "application/octet-stream"
        }
        val text = if (name == "index.html") String(bytes).replace("@V@", appVersion).toByteArray() else bytes
        r.ex.responseHeaders["Content-Type"] = type
        r.ex.responseHeaders["Cache-Control"] = if (name == "index.html") "no-cache" else "public, max-age=86400"
        sendBytes(r.ex, 200, text)
    }

    // ---------- Schnittstelle ----------

    private fun api(r: Request) {
        val ex = r.ex
        val route = r.path.removePrefix("/api/")
        if (r.method == "POST" && r.header("X-Klar") != "1") throw BadRequest("Nicht erlaubt", 403)

        // Ohne Anmeldung
        when (route) {
            "session" -> {
                val s = auth.session(r.cookie)
                return send(
                    ex, 200,
                    JSONObject().put("loggedIn", s != null).put("setupNeeded", !auth.isSetUp).put("setupAllowed", isLocal(r))
                        .put("version", appVersion).put("user", if (s != null) auth.user else ""),
                )
            }
            "setup" -> {
                if (auth.isSetUp) throw BadRequest("Schon eingerichtet", 409)
                if (!isLocal(r)) throw BadRequest("Die Ersteinrichtung geht nur aus dem eigenen Netz.", 403)
                val b = r.body()
                try {
                    auth.setUp(b.optString("user"), b.optString("password"))
                } catch (e: IllegalArgumentException) {
                    throw BadRequest(e.message ?: "Ungültig")
                }
                return login(r, auth.newSession())
            }
            "login" -> {
                val who = client(r)
                val wait = auth.waitSeconds(who)
                if (wait > 0) throw BadRequest("Zu viele Fehlversuche. Bitte $wait Sekunden warten.", 429)
                val b = r.body()
                if (!auth.check(b.optString("user"), b.optString("password"))) {
                    auth.failed(who)
                    log.info("Fehlgeschlagene Anmeldung von $who")
                    throw BadRequest("Benutzername oder Passwort falsch.", 401)
                }
                auth.succeeded(who)
                return login(r, auth.newSession())
            }
        }

        val session = auth.session(r.cookie) ?: throw BadRequest("Bitte anmelden.", 401)
        when (route) {
            "logout" -> {
                auth.end(session.token)
                media.stop(session.token)
                ex.responseHeaders.add("Set-Cookie", "$COOKIE=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict")
                send(ex, 200, ok())
            }
            "password" -> {
                val b = r.body()
                if (!auth.check(auth.user, b.optString("old"))) throw BadRequest("Das bisherige Passwort stimmt nicht.", 403)
                try {
                    auth.setUp(b.optString("user").ifBlank { auth.user }, b.optString("password"))
                } catch (e: IllegalArgumentException) {
                    throw BadRequest(e.message ?: "Ungültig")
                }
                auth.endOthers(session.token)
                send(ex, 200, ok())
            }
            "state" -> send(ex, 200, state(session))
            "catalog" -> {
                val kind = kind(r.q("kind"))
                if (r.query["refresh"] == "1") core.rawCatalog(kind, refresh = true)
                send(ex, 200, catalogJson(core.catalog(kind, session.unlocked)))
            }
            "now" -> {
                val ids = r.q("ids").split(',').filter { it.isNotBlank() }.take(80)
                val o = JSONObject()
                ids.parallelStream().map { id -> id to core.stream("LIVE:$id") }.toList().forEach { (id, s) ->
                    if (s != null) o.put(id, JSONArray(core.nowNext(s).map { epgJson(it) }))
                }
                send(ex, 200, o)
            }
            "epg" -> {
                val s = core.stream("LIVE:" + r.q("id")) ?: throw BadRequest("Unbekannter Sender", 404)
                val now = System.currentTimeMillis()
                val from = r.query["from"]?.toLongOrNull() ?: (now - 86_400_000L * s.archiveDays.coerceIn(0, 7))
                val to = r.query["to"]?.toLongOrNull() ?: (now + 2 * 86_400_000L)
                send(ex, 200, JSONObject().put("archive", s.archiveDays).put("list", JSONArray(core.programmes(s, from, to).map { epgJson(it) })))
            }
            "vod" -> {
                val i = core.vodInfo(r.q("id"))
                val o = JSONObject()
                if (i != null) {
                    o.put("plot", i.plot).put("genre", i.genre).put("year", i.year).put("duration", i.duration).put("cover", i.cover)
                        .put("backdrop", i.backdrop).put("cast", i.cast).put("director", i.director).put("rating", i.rating)
                }
                o.put("position", core.library(session.profile)?.position("MOVIE:" + r.q("id")) ?: 0L)
                send(ex, 200, o)
            }
            "series" -> {
                val i = core.seriesInfo(r.q("id"))
                val lib = core.library(session.profile)
                val watched = lib?.watched().orEmpty()
                val seasons = JSONArray()
                i.seasons.toSortedMap().forEach { (no, eps) ->
                    seasons.put(
                        JSONObject().put("season", no).put(
                            "episodes",
                            JSONArray(
                                eps.map { e ->
                                    JSONObject().put("id", e.id).put("no", e.number).put("title", e.title).put("plot", e.plot)
                                        .put("image", e.image).put("duration", e.duration).put("position", lib?.position(e.key) ?: 0L)
                                        .put("watched", watched.contains(e.key))
                                },
                            ),
                        ),
                    )
                }
                send(
                    ex, 200,
                    JSONObject().put("plot", i.plot).put("cover", i.cover).put("backdrop", i.backdrop).put("genre", i.genre)
                        .put("year", i.year).put("cast", i.cast).put("director", i.director).put("seasons", seasons),
                )
            }
            "favorite" -> {
                val lib = core.library(session.profile) ?: throw BadRequest("Kein Anbieter")
                send(ex, 200, JSONObject().put("favorites", JSONArray(lib.toggleFavorite(r.body().getString("key")))))
            }
            "progress" -> {
                val b = r.body()
                core.saveProgress(
                    b.getString("ref"), b.optString("parent"), b.optString("title"), b.optString("subtitle").ifBlank { null },
                    b.optString("image").ifBlank { null }, b.optLong("position"), b.optLong("duration"), session.profile,
                )
                send(ex, 200, ok())
            }
            "watched" -> {
                val lib = core.library(session.profile) ?: throw BadRequest("Kein Anbieter")
                val b = r.body()
                val keys = b.getJSONArray("keys").let { a -> (0 until a.length()).map { a.getString(it) } }
                    .filter { it.startsWith("MOVIE:") || it.startsWith("EP:") }
                lib.setWatched(keys, b.optBoolean("on", true))
                send(ex, 200, ok())
            }
            "continue/remove" -> {
                core.library(session.profile)?.removeContinue(r.body().getString("key"))
                send(ex, 200, ok())
            }
            "probe" -> {
                if (!media.available) throw BadRequest("FFmpeg fehlt. Bitte den Server neu installieren.", 500)
                val t = core.resolve(r.q("ref"), session.profile) ?: throw BadRequest(notFound(r.q("ref")), 404)
                val p = media.probe(t.url, t.live)
                val o = p.toJson().put("live", t.live).put("chosen", media.pickAudio(p, null) ?: -1)
                if (t.durationMs > 0 && p.durationMs == 0L) o.put("duration", t.durationMs)
                core.keyOf(r.q("ref"))?.let { k -> o.put("position", core.library(session.profile)?.position(k) ?: 0L) }
                send(ex, 200, o)
            }
            "unlock" -> {
                if (!AppSettings.checkPin(r.body().optString("pin"))) {
                    val wait = AppSettings.pinWaitSeconds()
                    throw BadRequest(if (wait > 0) "Zu viele Fehlversuche. Bitte $wait Sekunden warten." else "PIN falsch.", 403)
                }
                session.unlocked = true
                send(ex, 200, ok())
            }
            "lock" -> {
                session.unlocked = false
                send(ex, 200, ok())
            }
            "accounts/save" -> {
                val b = r.body()
                val type = if (b.optString("type") == "M3U") SourceType.M3U else SourceType.XTREAM
                val old = core.accounts().firstOrNull { it.id == b.optString("id") }
                val a = Account(
                    id = old?.id ?: UUID.randomUUID().toString(),
                    name = b.optString("name").trim().ifEmpty { "Anbieter" },
                    type = type,
                    server = b.optString("server").trim(),
                    username = b.optString("username").trim(),
                    // Leer = unverändert, das Passwort wird nie an den Browser gegeben
                    password = b.optString("password").ifEmpty { old?.password.orEmpty() },
                    m3uUrl = b.optString("m3uUrl").trim().ifEmpty { old?.m3uUrl.orEmpty() },
                    // Programmführer wird unter „Programmführer“ eingestellt
                    epgUrls = old?.epgUrls.orEmpty(),
                    epgProvider = old?.epgProvider ?: true,
                    epgShift = old?.epgShift ?: 0,
                )
                try {
                    core.saveAccount(a)
                } catch (e: Exception) {
                    throw BadRequest(friendlyError(e))
                }
                send(ex, 200, ok())
            }
            "accounts/delete" -> {
                core.deleteAccount(r.body().getString("id"))
                send(ex, 200, ok())
            }
            "accounts/activate" -> {
                core.activate(r.body().getString("id"))
                send(ex, 200, ok())
            }
            "epg/settings" -> {
                // Ohne Anmeldung beim Anbieter: Es ändert sich nur, woher das Programm kommt
                val b = r.body()
                val a = core.active ?: throw BadRequest("Noch kein Anbieter eingerichtet.")
                val urls = if (b.has("urls")) Account.parseUrls(b.getString("urls")) else a.epgUrls
                if (urls.any { !it.startsWith("http://", true) && !it.startsWith("https://", true) }) {
                    throw BadRequest("Jede Adresse muss mit http:// oder https:// beginnen.")
                }
                val changed = a.copy(
                    epgUrls = urls,
                    epgProvider = if (b.has("provider")) b.getBoolean("provider") else a.epgProvider,
                    epgShift = if (b.has("shift")) b.getInt("shift").coerceIn(-Account.EPG_SHIFT_MAX, Account.EPG_SHIFT_MAX) else a.epgShift,
                )
                if (b.has("refreshHours")) AppSettings.refreshHours = b.getInt("refreshHours").coerceIn(0, 168)
                core.updateEpg(changed, reload = changed.epgUrls != a.epgUrls || changed.epgProvider != a.epgProvider)
                send(ex, 200, ok())
            }
            "epg/refresh" -> {
                Thread { core.refreshEpg(true) }.start()
                send(ex, 200, ok())
            }
            "settings" -> {
                val b = r.body()
                if (b.has("userAgent")) AppSettings.userAgent = b.getString("userAgent").trim()
                if (b.has("audioLanguage")) AppSettings.audioLanguage = b.getString("audioLanguage").trim().take(8)
                if (b.has("liveFormat")) core.liveFormat = if (b.getString("liveFormat") == "m3u8") "m3u8" else "ts"
                var restart = false
                if (b.has("port")) {
                    val p = b.getInt("port")
                    if (p !in 1024..65535) throw BadRequest("Port zwischen 1024 und 65535 wählen.")
                    if (p != config.port) {
                        config.port = p
                        restart = true
                    }
                }
                send(ex, 200, ok().put("restart", restart))
                if (restart) {
                    // Der Dienst startet den Server mit dem neuen Port neu
                    Thread {
                        Thread.sleep(1500)
                        Runtime.getRuntime().halt(RESTART_CODE)
                    }.start()
                }
            }
            "profile" -> {
                val id = r.body().optString("id")
                if (core.profiles().none { it.id == id }) throw BadRequest("Unbekanntes Profil")
                session.profile = id
                session.profileChosen = true
                send(ex, 200, state(session))
            }
            "update" -> send(ex, 200, updateState())
            "update/settings" -> {
                val u = update ?: throw BadRequest("Nicht verfügbar", 404)
                if (r.body().has("auto")) u.auto = r.body().getBoolean("auto")
                send(ex, 200, updateState())
            }
            "update/check" -> {
                val u = update ?: throw BadRequest("Nicht verfügbar", 404)
                Thread { u.check() }.start()
                Thread.sleep(300)
                send(ex, 200, updateState())
            }
            "update/install" -> {
                val u = update ?: throw BadRequest("Nicht verfügbar", 404)
                if (u.state != "ready") throw BadRequest("Kein geprüftes Update bereit.")
                send(ex, 200, ok())
                Thread {
                    Thread.sleep(1000)
                    u.install()
                }.start()
            }
            "sync/start" -> {
                sync?.stop()
                val host = SyncHost(core.context, "Klar TV Server")
                sync = host
                send(ex, 200, JSONObject().put("link", host.link.toUri()).put("qr", Qr.svg(host.link.toUri())))
            }
            "sync/state" -> {
                val host = sync
                val o = JSONObject()
                if (host != null) {
                    host.offer?.let { o.put("offer", it.device) }
                    host.done?.let { o.put("done", it) }
                    o.put("expired", host.expired)
                }
                send(ex, 200, o)
            }
            "sync/answer" -> {
                val host = sync ?: throw BadRequest("Kein Abgleich offen")
                val offer = host.offer ?: throw BadRequest("Keine Anfrage offen")
                val yes = r.body().optBoolean("ok")
                if (yes) {
                    SyncData.import(core.context, offer.data)
                    AppSettings.reload()
                    I18n.init(core.context)
                    core.reload()
                }
                offer.answer.complete(yes)
                send(ex, 200, ok())
            }
            "sync/stop" -> {
                sync?.stop()
                sync = null
                send(ex, 200, ok())
            }
            else -> throw BadRequest("Unbekannt", 404)
        }
    }

    @Volatile private var sync: SyncHost? = null

    private fun login(r: Request, s: Auth.Session) {
        val secure = if (https(r)) "; Secure" else ""
        r.ex.responseHeaders.add(
            "Set-Cookie",
            "$COOKIE=${s.token}; Path=/; Max-Age=${Auth.MAX_AGE_MS / 1000}; HttpOnly; SameSite=Strict$secure",
        )
        send(r.ex, 200, ok())
    }

    private fun notFound(ref: String) = when {
        ref.startsWith("CU:") -> "Diese Sendung bietet der Anbieter nicht zum Nachschauen an."
        ref.startsWith("CONT:") -> "Nicht mehr beim Anbieter vorhanden."
        else -> "Beim Anbieter nicht gefunden. Vielleicht wurde die Liste geändert; Einstellungen → Listen neu laden."
    }

    private fun kind(name: String) = runCatching { Kind.valueOf(name) }.getOrElse { throw BadRequest("Unbekannte Art") }

    private fun updateState(): JSONObject {
        val u = update ?: return JSONObject().put("available", false).put("version", appVersion)
        return JSONObject().put("available", true).put("version", appVersion).put("auto", u.auto).put("latest", u.latest)
            .put("state", u.state).put("error", u.error).put("lastCheck", u.lastCheck)
    }

    private fun state(session: Auth.Session): JSONObject {
        val a = core.active
        val lib = core.library(session.profile)
        val o = JSONObject()
            .put("version", appVersion)
            .put("user", auth.user)
            .put("ffmpeg", media.available)
            .put("active", a?.id ?: "")
            .put("accounts", JSONArray(core.accounts().map { acc ->
                JSONObject().put("id", acc.id).put("name", acc.name).put("type", acc.type.name).put("server", acc.server)
                    .put("username", acc.username).put("hasM3u", acc.m3uUrl.isNotEmpty()).put("epgUrls", acc.epgUrls.joinToString("\n"))
                    .put("epgProvider", acc.epgProvider).put("epgShift", acc.epgShift)
                    .put("hasPassword", acc.password.isNotEmpty())
            }))
            .put("hasPin", AppSettings.hasPin)
            .put("unlocked", session.unlocked)
            .put("userAgent", AppSettings.userAgent)
            .put("refreshHours", AppSettings.refreshHours)
            .put("audioLanguage", AppSettings.audioLanguage)
            .put("liveFormat", core.liveFormat)
            .put("epg", core.epgStatus)
            .put("port", config.port)
            .put("profile", session.profile)
            .put("chooseProfile", !session.profileChosen && core.profiles().size > 1)
            .put("profiles", JSONArray(core.profiles().map { p -> JSONObject().put("id", p.id).put("name", p.name).put("color", p.color) }))
        if (lib != null) {
            o.put("favorites", JSONArray(lib.favorites()))
            val watched = lib.watched()
            o.put("watched", JSONArray(watched.filter { it.startsWith("MOVIE:") }))
            // Serien: gesehene und alle Folgen
            val series = JSONObject()
            lib.seriesEpisodes().forEach { (k, eps) -> if (eps.isNotEmpty()) series.put(k, JSONArray().put(eps.count { it in watched }).put(eps.size)) }
            o.put("seriesSeen", series)
            o.put("recent", JSONArray(lib.recent()))
            o.put("continue", JSONArray(lib.continueList().map { c ->
                JSONObject().put("key", c.key).put("parent", c.parentKey).put("title", c.title).put("subtitle", c.subtitle ?: "")
                    .put("image", c.image ?: "").put("position", c.position).put("duration", c.duration)
            }))
        }
        return o
    }

    private fun catalogJson(c: Catalog): JSONObject {
        val cats = JSONArray()
        c.categories.forEach { cats.put(JSONObject().put("id", it.id).put("name", if (it.id == Catalog.ALL) "Alle" else it.name)) }
        val list = JSONArray()
        c.streams.forEach { s -> list.put(streamJson(s)) }
        return JSONObject().put("categories", cats).put("streams", list).put("locked", JSONArray(c.lockedIds.toList()))
    }

    private fun streamJson(s: Stream): JSONObject {
        val o = JSONObject().put("k", s.key).put("i", s.id).put("n", s.name).put("c", s.categoryId)
        s.logo?.takeIf { it.isNotBlank() }?.let { o.put("l", it) }
        if (s.number > 0) o.put("no", s.number)
        s.year?.takeIf { it.isNotBlank() }?.let { o.put("y", it) }
        s.rating?.takeIf { it.isNotBlank() && it != "0" }?.let { o.put("r", it) }
        if (s.added > 0) o.put("a", s.added)
        if (s.archiveDays > 0) o.put("ar", s.archiveDays)
        s.genre?.takeIf { it.isNotBlank() }?.let { o.put("g", it) }
        return o
    }

    private fun epgJson(e: EpgEntry) = JSONObject().put("t", e.title).put("d", e.description).put("s", e.start).put("e", e.end)

    // ---------- Bilder ----------

    private fun image(r: Request) {
        auth.session(r.cookie) ?: throw BadRequest("Bitte anmelden.", 401)
        val img = try {
            images.get(r.q("u"))
        } catch (e: IOException) {
            r.ex.sendResponseHeaders(404, -1)
            return
        }
        r.ex.responseHeaders["Content-Type"] = img.type
        r.ex.responseHeaders["Cache-Control"] = "private, max-age=604800"
        sendBytes(r.ex, 200, img.bytes)
    }

    // ---------- Abspielen ----------

    private fun play(r: Request) {
        val session = auth.session(r.cookie) ?: throw BadRequest("Bitte anmelden.", 401)
        if (!media.available) throw BadRequest("FFmpeg fehlt.", 500)
        val ref = r.q("ref")
        val t = core.resolve(ref, session.profile) ?: throw BadRequest(notFound(ref), 404)
        val probe = media.probe(t.url, t.live)
        val audio = media.pickAudio(probe, r.query["a"]?.toIntOrNull())
        // Zuletzt gesehen: bei Folgen die Serie
        r.query["recent"]?.takeIf { it.isNotBlank() }?.let { core.library(session.profile)?.addRecent(it) }
            ?: if (ref.startsWith("LIVE:") || ref.startsWith("MOVIE:")) core.library(session.profile)?.addRecent(ref) else Unit
        r.ex.responseHeaders["Content-Type"] = "video/mp2t"
        r.ex.responseHeaders["Cache-Control"] = "no-store"
        r.ex.sendResponseHeaders(200, 0)
        r.ex.responseBody.use { out ->
            media.stream(
                owner = session.token,
                url = t.url,
                live = t.live,
                probe = probe,
                audio = audio,
                hevc = r.query["hevc"] == "1",
                startMs = r.query["t"]?.toLongOrNull() ?: 0L,
                out = out,
            )
        }
    }

    companion object {
        const val COOKIE = "klartv"
        const val RESTART_CODE = 3
    }
}
