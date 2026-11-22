package io.github.phillhf.iptv.cast

import io.github.phillhf.iptv.platform.AppContext

/** Chromecast gibt es nur auf Android; der Knopf dazu ist unter Windows ausgeblendet. */
object Caster {
    class Route(val id: String, val name: String)

    val connectedTo: String? = null
    val routes: List<Route> = emptyList()
    val problem: String? = null

    fun disconnect(@Suppress("UNUSED_PARAMETER") context: AppContext) = Unit
}
