package io.github.phillhf.iptv.update

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * Updates für Windows-App und Server aus den GitHub-Releases. Installiert wird nur, was mit dem
 * Release-Schlüssel signiert ist (derselbe wie für die Android-App); die Signatur macht release.yml.
 */
object Updater {
    const val RELEASES = "https://api.github.com/repos/phillhf/klartv/releases/latest"

    /** Öffentlicher Teil des Release-Schlüssels (RSA 4096, Zertifikat SHA-256 1eb4188e…c3fd). */
    private const val PUBLIC_KEY =
        "MIICIjANBgkqhkiG9w0BAQEFAAOCAg8AMIICCgKCAgEAsaphQJL2oj1Vx8Oxc6Ih4eRpfIc6UYsLV5onRzsCTllQwrkVdxR3BQPN" +
            "wWaGOFlLjq0iiYXx6VOr+qM8fH5zJmpmczs37mHK0KiOzl0No+9FfiLBwQw10Nc40z/czbMp9+TYiSLuYroyMsrM/VvtKBTwKCjv" +
            "EyupMSFd6Qbg+dOtfwgEtaKqNNNCT0nI06di5aj4weA5PBz1VwFuabIb3JvjfhlTSee3b+k/et1rR7BZpiKJBSKhvfGtjRaSnkDD" +
            "S7e0QHAP2vEZfEPzY3vI88A/ZuU7vVWvbjQgRaQ65tZjZGRMYkpkchEbcbZnPH6YXKmP3+RQd9NVkNJykYKtToHE6O2S/GvJq5uo" +
            "wJRMeHo+leMYiAvEag9xyjr5OX4X8ipFyYO21nVyw14dsoXinDW32kWEL9Y1lqO0+gtqqTm6h1UjpS4g5rr/xh6PRYFVE7eoLSWa" +
            "iLScRMqVMPRCZo8YsxO0Do6M2fBn1lQQgF5Y96oULQdFMVC3MJJOc7urzJGLseQ886Uzq8Kg2mbQl+5lkZDyj3U62iprbcXDRSKw" +
            "vJMiud6vHf2+nfmBqUX5+eOoaHQUgBChN9NoM+0b696ITNhzZPauZwTHt+k1pbrlO9/Bn5j/obOLZc20joM3ZaYcVYxiJuDmZ3V+" +
            "e0TeP/tmoDpxgFk2jz/D8jS1TRkCAwEAAQ=="

    val publicKey: PublicKey by lazy {
        KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)))
    }

    /** Nur von hier wird geladen; GitHub leitet Downloads auf seine Ablage weiter. */
    private val hosts = setOf("api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

    data class Release(val version: String, val file: String, val url: String, val sigUrl: String, val size: Long, val sha256: String?)

    /** Neueste Version mit der Datei „KlarTV-<Version>-<suffix>“, z. B. suffix = "Windows-Setup.exe". */
    fun latest(suffix: String, api: String = RELEASES): Release? {
        val o = JSONObject(String(fetch(api, 1_000_000), Charsets.UTF_8))
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v")
        if (!Regex("""\d+(\.\d+){1,3}""").matches(version)) return null
        val assets = o.optJSONArray("assets") ?: return null
        val name = "KlarTV-$version-$suffix"
        var exe: JSONObject? = null
        var sig: JSONObject? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.optString("name")) {
                name -> exe = a
                "$name.sig" -> sig = a
            }
        }
        if (exe == null || sig == null) return null
        return Release(
            version, name, exe.getString("browser_download_url"), sig.getString("browser_download_url"), exe.optLong("size"),
            exe.optString("digest").removePrefix("sha256:").takeIf { it.length == 64 },
        )
    }

    /** Ist [candidate] neuer als [current]? Vergleicht Zahl für Zahl, 0.10.0 ist neuer als 0.9.3. */
    fun newer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * Lädt das Setup nach [dir] und prüft die Signatur. Gibt die geprüfte Datei zurück;
     * passt etwas nicht, wird sie gelöscht und es gibt eine IOException.
     */
    fun download(r: Release, dir: File, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        dir.mkdirs()
        val sig = Base64.getDecoder().decode(String(fetch(r.sigUrl, 10_000), Charsets.UTF_8).trim())
        val target = File(dir, r.file)
        val part = File(dir, r.file + ".part")
        part.delete()
        try {
            open(r.url).let { conn ->
                try {
                    val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
                    if (total > 1_000_000_000) throw IOException("Update zu groß")
                    conn.inputStream.use { input ->
                        part.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                if (done > 1_000_000_000) throw IOException("Update zu groß")
                                progress(done, total)
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            }
            if (r.sha256 != null && sha256(part) != r.sha256) throw IOException("Update beschädigt (Prüfsumme)")
            if (!verify(part, sig)) throw IOException("Update nicht echt (Signatur passt nicht)")
            target.delete()
            if (!part.renameTo(target)) throw IOException("Update ließ sich nicht ablegen")
            return target
        } catch (e: Exception) {
            part.delete()
            throw e
        }
    }

    fun verify(file: File, signature: ByteArray, key: PublicKey = publicKey): Boolean = runCatching {
        val s = Signature.getInstance("SHA256withRSA")
        s.initVerify(key)
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                s.update(buf, 0, n)
            }
        }
        s.verify(signature)
    }.getOrDefault(false)

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetch(address: String, limit: Int): ByteArray {
        val conn = open(address)
        try {
            val bytes = conn.inputStream.use { it.readNBytes(limit + 1) }
            if (bytes.size > limit) throw IOException("Antwort zu groß")
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    /** Nur https und nur GitHub, auch nach Weiterleitungen. */
    private fun open(address: String): HttpURLConnection {
        var uri = URI(address)
        repeat(6) {
            if (uri.scheme != "https" || uri.host !in hosts) throw IOException("Update-Adresse nicht erlaubt: ${uri.host}")
            val conn = uri.toURL().openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "KlarTV-Updater")
            conn.setRequestProperty("Accept", if (uri.host == "api.github.com" && "/releases/" in uri.path && "/assets/" !in uri.path) "application/vnd.github+json" else "application/octet-stream")
            val code = conn.responseCode
            if (code in 300..399) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                uri = uri.resolve(location ?: throw IOException("Weiterleitung ohne Ziel"))
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                throw IOException("GitHub meldet Fehler $code")
            }
            return conn
        }
        throw IOException("Zu viele Weiterleitungen")
    }
}
