package io.github.phillhf.iptv.data

import io.github.phillhf.iptv.t
import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object Http {
    private const val DEFAULT_AGENT = "KlarTV/1.0 (Android)"

    /** Kennung gegenüber dem Anbieter; manche Anbieter lassen nur bestimmte Player zu. */
    val USER_AGENT: String get() = AppSettings.userAgent.trim().ifEmpty { DEFAULT_AGENT }

    /** Lädt Text. Folgt Weiterleitungen auch von http zu https, was HttpURLConnection allein nicht tut. */
    fun get(address: String, headers: Map<String, String> = emptyMap()): String {
        val conn = open(address, headers)
        try {
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** Öffnet eine Verbindung und folgt Weiterleitungen. Der Aufrufer schließt sie. */
    fun open(address: String, headers: Map<String, String> = emptyMap()): HttpURLConnection {
        var url = URL(address)
        repeat(6) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            var keep = false
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException(t("Weiterleitung ohne Ziel."))
                    url = URL(url, location)
                    return@repeat
                }
                if (code == 401 || code == 403) throw IOException(t("Zugang verweigert (Fehler %1). Zugangsdaten prüfen.", code))
                if (code !in 200..299) throw IOException(t("Der Server meldet Fehler %1.", code))
                keep = true
                return conn
            } finally {
                if (!keep) conn.disconnect()
            }
        }
        throw IOException(t("Zu viele Weiterleitungen."))
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is UnknownHostException -> t("Server nicht gefunden. Adresse und Internetverbindung prüfen.")
    is SocketTimeoutException -> t("Der Server antwortet nicht (Zeitüberschreitung).")
    is ConnectException -> t("Keine Verbindung zum Server.")
    is SSLException -> t("Sichere Verbindung fehlgeschlagen.")
    is JSONException -> t("Unerwartete Antwort vom Server.")
    is OutOfMemoryError -> t("Die Liste ist zu groß für dieses Gerät.")
    else -> e.message ?: e.javaClass.simpleName
}
