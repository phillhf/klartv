package io.github.phillhf.iptv.data

import org.json.JSONException
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

object Http {
    const val USER_AGENT = "KlarTV/1.0 (Android)"

    /** Lädt Text. Folgt Weiterleitungen auch von http zu https, was HttpURLConnection allein nicht tut. */
    fun get(address: String): String {
        var url = URL(address)
        repeat(6) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", USER_AGENT)
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel.")
                    url = URL(url, location)
                    return@repeat
                }
                if (code == 401 || code == 403) throw IOException("Zugang verweigert (Fehler $code). Zugangsdaten prüfen.")
                if (code !in 200..299) throw IOException("Der Server meldet Fehler $code.")
                return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
        throw IOException("Zu viele Weiterleitungen.")
    }
}

fun friendlyError(e: Throwable): String = when (e) {
    is UnknownHostException -> "Server nicht gefunden. Adresse und Internetverbindung prüfen."
    is SocketTimeoutException -> "Der Server antwortet nicht (Zeitüberschreitung)."
    is ConnectException -> "Keine Verbindung zum Server."
    is SSLException -> "Sichere Verbindung fehlgeschlagen."
    is JSONException -> "Unerwartete Antwort vom Server."
    is OutOfMemoryError -> "Die Liste ist zu groß für dieses Gerät."
    else -> e.message ?: e.javaClass.simpleName
}
