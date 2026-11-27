package io.github.phillhf.iptv.sync

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.phillhf.iptv.data.AccountStore
import io.github.phillhf.iptv.data.Library
import io.github.phillhf.iptv.data.ProfileStore
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.openPrefs
import io.github.phillhf.iptv.platform.Prefs
import io.github.phillhf.iptv.t
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Abgleich zwischen zwei Geräten im selben WLAN: Das eine zeigt einen QR-Code, das andere scannt ihn und schickt
 * seinen Stand hin oder holt den von dort. Alles geht verschlüsselt mit dem Schlüssel aus dem QR-Code.
 */
object SyncData {
    /** Gehört zum Gerät und bleibt beim Abgleich, wie es ist. */
    private val deviceOnly = setOf("matchFrameRate", "storeTarget", "diagnostics", "autostart", "lastChannel")

    private fun names(context: AppContext): List<String> {
        val profiles = ProfileStore(context).all()
        return listOf("settings", "accounts", "profiles") +
            AccountStore(context).all().flatMap { a -> profiles.map { p -> Library.prefsName(a.id, p.id) } }
    }

    private fun encode(p: Prefs, skip: Set<String> = emptySet()): JSONObject {
        val o = JSONObject()
        p.all.forEach { (k, v) ->
            if (k in skip || v == null) return@forEach
            val a = JSONArray()
            when (v) {
                is Int -> a.put("i").put(v)
                is Long -> a.put("l").put(v)
                is Float -> a.put("f").put(v.toDouble())
                is Boolean -> a.put("b").put(v)
                is Set<*> -> {
                    a.put("t")
                    v.forEach { a.put(it.toString()) }
                }
                else -> a.put("s").put(v.toString())
            }
            o.put(k, a)
        }
        return o
    }

    fun export(context: AppContext, device: String): ByteArray {
        val prefs = JSONObject()
        names(context).forEach { n -> prefs.put(n, encode(context.openPrefs(n), if (n == "settings") deviceOnly else emptySet())) }
        return JSONObject().put("version", 1).put("device", device).put("time", System.currentTimeMillis()).put("prefs", prefs)
            .toString().toByteArray()
    }

    fun device(data: ByteArray): String = runCatching { JSONObject(String(data)).optString("device") }.getOrDefault("")

    /** Ersetzt Anbieter, Favoriten, Verlauf und Einstellungen durch den mitgebrachten Stand. */
    fun import(context: AppContext, data: ByteArray) {
        val root = JSONObject(String(data))
        if (root.optInt("version") != 1) throw IOException(t("Die andere App ist zu alt oder zu neu. Bitte beide aktualisieren."))
        val prefs = root.getJSONObject("prefs")
        val before = names(context)
        val incoming = prefs.keys().asSequence().toList().filter { it == "settings" || it == "accounts" || it == "profiles" || it.startsWith("library_") }
        for (n in incoming) {
            val p = context.openPrefs(n)
            val keep = if (n == "settings") p.all.filterKeys { it in deviceOnly } else emptyMap()
            val o = prefs.getJSONObject(n)
            val e = p.edit().clear()
            fun put(k: String, v: Any?) {
                when (v) {
                    is Int -> e.putInt(k, v)
                    is Long -> e.putLong(k, v)
                    is Float -> e.putFloat(k, v)
                    is Boolean -> e.putBoolean(k, v)
                    is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
                    is String -> e.putString(k, v)
                }
            }
            keep.forEach { (k, v) -> put(k, v) }
            o.keys().asSequence().toList().forEach { k ->
                if (n == "settings" && k in deviceOnly) return@forEach
                val a = o.getJSONArray(k)
                put(
                    k,
                    when (a.getString(0)) {
                        "i" -> a.getInt(1)
                        "l" -> a.getLong(1)
                        "f" -> a.getDouble(1).toFloat()
                        "b" -> a.getBoolean(1)
                        "t" -> (1 until a.length()).map { a.getString(it) }.toSet()
                        else -> a.getString(1)
                    },
                )
            }
            e.commit()
        }
        // Verlauf von Anbietern, die es dort nicht gibt, fällt weg
        (before - incoming.toSet()).filter { it.startsWith("library_") }.forEach { n -> context.openPrefs(n).edit().clear().commit() }
    }
}

