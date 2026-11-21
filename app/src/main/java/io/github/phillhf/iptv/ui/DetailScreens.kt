package io.github.phillhf.iptv.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
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
fun MovieScreen(vm: AppViewModel, stream: Stream) {
    val context = LocalContext.current
    var info by remember(stream.key) { mutableStateOf<VodInfo?>(null) }
    LaunchedEffect(stream.key) { info = vm.vodInfo(stream) }
    val tick = resumeTick()
    val resume = remember(tick, stream.key) { vm.position(stream.key) }
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (isTv(context)) runCatching { firstButton.requestFocus() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TitleBar(stream.name) { vm.back() }
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            PosterImage(info?.cover ?: stream.logo, stream.name, Modifier.width(160.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val meta = listOfNotNull(info?.year, info?.genre, info?.duration, stream.rating?.let { "★ $it" })
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                if (meta.isNotEmpty()) Text(meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (resume > 0) {
                    Button(
                        onClick = { vm.playMovie(context, stream, fromStart = false) },
                        modifier = Modifier.focusRequester(firstButton).focusRing(Pill),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Fortsetzen bei ${formatTime(resume)}")
                    }
                    OutlinedButton(onClick = { vm.playMovie(context, stream, fromStart = true) }, modifier = Modifier.focusRing(Pill)) {
                        Icon(Icons.Filled.Replay, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Von vorn")
                    }
                } else {
                    Button(
                        onClick = { vm.playMovie(context, stream, fromStart = true) },
                        modifier = Modifier.focusRequester(firstButton).focusRing(Pill),
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Abspielen")
                    }
                }
                FavoriteButton(vm, stream)
            }
        }
        val plot = info?.plot ?: stream.plot
        if (!plot.isNullOrBlank()) Text(plot, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

@Composable
fun SeriesScreen(vm: AppViewModel, stream: Stream) {
    val context = LocalContext.current
    var state by remember(stream.key) { mutableStateOf<Load<SeriesInfo>>(Load.Loading) }
    LaunchedEffect(stream.key) { state = vm.seriesInfo(stream) }
    var season by rememberSaveable(stream.key) { mutableStateOf<Int?>(null) }
    val tick = resumeTick()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item { TitleBar(stream.name) { vm.back() } }
        item {
            val info = (state as? Load.Ready<SeriesInfo>)?.value
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                PosterImage(info?.cover ?: stream.logo, stream.name, Modifier.width(140.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    stream.rating?.let { Text("★ $it", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FavoriteButton(vm, stream)
                    val plot = info?.plot ?: stream.plot
                    if (!plot.isNullOrBlank()) Text(plot, style = MaterialTheme.typography.bodyMedium, maxLines = 8)
                }
            }
        }
        when (val s = state) {
            Load.Idle, Load.Loading -> item {
                Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            }
            is Load.Error -> item { Text(s.message, modifier = Modifier.padding(24.dp)) }
            is Load.Ready -> {
                val seasons = s.value.seasons.keys.toList()
                val selected = season?.takeIf { it in seasons } ?: seasons.firstOrNull()
                val episodes = s.value.seasons[selected].orEmpty()
                if (seasons.isEmpty()) item { Text("Keine Folgen vorhanden.", modifier = Modifier.padding(24.dp)) }
                if (seasons.size > 1) {
                    item {
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                    val subtitle = listOfNotNull(
                        e.duration,
                        if (position > 0) "weiter bei ${formatTime(position)}" else null,
                    ).joinToString(" · ")
                    StreamRow(
                        title = "${e.number}. ${e.title}",
                        subtitle = subtitle,
                        image = e.image ?: s.value.cover ?: stream.logo,
                        onClick = { vm.playEpisodes(context, stream, episodes, i) },
                    )
                }
            }
        }
    }
}
