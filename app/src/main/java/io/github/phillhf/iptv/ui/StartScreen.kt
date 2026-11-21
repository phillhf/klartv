package io.github.phillhf.iptv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.ContinueEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.Stream
import kotlinx.coroutines.delay

/** Eine Kachel in einer Reihe: ein Eintrag des Anbieters oder etwas zum Weiterschauen. */
sealed interface Tile {
    data class Item(val stream: Stream) : Tile
    data class Resume(val entry: ContinueEntry) : Tile
}

enum class ShelfStyle { WIDE, POSTER, CHANNEL }

data class ShelfData(val title: String, val style: ShelfStyle, val tiles: List<Tile>)

/** Was im großen Infobereich steht. */
data class Spot(
    val title: String,
    val meta: String,
    val plot: String?,
    val backdrop: String?,
    val isCover: Boolean,
    val logo: String? = null,
    val badge: String? = null,
)

fun spotFor(vm: AppViewModel, tile: Tile): Spot = when (tile) {
    is Tile.Resume -> Spot(
        title = tile.entry.title,
        meta = metaLine(tile.entry.subtitle, minutesLeft(tile.entry.position, tile.entry.duration)),
        plot = null,
        backdrop = tile.entry.image,
        isCover = false,
        badge = "WEITERSCHAUEN",
    )
    is Tile.Item -> spotFor(vm, tile.stream)
}

fun spotFor(vm: AppViewModel, s: Stream): Spot {
    if (s.kind == Kind.LIVE) {
        val (now, next) = currentAndNext(vm.nowEpg[s.id].orEmpty())
        return Spot(
            title = s.name,
            meta = metaLine(now?.let { "Jetzt: ${it.title}" }, next?.let { "Danach: ${it.title}" }),
            plot = now?.description,
            backdrop = null,
            isCover = false,
            logo = s.logo,
            badge = "LIVE",
        )
    }
    val info = if (s.kind == Kind.MOVIE) vm.cachedVodInfo(s) else null
    val backdrop = info?.backdrop ?: s.backdrop
    return Spot(
        title = s.name,
        meta = metaLine(
            kindLabel(s.kind),
            info?.year ?: s.year,
            info?.genre ?: s.genre,
            info?.duration,
            (info?.rating ?: s.rating)?.let { "★ $it" },
        ),
        plot = info?.plot ?: s.plot,
        backdrop = backdrop ?: info?.cover ?: s.logo,
        isCover = backdrop == null,
    )
}

/** Bild für eine Breitbild-Karte: lieber Szenenbild als Plakat. */
private fun wideImage(vm: AppViewModel, s: Stream): String? =
    (if (s.kind == Kind.MOVIE) vm.cachedVodInfo(s)?.backdrop else null) ?: s.backdrop ?: s.logo

fun buildShelves(vm: AppViewModel, featured: List<Stream>): List<ShelfData> {
    val shelves = ArrayList<ShelfData>()
    val live = vm.readyCatalog(Kind.LIVE)
    val movies = vm.readyCatalog(Kind.MOVIE)
    val series = vm.readyCatalog(Kind.SERIES)

    if (vm.continueList.isNotEmpty()) {
        shelves += ShelfData("Weiterschauen", ShelfStyle.WIDE, vm.continueList.map { Tile.Resume(it) })
    }
    if (featured.isNotEmpty()) shelves += ShelfData("Highlights", ShelfStyle.WIDE, featured.map { Tile.Item(it) })

    val favLive = live?.streams.orEmpty().filter { it.key in vm.favorites }
    if (favLive.isNotEmpty()) shelves += ShelfData("Deine Sender", ShelfStyle.CHANNEL, favLive.map { Tile.Item(it) })
    val recentLive = vm.recent.filter { it.startsWith("LIVE:") && it !in vm.favorites }.mapNotNull { live?.byKey?.get(it) }.take(20)
    if (recentLive.isNotEmpty()) shelves += ShelfData("Zuletzt gesehene Sender", ShelfStyle.CHANNEL, recentLive.map { Tile.Item(it) })

    fun posters(title: String, list: List<Stream>) {
        if (list.isNotEmpty()) shelves += ShelfData(title, ShelfStyle.POSTER, list.map { Tile.Item(it) })
    }
    posters("Neue Filme", vm.newest(Kind.MOVIE).ifEmpty { movies?.streams.orEmpty().take(30) })
    posters("Neue Serien", vm.newest(Kind.SERIES).ifEmpty { series?.streams.orEmpty().take(30) })
    posters(
        "Deine Merkliste",
        (movies?.streams.orEmpty() + series?.streams.orEmpty()).filter { it.key in vm.favorites },
    )

    fun categoryShelves(catalog: Catalog?, prefix: String, count: Int, style: ShelfStyle) {
        catalog ?: return
        catalog.categories.filter { it.id != Catalog.ALL }
            .map { it to catalog.inCategory(it.id) }
            .filter { it.second.size >= 4 }
            .take(count)
            .forEach { (c, list) -> shelves += ShelfData("$prefix${c.name}", style, list.take(30).map { Tile.Item(it) }) }
    }
    categoryShelves(movies, "Filme · ", 4, ShelfStyle.POSTER)
    categoryShelves(series, "Serien · ", 2, ShelfStyle.POSTER)
    if (favLive.isEmpty()) categoryShelves(live, "Live · ", 2, ShelfStyle.CHANNEL)
    return shelves
}

