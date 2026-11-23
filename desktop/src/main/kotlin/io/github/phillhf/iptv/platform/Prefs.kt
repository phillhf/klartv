package io.github.phillhf.iptv.platform

import com.sun.jna.platform.win32.Crypt32Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Einstellungsdatei mit den Methoden von Androids SharedPreferences. Der Inhalt ist mit dem Windows-Konto
 * verschlüsselt (DPAPI), denn darin stehen auch Zugangsdaten.
 */
class Prefs(private val file: File) {
    private val values = LinkedHashMap<String, Any>()

    init {
        runCatching { SecureFile.read(file)?.let { load(JSONObject(it)) } }
    }

    private fun load(o: JSONObject) {
        o.keySet().forEach { k ->
            val a = o.getJSONArray(k)
            values[k] = when (a.getString(0)) {
                "i" -> a.getInt(1)
                "l" -> a.getLong(1)
                "f" -> a.getDouble(1).toFloat()
                "b" -> a.getBoolean(1)
                "t" -> (1 until a.length()).map { a.getString(it) }.toSet()
                else -> a.getString(1)
            }
        }
    }

    val all: Map<String, *> get() = synchronized(values) { LinkedHashMap(values) }

    private inline fun <reified T> get(key: String, default: T): T = synchronized(values) { values[key] as? T ?: default }

    fun getString(key: String, default: String?): String? = get<Any?>(key, null) as? String ?: default
    fun getInt(key: String, default: Int): Int = get(key, default)
    fun getLong(key: String, default: Long): Long = get(key, default)
    fun getBoolean(key: String, default: Boolean): Boolean = get(key, default)

    @Suppress("UNCHECKED_CAST")
    fun getStringSet(key: String, default: Set<String>?): Set<String>? = synchronized(values) { values[key] as? Set<String> } ?: default

    fun edit() = Editor()

    inner class Editor {
        private val changes = LinkedHashMap<String, Any?>()

        private fun set(key: String, value: Any?): Editor {
            changes[key] = value
            return this
        }

        fun putString(key: String, value: String?) = set(key, value)
        fun putInt(key: String, value: Int) = set(key, value)
        fun putLong(key: String, value: Long) = set(key, value)
        fun putFloat(key: String, value: Float) = set(key, value)
        fun putBoolean(key: String, value: Boolean) = set(key, value)
        fun putStringSet(key: String, value: Set<String>?) = set(key, value?.toSet())
        fun remove(key: String) = set(key, null)

        private var wipe = false

        fun clear(): Editor {
            wipe = true
            return this
        }

        fun commit(): Boolean {
            apply()
            return true
        }

        fun apply() {
            synchronized(values) {
                if (wipe) values.clear()
                changes.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v }
                val o = JSONObject()
                values.forEach { (k, v) ->
                    val a = JSONArray()
                    when (v) {
                        is Int -> a.put("i").put(v)
                        is Long -> a.put("l").put(v)
                        is Float -> a.put("f").put(v.toDouble())
                        is Boolean -> a.put("b").put(v)
                        is Set<*> -> {
                            a.put("t")
                            v.forEach { a.put(it) }
                        }
                        else -> a.put("s").put(v.toString())
                    }
                    o.put(k, a)
                }
                SecureFile.write(file, o.toString())
            }
        }
    }
}

/** Textdateien, die nur das eigene Windows-Konto lesen kann. */
object SecureFile {
    fun read(file: File): String? {
        if (!file.exists()) return null
        return try {
            String(Crypt32Util.cryptUnprotectData(file.readBytes()))
        } catch (e: RuntimeException) {
            // Von einem anderen Konto oder beschädigt: nicht überschreiben
            runCatching { Files.move(file.toPath(), File(file.path + ".alt").toPath(), StandardCopyOption.REPLACE_EXISTING) }
            null
        }
    }

    fun write(file: File, text: String) {
        val tmp = File(file.path + ".tmp")
        tmp.writeBytes(Crypt32Util.cryptProtectData(text.toByteArray()))
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
