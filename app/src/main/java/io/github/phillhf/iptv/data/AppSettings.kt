package io.github.phillhf.iptv.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateMapOf
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Welche Engine abspielt. AUTO nimmt ExoPlayer und wechselt zu VLC, wenn ein Format nicht geht. */
enum class EngineChoice { AUTO, EXO, VLC }

/** Einstellungen der App. Änderungen sind sofort in der Oberfläche sichtbar. */
object AppSettings {
    private var prefs: SharedPreferences? = null
    private val state = mutableStateMapOf<String, Any>()

    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (p === prefs) return
        prefs = p
        state.clear()
        p.all.forEach { (k, v) -> if (v != null) state[k] = v }
    }

    private class Pref<T : Any>(private val default: T) : ReadWriteProperty<AppSettings, T> {
        @Suppress("UNCHECKED_CAST")
        override fun getValue(thisRef: AppSettings, property: KProperty<*>): T = (state[property.name] as? T) ?: default

        override fun setValue(thisRef: AppSettings, property: KProperty<*>, value: T) {
            state[property.name] = value
            val e = prefs?.edit() ?: return
            when (value) {
                is String -> e.putString(property.name, value)
                is Int -> e.putInt(property.name, value)
                is Boolean -> e.putBoolean(property.name, value)
                is Float -> e.putFloat(property.name, value)
                is Long -> e.putLong(property.name, value)
            }
            e.apply()
        }
    }

    private var engineName by Pref("AUTO")
    var engine: EngineChoice
        get() = runCatching { EngineChoice.valueOf(engineName) }.getOrDefault(EngineChoice.AUTO)
        set(value) {
            engineName = value.name
        }

    /** Bevorzugte Sprachen, leer = wie vom Stream vorgegeben. */
    var audioLanguage by Pref("de")
    var subtitleLanguage by Pref("de")
    var subtitlesOn by Pref(false)
    var subtitleSize by Pref(100)
    var subtitleColor by Pref("#FFFFFF")
    var subtitleBackground by Pref(50)

    var userAgent by Pref("")
    var theme by Pref("NIGHT")
    /** Sprache der App, leer = wie das Gerät. */
    var language by Pref("")

    /** Eigene Schlüssel des Nutzers für Zusatzdienste, leer = aus. */
    var tmdbKey by Pref("")
    var openSubtitlesKey by Pref("")

    // ---------- Kindersicherung ----------

    private var pinSalt by Pref("")
    private var pinHash by Pref("")
    private var pinFails by Pref(0)
    private var pinBlockedUntil by Pref(0L)

    val hasPin: Boolean get() = pinHash.isNotEmpty()

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        pinSalt = hex(salt)
        pinHash = hash(pin, salt)
        pinFails = 0
    }

    fun clearPin() {
        pinHash = ""
        pinSalt = ""
    }

    /** Sekunden, die nach zu vielen Fehlversuchen noch zu warten sind. */
    fun pinWaitSeconds(now: Long = System.currentTimeMillis()): Long =
        // Wer die Uhr zurückstellt, soll nicht ewig warten: höchstens eine Minute
        if (pinBlockedUntil > now) ((pinBlockedUntil - now) / 1000 + 1).coerceAtMost(60) else 0

    fun checkPin(pin: String): Boolean {
        if (!hasPin) return true
        if (pinWaitSeconds() > 0) return false
        val ok = MessageDigest.isEqual(hash(pin, unhex(pinSalt)).toByteArray(), pinHash.toByteArray())
        if (ok) {
            pinFails = 0
        } else {
            pinFails += 1
            if (pinFails >= 5) {
                pinFails = 0
                pinBlockedUntil = System.currentTimeMillis() + 60_000
            }
        }
        return ok
    }

    private fun hash(pin: String, salt: ByteArray): String {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 20_000, 256)
        return hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

/** Kategorien, die nach Erwachseneninhalten klingen; Vorschlag für die Sperre. */
fun looksAdult(categoryName: String): Boolean =
    Regex("(^|[^a-z0-9])(xxx|adults?|18\\s*\\+|\\+\\s*18|erotik|erotic|porn|porno|for adults|fsk\\s*18|ab 18)", RegexOption.IGNORE_CASE)
        .containsMatchIn(categoryName)