@Composable
fun ShelfView(vm: AppViewModel, data: ShelfData, side: Dp, wide: Boolean, onFocusTile: (Tile) -> Unit) {
    val context = LocalContext.current
    val siblings = remember(data) { data.tiles.mapNotNull { (it as? Tile.Item)?.stream } }
    Shelf(data.title, side) {
        items(data.tiles) { tile ->
            when (tile) {
                is Tile.Resume -> WideCard(
                    title = tile.entry.title,
                    subtitle = metaLine(tile.entry.subtitle, minutesLeft(tile.entry.position, tile.entry.duration)),
                    image = tile.entry.image,
                    progress = tile.entry.progress,
                    modifier = Modifier.width(if (wide) 236.dp else 236.dp),
                    onFocused = { onFocusTile(tile) },
                ) { vm.playContinue(context, tile.entry) }
                is Tile.Item -> {
                    val s = tile.stream
                    val open = { vm.openStream(context, s, siblings) }
                    when (data.style) {
                        ShelfStyle.WIDE -> WideCard(
                            title = s.name,
                            subtitle = metaLine(kindLabel(s.kind), s.year, s.genre),
                            image = wideImage(vm, s),
                            modifier = Modifier.width(236.dp),
                            onFocused = { vm.focus(s); onFocusTile(tile) },
                            onClick = open,
                        )
                        ShelfStyle.POSTER -> PosterCard(
                            title = s.name,
                            image = s.logo,
                            rating = s.rating,
                            progress = vm.progress(s.key),
                            modifier = Modifier.width(if (wide) 124.dp else 112.dp),
                            onFocused = { vm.focus(s); onFocusTile(tile) },
                            onClick = open,
                        )
                        ShelfStyle.CHANNEL -> {
                            LaunchedEffect(s.id) { vm.requestEpg(s) }
                            ChannelCard(
                                name = s.name,
                                logo = s.logo,
                                now = currentAndNext(vm.nowEpg[s.id].orEmpty()).first,
                                modifier = Modifier.width(if (wide) 200.dp else 180.dp),
                                onFocused = { onFocusTile(tile) },
                                onClick = open,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StartScreen(vm: AppViewModel, wide: Boolean) {
    LaunchedEffect(vm.active?.id) { Kind.entries.forEach { vm.ensureLoaded(it) } }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshLibrary() }

    val movies = vm.readyCatalog(Kind.MOVIE)
    val series = vm.readyCatalog(Kind.SERIES)
    val live = vm.readyCatalog(Kind.LIVE)
    if (movies == null && series == null && live == null) {
        val error = Kind.entries.map { vm.catalog(it) }.filterIsInstance<Load.Error>().firstOrNull()
        if (error != null && Kind.entries.none { vm.catalog(it) is Load.Loading }) {
            ErrorBox(error.message) { Kind.entries.forEach { vm.ensureLoaded(it, force = true) } }
        } else {
            LoadingBox("Lade deine Sender, Filme und Serien …")
        }
        return
    }

    val featured = remember(movies, series) { vm.featured() }
    LaunchedEffect(featured) { vm.prefetch(featured) }
    val shelves = remember(movies, series, live, vm.favorites, vm.continueList, vm.recent, featured) { buildShelves(vm, featured) }

    if (wide) StartWide(vm, featured, shelves) else StartPhone(vm, featured, shelves)
}

@Composable
private fun StartWide(vm: AppViewModel, featured: List<Stream>, shelves: List<ShelfData>) {
    var focusedTile by remember { mutableStateOf<Tile?>(null) }
    var heroIndex by remember { mutableStateOf(0) }
    val still = LocalInspectionMode.current
    LaunchedEffect(featured, focusedTile) {
        // Ohne Fokus (Tablet per Touch) wechselt die große Vorschau von selbst
        while (!still && focusedTile == null && featured.size > 1) {
            delay(8_000)
            heroIndex = (heroIndex + 1) % featured.size
        }
    }
    val spot = focusedTile?.let { spotFor(vm, it) } ?: featured.getOrNull(heroIndex)?.let { spotFor(vm, it) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val spotHeight = (maxHeight * 0.40f).coerceAtLeast(180.dp)
        Crossfade(targetState = spot?.backdrop to (spot?.isCover == true), animationSpec = tween(450), label = "Hintergrund") { (url, cover) ->
            Backdrop(url, isCover = cover)
        }
        Column(Modifier.fillMaxSize()) {
            Crossfade(targetState = spot, animationSpec = tween(250), label = "Info") { s ->
                SpotInfo(s, Modifier.fillMaxWidth().height(spotHeight).padding(start = 48.dp, end = 48.dp, top = 36.dp))
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 48.dp)) {
                items(shelves) { shelf -> ShelfView(vm, shelf, side = 48.dp, wide = true) { focusedTile = it } }
            }
        }
    }
}

@Composable
private fun SpotInfo(spot: Spot?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (spot == null) return@Column
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            spot.badge?.let { Badge(it, color = if (it == "LIVE") Klar.Live else Klar.Accent, textColor = if (it == "LIVE") Color.White else Color(0xFF02131D)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (spot.logo != null) {
                Logo(spot.logo, spot.title, 72.dp)
                Spacer(Modifier.width(16.dp))
            }
            HeroInfo(spot.title, spot.meta, spot.plot, Modifier.widthIn(max = 680.dp), plotLines = 3)
        }
    }
}

@Composable
private fun StartPhone(vm: AppViewModel, featured: List<Stream>, shelves: List<ShelfData>) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        if (featured.isNotEmpty()) item { HeroPager(vm, featured) } else item { PhoneHeader(vm) }
        items(shelves) { shelf -> ShelfView(vm, shelf, side = 16.dp, wide = false) {} }
    }
}

@Composable
private fun PhoneHeader(vm: AppViewModel, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Klar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(" TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Klar.Accent)
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { vm.tab = Tab.FAVORITES }) { Icon(Icons.Filled.Star, contentDescription = "Favoriten") }
        IconButton(onClick = { vm.tab = Tab.SETTINGS }) { Icon(Icons.Filled.Settings, contentDescription = "Einstellungen") }
    }
}

@Composable
private fun HeroPager(vm: AppViewModel, featured: List<Stream>) {
    val context = LocalContext.current
    val pager = rememberPagerState { featured.size }
    val still = LocalInspectionMode.current
    LaunchedEffect(pager, featured.size) {
        while (!still && featured.size > 1) {
            delay(7_000)
            pager.animateScrollToPage((pager.currentPage + 1) % featured.size)
        }
    }
    Box(Modifier.fillMaxWidth().height(480.dp)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val s = featured[page]
            val spot = spotFor(vm, s)
            Box(Modifier.fillMaxSize()) {
                Backdrop(spot.backdrop, isCover = spot.isCover)
                Column(
                    Modifier.align(Alignment.BottomStart).padding(horizontal = 20.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HeroInfo(spot.title, spot.meta, spot.plot, plotLines = 3, big = false)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { if (s.kind == Kind.MOVIE) vm.playMovie(context, s, fromStart = false) else vm.openStream(context, s, featured) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                        ) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (s.kind == Kind.MOVIE) "Abspielen" else "Folgen")
                        }
                        OutlinedButton(onClick = { vm.openStream(context, s, featured) }) {
                            Icon(Icons.Filled.Info, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Details")
                        }
                    }
                }
            }
        }
        PhoneHeader(vm, Modifier.align(Alignment.TopStart))
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            repeat(featured.size) { i ->
                Box(
                    Modifier.size(if (i == pager.currentPage) 18.dp else 6.dp, 6.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) Klar.Accent else Color.White.copy(alpha = 0.35f)),
                )
            }
        }
    }
}
