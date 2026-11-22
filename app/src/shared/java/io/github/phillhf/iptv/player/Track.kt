package io.github.phillhf.iptv.player

/** Eine Ton- oder Untertitelspur. [id] gilt nur für die Engine, die sie geliefert hat. */
data class Track(val id: String, val label: String, val selected: Boolean)

enum class ScaleMode { FIT, ZOOM, STRETCH }
