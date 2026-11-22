package io.github.phillhf.iptv.data

/** Liest M3U/M3U8-Playlists mit #EXTINF-Zeilen (tvg-logo, group-title, tvg-id). */
object M3uParser {
    private val attribute = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")
    private val videoFile = Regex("\\.(mp4|mkv|avi|mov|m4v|wmv)(\\?.*)?$", RegexOption.IGNORE_CASE)

    /** Adresse des Programmführers aus der Kopfzeile (url-tvg oder x-tvg-url), wenn vorhanden. */
    fun epgUrl(text: String): String? {
        val header = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("﻿") ?: return null
        if (!header.startsWith("#EXTM3U", ignoreCase = true)) return null
        val attrs = attribute.findAll(header).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
        return listOf("url-tvg", "x-tvg-url", "tvg-url").firstNotNullOfOrNull { attrs[it]?.takeIf { u -> u.isNotBlank() } }
            ?.split(',')?.first()?.trim()
    }

    fun parse(text: String): List<Stream> {
        val out = ArrayList<Stream>()
        var name: String? = null
        var attrs: Map<String, String> = emptyMap()
        var group: String? = null
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.isEmpty() -> Unit
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    attrs = attribute.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    name = nameOf(line)
                    group = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> group = line.substringAfter(':').trim()
                line.startsWith("#") -> Unit
                else -> {
                    val url = line
                    val title = name?.takeIf { it.isNotBlank() }
                        ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                        ?: url.substringAfterLast('/')
                    val kind = if (url.contains("/movie/") || url.contains("/series/") || videoFile.containsMatchIn(url)) {
                        Kind.MOVIE
                    } else {
                        Kind.LIVE
                    }
                    out += Stream(
                        kind = kind,
                        id = idFor(url),
                        name = title,
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        categoryId = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: group ?: "Ohne Gruppe",
                        url = url,
                        epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        number = out.size + 1,
                        archiveDays = if (kind == Kind.LIVE) archiveDays(attrs) else 0,
                        catchupType = attrs["catchup"] ?: attrs["catchup-type"] ?: if (attrs["timeshift"] != null) "shift" else null,
                        catchupSource = attrs["catchup-source"],
                    )
                    name = null
                    attrs = emptyMap()
                    group = null
                }
            }
        }
        return out
    }

    private fun archiveDays(attrs: Map<String, String>): Int {
        val days = (attrs["catchup-days"] ?: attrs["timeshift"] ?: attrs["tvg-rec"])?.toIntOrNull()
        return days ?: if (attrs["catchup"] != null || attrs["catchup-type"] != null) 1 else 0
    }

    /** Der Name steht nach dem ersten Komma außerhalb von Anführungszeichen. */
    fun nameOf(line: String): String {
        var quoted = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> quoted = !quoted
                ',' -> if (!quoted) return line.substring(i + 1).trim()
            }
        }
        return ""
    }

    /** Stabile ID aus der Adresse, damit Favoriten nach dem Neuladen erhalten bleiben. */
    fun idFor(url: String): String = url.hashCode().toUInt().toString(16)
}