/** Verschlüsselt mit dem Schlüssel aus dem QR-Code (AES-GCM). */
object SyncCrypto {
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun encrypt(key: ByteArray, plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return iv + c.doFinal(plain)
    }

    fun decrypt(key: ByteArray, data: ByteArray): ByteArray {
        if (data.size < 28) throw IOException(t("Unvollständige Daten."))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
        return try {
            c.doFinal(data, 12, data.size - 12)
        } catch (e: Exception) {
            throw IOException(t("Der Code passt nicht. Bitte neu scannen."))
        }
    }
}

/** Was im QR-Code steht: wo das andere Gerät zu erreichen ist und der Schlüssel. */
data class SyncLink(val hosts: List<String>, val port: Int, val token: String, val key: ByteArray, val device: String) {
    fun toUri(): String {
        val k = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
        val n = URLEncoder.encode(device, "UTF-8")
        return "klartv://sync?h=${hosts.joinToString(",")}&p=$port&t=$token&k=$k&n=$n"
    }

    override fun equals(other: Any?) = other is SyncLink && other.toUri() == toUri()

    override fun hashCode() = toUri().hashCode()

    companion object {
        fun parse(text: String): SyncLink? = runCatching {
            val u = URI(text.trim())
            if (u.scheme != "klartv" || u.host != "sync") return null
            val q = u.rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            SyncLink(
                hosts = q.getValue("h").split(',').filter { it.isNotBlank() },
                port = q.getValue("p").toInt(),
                token = q.getValue("t").also { require(it.matches(Regex("[0-9a-f]{32}"))) },
                key = Base64.getUrlDecoder().decode(q.getValue("k")).also { require(it.size == 32) },
                device = q["n"].orEmpty(),
            )
        }.getOrNull()
    }
}

/** Wartet auf das andere Gerät. Gilt fünf Minuten und für einen Vorgang. */
class SyncHost(private val context: AppContext, private val device: String) {
    /** Das andere Gerät will seinen Stand schicken; die Oberfläche fragt nach. */
    class Offer(val device: String, val data: ByteArray, val answer: CompletableFuture<Boolean>)

    var offer by mutableStateOf<Offer?>(null)
        private set

    /** Meldung für die Oberfläche, wenn etwas passiert ist. */
    var done by mutableStateOf<String?>(null)
        private set

    private val key = SyncCrypto.newKey()
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val socket = ServerSocket(0, 5, InetAddress.getByName("0.0.0.0"))
    private val until = System.currentTimeMillis() + 5 * 60_000
    @Volatile private var used = false

    val link = SyncLink(localAddresses(), socket.localPort, token, key, device)

    init {
        thread(name = "abgleich", isDaemon = true) {
            while (!socket.isClosed) {
                val c = runCatching { socket.accept() }.getOrNull() ?: break
                thread(isDaemon = true) { runCatching { serve(c) } }
            }
        }
    }

    val expired: Boolean get() = System.currentTimeMillis() > until

    fun stop() {
        runCatching { socket.close() }
        offer?.answer?.complete(false)
    }

