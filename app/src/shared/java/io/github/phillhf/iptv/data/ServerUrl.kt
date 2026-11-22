package io.github.phillhf.iptv.data

import java.net.URLEncoder
import java.util.Base64

/** Macht aus Eingaben wie „example.com:8080/“ oder einer kompletten get.php-Adresse die Basisadresse. */
fun normalizeServer(input: String): String {
    var s = input.trim()
    if (s.isEmpty()) return s
    if (!s.contains("://")) s = "http://$s"
    val cut = listOf("/player_api.php", "/get.php", "/xmltv.php").map { s.indexOf(it) }.filter { it >= 0 }.minOrNull()
    if (cut != null) s = s.substring(0, cut)
    return s.trimEnd('/')
}

fun queryPart(value: String): String = URLEncoder.encode(value, "UTF-8")

fun pathPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

/** Xtream liefert EPG-Texte meist Base64-kodiert, manche Server aber im Klartext. */
fun decodeMaybeBase64(s: String): String {
    if (s.isBlank()) return ""
    val decoded = try {
        String(Base64.getDecoder().decode(s.trim()), Charsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        return s
    }
    // Kurze Klartexte wie „News“ sind zufällig gültiges Base64; dann kommt Zeichensalat heraus
    val garbled = decoded.any { it == '�' || (it.isISOControl() && it != '\n' && it != '\r' && it != '\t') }
    return if (garbled) s else decoded
}
