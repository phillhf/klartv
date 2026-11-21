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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.SeriesInfo
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.data.VodInfo

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
        Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(if (favorite) "Favorit entfernen" else "Zu Favoriten")
    }
}

@Composable
private fun Credits(director: String?, cast: String?) {
    if (!director.isNullOrBlank()) Text("Regie: $director", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!cast.isNullOrBlank()) {
        Text(
            "Mit: $cast",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MovieScreen(vm: AppViewModel, stream: Stream) {
    val context = LocalContext.current
    var info by remember(stream.key) { mutableStateOf(vm.cachedVodInfo(stream)) }
    LaunchedEffect(stream.key) { info = vm.vodInfo(stream) }
    val tick = resumeTick()
    val resume = remember(tick, stream.key) { vm.position(stream.key) }
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv(context)) runCatching { firstButton.requestFocus() } }
    val backdrop = info?.backdrop ?: stream.backdrop

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Backdrop(backdrop ?: info?.cover ?: stream.logo, isCover = backdrop == null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { TitleBar("", onBack = { vm.back() }) }
            item {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PosterImage(info?.cover ?: stream.logo, stream.name, Modifier.width(if (wide) 200.dp else 120.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroInfo(
                            title = stream.name,
                            meta = metaLine(info?.year ?: stream.year, info?.genre, info?.duration, (info?.rating ?: stream.rating)?.let { "★ $it" }),
                            plot = if (wide) info?.plot ?: stream.plot else null,
                            plotLines = 8,
                        )
                        if (wide) Credits(info?.director, info?.cast)
                        PlayButtons(
                            resume = resume,
                            focus = firstButton,
                            onResume = { vm.playMovie(context, stream, fromStart = false) },
                            onStart = { vm.playMovie(context, stream, fromStart = true) },
                        )
                        FavoriteButton(vm, stream)
                    }
                }
            }
            if (!wide) {
                item {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        (info?.plot ?: stream.plot)?.let { Text(it) }
                        Credits(info?.director, info?.cast)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayButtons(resume: Long, focus: FocusRequester, onResume: () -> Unit, onStart: () -> Unit) {
    if (resume > 0) {
        Button(onClick = onResume, modifier = Modifier.focusRequester(focus).focusRing(Pill)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Fortsetzen bei ${formatTime(resume)}")
        }
        OutlinedButton(onClick = onStart, modifier = Modifier.focusRing(Pill)) {
            Icon(Icons.Filled.Replay, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Von vorn")
        }
    } else {
        Button(onClick = onStart, modifier = Modifier.focusRequester(focus).focusRing(Pill)) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Abspielen")
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

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        Backdrop(backdrop ?: info?.cover ?: stream.logo, isCover = backdrop == null)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { TitleBar("", onBack = { vm.back() }) }
            item {
                Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PosterImage(info?.cover ?: stream.logo, stream.name, Modifier.width(if (wide) 180.dp else 110.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeroInfo(
                            title = stream.name,
                            meta = metaLine(info?.year ?: stream.year, info?.genre ?: stream.genre, stream.rating?.let { "★ $it" }),
                            plot = if (wide) info?.plot ?: stream.plot else null,
                            plotLines = 6,
                        )
                        if (wide) Credits(info?.director, info?.cast)
                        FavoriteButton(vm, stream)
                    }
                }
            }
            if (!wide) {
                item {
                    (info?.plot ?: stream.plot)?.let { Text(it, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) }
                }
            }
            when (val s = state) {
                Load.Idle, Load.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                is Load.Error -> item { Text(s.message, modifier = Modifier.padding(24.dp)) }
                is Load.Ready -> {
                    val seasons = s.value.seasons.keys.toList()
                    val selected = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
                    val episodes = s.value.seasons[selected].orEmpty()
                    if (seasons.isEmpty()) item { Text("Keine Folgen vorhanden.", modifier = Modifier.padding(24.dp)) }
                    if (seasons.size > 1) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(seasons) { n ->
                                    FilterChip(
                                        selected = n == selected,
                                        onClick = { season = n },
                                        label = { Text(if (n == 0) "Folgen" else "Staffel $n") },
                                        modifier = Modifier.focusRing(),
                                    )
                                }
                            }
                        }
                    }
                    itemsIndexed(episodes) { i, e ->
                        val position = remember(tick, e.key) { vm.position(e.key) }
                        EpisodeRow(
                            title = "${e.number}. ${e.title}",
                            meta = metaLine(e.duration, if (position > 0) "weiter bei ${formatTime(position)}" else null),
                            plot = e.plot,
                            image = e.image ?: s.value.cover ?: stream.logo,
                            onClick = { vm.playEpisodes(context, stream, episodes, i) },
                        )
                    }
                }
            }
        }
    }
}
