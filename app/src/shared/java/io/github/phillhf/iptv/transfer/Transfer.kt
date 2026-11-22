package io.github.phillhf.iptv.transfer

import org.json.JSONObject

enum class TransferType { DOWNLOAD, RECORD }

enum class TransferState { SCHEDULED, QUEUED, RUNNING, DONE, FAILED }

data class Transfer(
    val id: String,
    val type: TransferType,
    /** Schlüssel des Eintrags beim Anbieter, dient auch zum Wiedererkennen. */
    val key: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val url: String,
    val file: String,
    val state: TransferState,
    val bytes: Long = 0,
    val total: Long = 0,
    val startAt: Long = 0,
    val endAt: Long = 0,
    val error: String? = null,
    /** Film, Serie oder Sender, zu dem der Eintrag gehört; entscheidet über die Kindersicherung. */
    val parentKey: String = key,
) {
    val progress: Float get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else 0f
    val active: Boolean get() = state == TransferState.RUNNING || state == TransferState.QUEUED

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type.name).put("key", key).put("title", title).put("subtitle", subtitle ?: "")
        .put("image", image ?: "").put("url", url).put("file", file).put("state", state.name)
        .put("bytes", bytes).put("total", total).put("startAt", startAt).put("endAt", endAt).put("error", error ?: "")
        .put("parent", parentKey)

    companion object {
        fun fromJson(o: JSONObject) = Transfer(
            id = o.getString("id"),
            type = TransferType.valueOf(o.getString("type")),
            key = o.getString("key"),
            title = o.getString("title"),
            subtitle = o.optString("subtitle").takeIf { it.isNotEmpty() },
            image = o.optString("image").takeIf { it.isNotEmpty() },
            url = o.getString("url"),
            file = o.getString("file"),
            state = TransferState.valueOf(o.getString("state")),
            bytes = o.optLong("bytes"),
            total = o.optLong("total"),
            startAt = o.optLong("startAt"),
            endAt = o.optLong("endAt"),
            error = o.optString("error").takeIf { it.isNotEmpty() },
            parentKey = o.optString("parent").ifEmpty { o.getString("key") },
        )
    }
}
