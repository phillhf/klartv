package io.github.phillhf.iptv.data

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt

/** Eigene Ordnung der Listen: ausgeblendete und umbenannte Sender, ausgeblendete und umsortierte Gruppen. */
data class Custom(
    val hiddenStreams: Set<String> = emptySet(),
    val names: Map<String, String> = emptyMap(),
    /** Gruppen als „ART:ID“. */
    val hiddenGroups: Set<String> = emptySet(),
    val groupOrder: Map<Kind, List<String>> = emptyMap(),
    /** Reihenfolge der Sender (Schlüssel), leer = wie vom Anbieter. */
    val streamOrder: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = hiddenStreams.isEmpty() && names.isEmpty() && hiddenGroups.isEmpty() && groupOrder.isEmpty() && streamOrder.isEmpty()

    fun toJson(): String = JSONObject()
        .put("hidden", JSONArray(hiddenStreams.toList()))
        .put("names", JSONObject(names))
        .put("hiddenGroups", JSONArray(hiddenGroups.toList()))
        .put("groupOrder", JSONObject().apply { groupOrder.forEach { (k, v) -> put(k.name, JSONArray(v)) } })
        .put("order", JSONArray(streamOrder))
        .toString()

    companion object {
        fun fromJson(text: String?): Custom = runCatching {
            val o = JSONObject(text ?: return Custom())
            fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.getString(it) }
            val names = o.optJSONObject("names")
            val order = o.optJSONObject("groupOrder")
            Custom(
                hiddenStreams = strings(o.optJSONArray("hidden")).toSet(),
                names = names?.keys()?.asSequence()?.toList()?.associateWith { names.getString(it) }.orEmpty(),
                hiddenGroups = strings(o.optJSONArray("hiddenGroups")).toSet(),
                groupOrder = order?.keys()?.asSequence()?.toList()?.mapNotNull { k ->
                    runCatching { Kind.valueOf(k) }.getOrNull()?.let { it to strings(order.optJSONArray(k)) }
                }?.toMap().orEmpty(),
                streamOrder = strings(o.optJSONArray("order")),
            )
        }.getOrDefault(Custom())
    }
}

/** Die Liste so, wie der Nutzer sie haben will. */
fun Catalog.customized(kind: Kind, c: Custom): Catalog {
    if (c.isEmpty) return this
    val hiddenGroups = c.hiddenGroups.filter { it.startsWith("${kind.name}:") }.mapTo(HashSet()) { it.substringAfter(':') }
    val order = c.groupOrder[kind].orEmpty().withIndex().associate { (i, id) -> id to i }
    val cats = categories.filter { it.id == Catalog.ALL || it.id !in hiddenGroups }
        .withIndex()
        .sortedWith(compareBy({ if (it.value.id == Catalog.ALL) -1 else order[it.value.id] ?: Int.MAX_VALUE }, { it.index }))
        .map { it.value }
    val position = c.streamOrder.withIndex().associate { (i, k) -> k to i }
    var list = streams.filter { it.key !in c.hiddenStreams && it.categoryId !in hiddenGroups }
        .map { s -> c.names[s.key]?.let { s.copy(name = it) } ?: s }
    if (position.isNotEmpty()) {
        list = list.withIndex().sortedWith(compareBy({ position[it.value.key] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
    }
    return Catalog(cats, list, lockedIds)
}

/** Vertauscht [key] mit seinem Nachbarn in der sichtbaren Liste. */
fun moveInOrder(order: List<String>, visible: List<String>, key: String, up: Boolean): List<String> {
    val i = visible.indexOf(key)
    val other = visible.getOrNull(if (up) i - 1 else i + 1) ?: return order
    val all = order.toMutableList()
    (visible - all.toSet()).let { all.addAll(it) }
    val a = all.indexOf(key)
    val b = all.indexOf(other)
    all[a] = other
    all[b] = key
    return all
}

/**
 * Bildwiederholrate für ein Video mit [fps] Bildern je Sekunde: die kleinste angebotene, die ein ganzes
 * Vielfaches davon ist (25 → 50 Hz, 23,976 → 23,976 Hz, 29,97 → 59,94 Hz). null, wenn keine passt.
 */
fun pickRefreshRate(fps: Float, rates: List<Float>): Float? {
    if (fps < 10f || fps > 125f) return null
    return rates.filter { r ->
        val k = (r / fps).roundToInt()
        k >= 1 && abs(r - fps * k) < 0.012f * k
    }.minOrNull()
}

/** Eine Erinnerung an eine Sendung. */
data class Reminder(
    val id: String,
    val account: String,
    val channel: String,
    val channelName: String,
    val title: String,
    val start: Long,
    /** Wer sie gestellt hat; "" = erstes Profil. Fällig wird sie für alle. */
    val profile: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("account", account).put("channel", channel)
        .put("channelName", channelName).put("title", title).put("start", start).put("profile", profile)

    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            o.getString("id"), o.getString("account"), o.getString("channel"), o.optString("channelName"),
            o.optString("title"), o.getLong("start"), o.optString("profile"),
        )
    }
}
