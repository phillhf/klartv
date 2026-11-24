package io.github.phillhf.iptv.server

import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.openPrefs
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Anmeldung an der Weboberfläche. Ein Benutzer mit Passwort; Sitzungen gelten 30 Tage ab der letzten Nutzung
 * und überstehen einen Neustart des Dienstes. Nach Fehlversuchen wird die Anmeldung kurz gesperrt.
 */
class Auth(context: AppContext) {
    private val prefs = context.openPrefs("server")
    private val random = SecureRandom()

    class Session(val token: String, var lastUsed: Long, @Volatile var unlocked: Boolean = false)

    private val sessions = ConcurrentHashMap<String, Session>()

    init {
        runCatching {
            val o = JSONObject(prefs.getString("sessions", "{}") ?: "{}")
            o.keys().forEach { k -> sessions[k] = Session(k, o.getLong(k)) }
        }
        dropOld()
    }

    val user: String get() = prefs.getString("user", "").orEmpty()
    val isSetUp: Boolean get() = prefs.getString("hash", "").orEmpty().isNotEmpty()

    fun setUp(user: String, password: String) {
        require(user.isNotBlank()) { "Bitte einen Benutzernamen angeben." }
        require(password.length >= 8) { "Das Passwort braucht mindestens 8 Zeichen." }
        val salt = ByteArray(16).also { random.nextBytes(it) }
        prefs.edit()
            .putString("user", user.trim())
            .putString("salt", hex(salt))
            .putString("hash", hash(password, salt))
            .apply()
    }

    fun check(user: String, password: String): Boolean {
        if (!isSetUp) return false
        val salt = unhex(prefs.getString("salt", "").orEmpty())
        val ok = MessageDigest.isEqual(hash(password, salt).toByteArray(), prefs.getString("hash", "").orEmpty().toByteArray())
        return ok && user.trim().equals(this.user, ignoreCase = true)
    }

    // ---------- Sperre nach Fehlversuchen ----------

    private class Fails(var count: Int, var until: Long)

    private val fails = ConcurrentHashMap<String, Fails>()
    private val recentFails = ArrayDeque<Long>()

    /** Sekunden, die [client] noch warten muss; 0 = darf es versuchen. */
    @Synchronized
    fun waitSeconds(client: String, now: Long = System.currentTimeMillis()): Long {
        // Viele Fehlversuche von überall: alle zehn Minuten warten lassen
        while (recentFails.isNotEmpty() && now - recentFails.first() > 10 * 60_000L) recentFails.removeFirst()
        val global = if (recentFails.size >= 30) recentFails.first() + 10 * 60_000L else 0L
        val local = fails[client]?.until ?: 0L
        val until = maxOf(global, local)
        return if (until > now) (until - now) / 1000 + 1 else 0
    }

    @Synchronized
    fun failed(client: String, now: Long = System.currentTimeMillis()) {
        recentFails.addLast(now)
        val f = fails.getOrPut(client) { Fails(0, 0) }
        f.count++
        // Ab dem fünften Fehlversuch: 1, 2, 4 … Minuten, höchstens eine Stunde
        if (f.count >= 5) f.until = now + minOf(60L, 1L shl (f.count - 5).coerceAtMost(6)) * 60_000L
    }

    @Synchronized
    fun succeeded(client: String) {
        fails.remove(client)
    }

    // ---------- Sitzungen ----------

    fun newSession(): Session {
        val token = ByteArray(32).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        return Session(token, System.currentTimeMillis()).also {
            sessions[token] = it
            saveSessions()
        }
    }

    fun session(token: String?): Session? {
        if (token.isNullOrEmpty()) return null
        val s = sessions[token] ?: return null
        val now = System.currentTimeMillis()
        if (now - s.lastUsed > MAX_AGE_MS) {
            end(token)
            return null
        }
        // Nicht bei jeder Anfrage auf die Platte schreiben
        if (now - s.lastUsed > 3_600_000L) {
            s.lastUsed = now
            saveSessions()
        }
        return s
    }

    fun end(token: String) {
        sessions.remove(token)
        saveSessions()
    }

    /** Nach einem neuen Passwort: alle anderen Geräte abmelden. */
    fun endOthers(keep: String) {
        sessions.keys.filter { it != keep }.forEach { sessions.remove(it) }
        saveSessions()
    }

    private fun dropOld() {
        val now = System.currentTimeMillis()
        sessions.values.filter { now - it.lastUsed > MAX_AGE_MS }.forEach { sessions.remove(it.token) }
    }

    @Synchronized
    private fun saveSessions() {
        dropOld()
        val o = JSONObject()
        sessions.values.sortedByDescending { it.lastUsed }.take(50).forEach { o.put(it.token, it.lastUsed) }
        prefs.edit().putString("sessions", o.toString()).apply()
    }

    private fun hash(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        return hex(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    companion object {
        const val MAX_AGE_MS = 30L * 86_400_000L
    }
}
