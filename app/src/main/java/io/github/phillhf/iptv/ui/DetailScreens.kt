package io.github.phillhf.iptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.Stream

/** Zählt hoch, wenn die Seite nach dem Player wieder sichtbar wird, damit „Fortsetzen“ aktuell ist. */
@Composable
private fun resumeTick(): Int {
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    return tick
}

@Composable
private fun FavoriteButton(vm: AppViewModel, stream: Stream) {
    val favorite = stream.key in vm.favorites
    OutlinedButton(onClick = { vm.toggleFavorite(stream) }, modifier = Modifier.focusRing(Pill)) {
        Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null, tint = if (favorite) Klar.Gold else Klar.Text)
        Spacer(Modifier.width(8.dp))
        Text(if (favorite) "Gemerkt" else "Merken")
    }
}

@Composable
private fun PrimaryButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = modifier.focusRing(Pill),
        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
private fun Credits(director: String?, cast: String?) {
    if (!director.isNullOrBlank()) Text("Regie: $director", style = MaterialTheme.typography.bodyMedium, color = Klar.TextDim)
    if (!cast.isNullOrBlank()) {
        Text("Mit: $cast", style = MaterialTheme.typography.bodyMedium, color = Klar.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Kopfbereich der Detailseiten: Plakat links, rechts Titel, Eckdaten, Beschreibung und Knöpfe. */
@Composable
private fun DetailHeader(
    wide: Boolean,
    poster: String?,
    title: String,
    meta: String,
    plot: String?,
    director: String?,
    cast: String?,
    buttons: @Composable () -> Unit,
) {
    Row(
        Modifier.padding(start = if (wide) 48.dp else 16.dp, end = if (wide) 48.dp else 16.dp, top = if (wide) 24.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (wide) 36.dp else 16.dp),
    ) {
        PosterImage(poster, title, Modifier.width(if (wide) 210.dp else 116.dp))
        Column(Modifier.weight(1f).widthIn(max = 760.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            HeroInfo(title, meta, if (wide) plot else null, plotLines = 6, big = wide)
            if (wide) Credits(director, cast)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons() }
            }
        }
    }
    if (!wide && (!plot.isNullOrBlank() || !director.isNullOrBlank() || !cast.isNullOrBlank())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Credits(director, cast)
        }
    }
}

@Composable
private fun SimilarShelf(vm: AppViewModel, stream: Stream, wide: Boolean) {
    val catalog = vm.readyCatalog(stream.kind) ?: return
    val similar = remember(catalog, stream.key) { catalog.inCategory(stream.categoryId).filter { it.key != stream.key }.take(20) }
    if (similar.isEmpty()) return
    Spacer(Modifier.padding(top = 20.dp))
    ShelfView(vm, ShelfData("Mehr davon", ShelfStyle.POSTER, similar.map { Tile.Item(it) }), if (wide) 48.dp else 16.dp, wide) {}
}

@Composable
fun MovieScreen(vm: AppViewModel, stream: Stream) {
    val context = LocalContext.current
    LaunchedEffect(stream.key) { vm.vodInfo(stream) }
    val info = vm.cachedVodInfo(stream)
    val tick = resumeTick()
    val resume = remember(tick, stream.key) { vm.position(stream.key) }
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(stream.key) { if (isTv(context)) runCatching { firstButton.requestFocus() } }
    val backdrop = info?.backdrop ?: stream.backdrop

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Backdrop(backdrop ?: info?.cover ?: stream.logo, isCover = backdrop == null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { TitleBar("", onBack = { vm.back() }) }
            item {
                DetailHeader(
                    wide = wide,
                    poster = info?.cover ?: stream.logo,
                    title = stream.name,
                    meta = metaLine(info?.year ?: stream.year, info?.genre, info?.duration, (info?.rating ?: stream.rating)?.let { "★ $it" }),
                    plot = info?.plot ?: stream.plot,
                    director = info?.director,
                    cast = info?.cast,
                ) {
                    if (resume > 0) {
                        PrimaryButton("Fortsetzen bei ${formatTime(resume)}", Icons.Filled.PlayArrow, Modifier.focusRequester(firstButton)) {
                            vm.playMovie(context, stream, fromStart = false)
                        }
                        OutlinedButton(onClick = { vm.playMovie(context, stream, fromStart = true) }, modifier = Modifier.focusRing(Pill)) {
                            Icon(Icons.Filled.Replay, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Von vorn")
                        }
                    } else {
                        PrimaryButton("Abspielen", Icons.Filled.PlayArrow, Modifier.focusRequester(firstButton)) {
                            vm.playMovie(context, stream, fromStart = true)
                        }
                    }
                    FavoriteButton(vm, stream)
                }
            }
            item { SimilarShelf(vm, stream, wide) }
        }
    }
}

@Composable
fun SeriesScreen(vm: AppViewModel, stream: Stream) {
    val context = LocalContext.current
    var state by remember(stream.key) { mutableStateOf<Load<SeriesInfo>>(Load.Loading) }
    LaunchedEffect(stream.key) { state = vm.seriesInfo(stream) }
    var season by rememberSaveable(stream.key) { mutableStateOf<Int?>(null) }
    val tick = resumeTick()
    val info = (state as? Load.Ready<SeriesInfo>)?.value
    val backdrop = info?.backdrop ?: stream.backdrop
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(info != null) { if (info != null && isTv(context)) runCatching { firstButton.requestFocus() } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Backdrop(backdrop ?: info?.cover ?: stream.logo, isCover = backdrop == null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item { TitleBar("", onBack = { vm.back() }) }
            val seasons = info?.seasons?.keys?.toList().orEmpty()
            val selected = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
            val episodes = info?.seasons?.get(selected).orEmpty()
            item {
                DetailHeader(
                    wide = wide,
                    poster = info?.cover ?: stream.logo,
                    title = stream.name,
                    meta = metaLine(
                        info?.year ?: stream.year,
                        info?.genre ?: stream.genre,
                        if (seasons.isNotEmpty()) "${seasons.size} ${if (seasons.size == 1) "Staffel" else "Staffeln"}" else null,
                        stream.rating?.let { "★ $it" },
                    ),
                    plot = info?.plot ?: stream.plot,
                    director = info?.director,
                    cast = info?.cast,
                ) {
                    if (episodes.isNotEmpty()) {
                        // Erste Folge, die noch nicht fertig ist, sonst Folge 1
                        val resumeIndex = episodes.indexOfFirst { vm.position(it.key) > 0 }.takeIf { it >= 0 } ?: 0
                        val e = episodes[resumeIndex]
                        PrimaryButton(
                            if (vm.position(e.key) > 0) "S${e.season} E${e.number} fortsetzen" else "S${e.season} E${e.number} abspielen",
                            Icons.Filled.PlayArrow,
                            Modifier.focusRequester(firstButton),
                        ) { vm.playEpisodes(context, stream, info, episodes, resumeIndex) }
                    }
                    FavoriteButton(vm, stream)
                }
            }
            when (val s = state) {
                Load.Idle, Load.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Klar.Accent) }
                }
                is Load.Error -> item { Text(s.message, modifier = Modifier.padding(32.dp)) }
                is Load.Ready -> {
                    if (seasons.isEmpty()) item { Text("Keine Folgen vorhanden.", modifier = Modifier.padding(32.dp)) }
                    if (seasons.size > 1) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = if (wide) 48.dp else 16.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(seasons) { n ->
                                    SeasonChip(if (n == 0) "Folgen" else "Staffel $n", n == selected) { season = n }
                                }
                            }
                        }
                    } else {
                        item { Spacer(Modifier.padding(top = 16.dp)) }
                    }
                    itemsIndexed(episodes) { i, e ->
                        val position = remember(tick, e.key) { vm.position(e.key) }
                        val progress = vm.continueList.firstOrNull { it.key == e.key }?.progress ?: if (position > 0) 0.05f else 0f
                        Box(Modifier.padding(horizontal = if (wide) 32.dp else 0.dp)) {
                            EpisodeRow(
                                title = "${e.number}. ${e.title}",
                                meta = metaLine(e.duration, if (position > 0) "weiter bei ${formatTime(position)}" else null),
                                plot = e.plot,
                                image = e.image ?: s.value.backdrop ?: s.value.cover ?: stream.logo,
                                progress = progress,
                                onClick = { vm.playEpisodes(context, stream, s.value, episodes, i) },
                            )
                        }
                    }
                }
            }
            item { SimilarShelf(vm, stream, wide) }
        }
    }
}

@Composable
private fun SeasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        Button(
            onClick = onClick,
            modifier = Modifier.focusRing(Pill),
            colors = ButtonDefaults.buttonColors(containerColor = Klar.Accent, contentColor = Color(0xFF02131D)),
        ) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = Modifier.focusRing(Pill)) { Text(label, color = Klar.Text) }
    }
}
