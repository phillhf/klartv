package io.github.phillhf.iptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.openAsset
import org.json.JSONObject
import java.util.Locale

/**
 * Übersetzungen. Schlüssel ist der deutsche Text; die Tabellen liegen unter assets/lang. Fehlt ein Text,
 * gilt Englisch, sonst Deutsch. Ein Test prüft, dass die Tabellen vollständig sind.
 */
object I18n {
    val languages = listOf(
        "de" to "Deutsch", "en" to "English", "fr" to "Français", "es" to "Español", "it" to "Italiano", "tr" to "Türkçe",
        "pt" to "Português", "nl" to "Nederlands", "pl" to "Polski", "ro" to "Română", "sq" to "Shqip", "ru" to "Русский", "ar" to "العربية",
    )

    /** Sprachen, die von rechts nach links geschrieben werden. */
    private val rightToLeft = setOf("ar")

    private var table by mutableStateOf<Map<String, String>>(emptyMap())

    /** Die gewählte Sprache läuft von rechts nach links (Arabisch); die Oberfläche spiegelt sich dann. */
    var rtl by mutableStateOf(false)
        private set

    fun init(context: AppContext) {
        val wanted = AppSettings.language.ifEmpty { Locale.getDefault().language }
        val lang = if (languages.any { it.first == wanted }) wanted else "en"
        rtl = lang in rightToLeft
        table = when (lang) {
            "de" -> emptyMap()
            "en" -> load(context, "en")
            else -> load(context, "en") + load(context, lang)
        }
    }

    private fun load(context: AppContext, lang: String): Map<String, String> = runCatching {
        val o = JSONObject(context.openAsset("lang/$lang.json").bufferedReader().use { it.readText() })
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
