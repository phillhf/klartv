package io.github.phillhf.iptv

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.transfer.Transfers
import org.json.JSONObject
import java.util.Locale

class KlarApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        I18n.init(this)
        Transfers.init(this)
    }
}

/**
 * Übersetzungen. Schlüssel ist der deutsche Text; die Tabellen liegen unter assets/lang. Fehlt ein Text,
 * gilt Englisch, sonst Deutsch. Ein Test prüft, dass die Tabellen vollständig sind.
 */
object I18n {
    val languages = listOf(
        "de" to "Deutsch", "en" to "English", "fr" to "Français", "es" to "Español", "it" to "Italiano", "tr" to "Türkçe",
    )

    private var table by mutableStateOf<Map<String, String>>(emptyMap())

    fun init(context: Context) {
        val wanted = AppSettings.language.ifEmpty { Locale.getDefault().language }
        val lang = if (languages.any { it.first == wanted }) wanted else "en"
        table = when (lang) {
            "de" -> emptyMap()
            "en" -> load(context, "en")
            else -> load(context, "en") + load(context, lang)
        }
    }

    private fun load(context: Context, lang: String): Map<String, String> = runCatching {
        val o = JSONObject(context.assets.open("lang/$lang.json").bufferedReader().use { it.readText() })
        o.keys().asSequence().associateWith { o.getString(it) }
    }.getOrDefault(emptyMap())

    val german: Boolean get() = table.isEmpty()

    fun get(text: String): String = table[text] ?: text
}

/** Übersetzt einen deutschen Text; %1, %2 … werden durch die Werte ersetzt. */
fun t(text: String, vararg values: Any?): String {
    var s = I18n.get(text)
    values.forEachIndexed { i, v -> s = s.replace("%${i + 1}", v.toString()) }
    return s
}