    private fun serve(c: Socket) = c.use {
        c.soTimeout = 30_000
        val input = c.getInputStream()
        val head = readHead(input)
        val request = head.firstOrNull().orEmpty().split(' ')
        val length = head.firstNotNullOfOrNull { l -> l.takeIf { it.startsWith("Content-Length:", true) }?.substringAfter(':')?.trim()?.toIntOrNull() } ?: 0
        val out = c.getOutputStream()
        fun answer(code: String, body: ByteArray = ByteArray(0)) {
            out.write("HTTP/1.1 $code\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(body)
            out.flush()
        }
        val path = request.getOrNull(1).orEmpty()
        if (path != "/$token/push" && path != "/$token/pull") return answer("404 Not Found")
        if (expired || used || length > 30_000_000) return answer("410 Gone")
        // Ohne Anbieter nichts herausgeben: Das andere Gerät würde sonst seinen Stand durch einen leeren ersetzen
        if (path.endsWith("/pull") && AccountStore(context).all().isEmpty()) return answer("409 Conflict")
        used = true
        if (path.endsWith("/pull")) {
            answer("200 OK", SyncCrypto.encrypt(key, SyncData.export(context, device)))
            done = t("Der Stand wurde an das andere Gerät gegeben.")
            return
        }
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        val data = try {
            SyncCrypto.decrypt(key, body)
        } catch (e: IOException) {
            used = false
            return answer("400 Bad Request")
        }
        val o = Offer(SyncData.device(data), data, CompletableFuture())
        offer = o
        // Das andere Gerät wartet, bis hier jemand bestätigt
        c.soTimeout = 0
        val ok = runCatching { o.answer.get(3, TimeUnit.MINUTES) }.getOrDefault(false)
        offer = null
        if (ok) {
            done = t("Stand von „%1“ übernommen.", o.device)
            answer("200 OK", "ok".toByteArray())
        } else {
            used = false
            answer("403 Forbidden", "nein".toByteArray())
        }
    }

    companion object {
        private fun readHead(input: InputStream): List<String> {
            val lines = ArrayList<String>()
            val line = ByteArrayOutputStream()
            var last = -1
            while (true) {
                val b = input.read()
                if (b < 0) break
                if (b == '\n'.code) {
                    val s = line.toString(Charsets.ISO_8859_1.name()).trimEnd('\r')
                    if (s.isEmpty()) break
                    lines += s
                    line.reset()
                } else {
                    line.write(b)
                }
                if (line.size() > 8192) break
                last = b
            }
            return lines
        }

        /** Adressen im eigenen Netz, WLAN und Kabel zuerst. */
        fun localAddresses(): List<String> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { n -> if (n.name.startsWith("wlan") || n.name.startsWith("eth") || n.name.startsWith("en")) 0 else 1 }
                .flatMap { n -> n.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress }.map { it.hostAddress!! } }
                .distinct()
        }.getOrDefault(emptyList()).ifEmpty { listOf("127.0.0.1") }
    }
}

/** Die Seite, die den QR-Code gescannt hat. */
object SyncClient {
    private fun open(link: SyncLink, path: String, prepare: (HttpURLConnection) -> Unit = {}): HttpURLConnection {
        var last: Exception? = null
        for (h in link.hosts) {
            try {
                val c = URL("http://$h:${link.port}/${link.token}/$path").openConnection() as HttpURLConnection
                c.connectTimeout = 4_000
                c.readTimeout = 200_000
                prepare(c)
                c.connect()
                return c
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException(t("Das andere Gerät ist nicht erreichbar. Sind beide im selben WLAN?"), last)
    }

    /** Schickt den eigenen Stand. true, wenn das andere Gerät ihn übernommen hat. */
    fun push(context: AppContext, link: SyncLink, device: String): Boolean {
        val body = SyncCrypto.encrypt(link.key, SyncData.export(context, device))
        val c = open(link, "push") {
            it.requestMethod = "POST"
            it.doOutput = true
            it.setFixedLengthStreamingMode(body.size)
        }
        try {
            c.outputStream.use { it.write(body) }
            return when (c.responseCode) {
                200 -> true
                403 -> false
                410 -> throw IOException(t("Der Code ist abgelaufen. Bitte neu anzeigen lassen."))
                else -> throw IOException(t("Das andere Gerät meldet Fehler %1.", c.responseCode))
            }
        } finally {
            c.disconnect()
        }
    }

    /** Holt den Stand des anderen Geräts und übernimmt ihn hier. Gibt dessen Namen zurück. */
    fun pull(context: AppContext, link: SyncLink): String {
        val c = open(link, "pull")
        try {
            if (c.responseCode == 410) throw IOException(t("Der Code ist abgelaufen. Bitte neu anzeigen lassen."))
            if (c.responseCode == 409) throw IOException(t("Auf dem anderen Gerät ist noch kein Anbieter eingerichtet. Bitte von hier dorthin senden."))
            if (c.responseCode != 200) throw IOException(t("Das andere Gerät meldet Fehler %1.", c.responseCode))
            val data = SyncCrypto.decrypt(link.key, c.inputStream.use { it.readBytes() })
            SyncData.import(context, data)
            return SyncData.device(data)
        } finally {
            c.disconnect()
        }
    }
}
