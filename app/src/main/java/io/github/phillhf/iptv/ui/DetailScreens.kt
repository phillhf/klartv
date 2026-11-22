package io.github.phillhf.iptv.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Theaters
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import coil3.compose.AsyncImage
import io.github.phillhf.iptv.data.Person
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.transfer.TransferState
import io.github.phillhf.iptv.transfer.TransferType
import io.github.phillhf.iptv.transfer.Transfers
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
    val favorite = vm.isFavorite(stream.key)
    OutlinedButton(onClick = { vm.toggleFavorite(stream) }, modifier = Modifier.focusRing(Pill)) {
        Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = null, tint = if (favorite) Klar.Gold else Klar.Text)
        Spacer(Modifier.width(8.dp))
        Text(if (favorite) t("Gemerkt") else t("Merken"))
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
    if (!director.isNullOrBlank()) Text(t("Regie: %1", director), style = MaterialTheme.typography.bodyMedium, color = Klar.TextDim)
    if (!cast.isNullOrBlank()) {
        Text(t("Mit: %1", cast), style = MaterialTheme.typography.bodyMedium, color = Klar.TextDim, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            if (wide) ButtonRow(buttons)
        }
    }
    // Auf dem Handy ist neben dem Cover zu wenig Platz
    if (!wide) Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) { ButtonRow(buttons) }
    if (!wide && (!plot.isNullOrBlank() || !director.isNullOrBlank() || !cast.isNullOrBlank())) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            plot?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Credits(director, cast)
        }
    }
}

/** Knöpfe, die auf dem Handy umbrechen, statt aus dem Bild zu laufen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ButtonRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

@Composable
private fun DownloadButton(key: String, onClick: () -> Unit) {
    val ask = rememberNotificationPermission()
    val x = Transfers.find(key, TransferType.DOWNLOAD)
    val label = when {
        x == null || x.state == TransferState.FAILED -> t("Herunterladen")
        x.state == TransferState.DONE -> t("Heruntergeladen")
        x.total > 0 -> t("Lädt … %1 %", (x.progress * 100).toInt())
        else -> t("Wartet …")
    }
    OutlinedButton(
        onClick = {
            ask()
            onClick()
        },
        modifier = Modifier.focusRing(Pill),
    ) {
        Icon(if (x?.state == TransferState.DONE) Icons.Filled.DownloadDone else Icons.Filled.Download, contentDescription = null, tint = Klar.Text)
        Spacer(Modifier.width(8.dp))
        Text(label, color = Klar.Text)
    }
}

@Composable
private fun TrailerButton(trailer: String?) {
    val context = LocalContext.current
    val url = trailerUrl(trailer) ?: return
    OutlinedButton(
        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
        modifier = Modifier.focusRing(Pill),
    ) {
        Icon(Icons.Filled.Theaters, contentDescription = null, tint = Klar.Text)
        Spacer(Modifier.width(8.dp))
        Text(t("Trailer"), color = Klar.Text)
    }
}

/** Anbieter liefern mal die YouTube-Kennung, mal die ganze Adresse. */
fun trailerUrl(value: String?): String? {
    val v = value?.trim().orEmpty()
    return when {
        v.startsWith("http") -> v
        Regex("[A-Za-z0-9_-]{11}").matches(v) -> "https://www.youtube.com/watch?v=$v"
        else -> null
    }
}

@Composable
private fun People(people: List<Person>, wide: Boolean) {
    if (people.isEmpty()) return
    Shelf(t("Besetzung"), if (wide) 48.dp else 16.dp, Modifier.padding(top = 20.dp)) {
        items(people) { p ->
            Column(Modifier.width(96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(84.dp).clip(CircleShape).background(Klar.SurfaceHigh), contentAlignment = Alignment.Center) {
                    if (p.photo != null) {
                        AsyncImage(p.photo, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(p.name.take(1), style = MaterialTheme.typography.headlineSmall, color = Klar.TextDim)
                    }
                }
                Text(p.name, style = MaterialTheme.typography.labelMedium, maxLines = 2, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                p.role?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = Klar.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun SimilarShelf(vm: AppViewModel, stream: Stream, wide: Boolean) {
    val catalog = vm.readyCatalog(stream.kind) ?: return
    val similar = remember(catalog, stream.key) { catalog.inCategory(stream.categoryId).filter { it.key != stream.key }.take(20) }
    if (similar.isEmpty()) return
    Spacer(Modifier.padding(top = 20.dp))
    ShelfView(vm, ShelfData(t("Mehr davon"), ShelfStyle.POSTER, similar.map { Tile.Item(it) }), if (wide) 48.dp else 16.dp, wide) {}
}

@Composable
fun MovieScreen(vm: AppViewModel, stream: Stream) {
    val context = LocalContext.current
    LaunchedEffect(stream.key) {
        val loaded = vm.vodInfo(stream)
        vm.loadTmdb(stream, loaded?.year ?: stream.year, loaded?.tmdbId)
    }
    val info = vm.cachedVodInfo(stream)
    val more = vm.tmdb[stream.key]
    val tick = resumeTick()
    val resume = remember(tick, stream.key) { vm.position(stream.key) }
    val firstButton = remember { FocusRequester() }
    LaunchedEffect(stream.key) { if (isTv(context)) runCatching { firstButton.requestFocus() } }
    val backdrop = info?.backdrop ?: stream.backdrop ?: more?.backdrop

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
                    plot = info?.plot ?: stream.plot ?: more?.plot,
                    director = info?.director,
                    cast = if (more?.people.isNullOrEmpty()) info?.cast else null,
                ) {
                    if (resume > 0) {
                        PrimaryButton(t("Fortsetzen bei %1", formatTime(resume)), Icons.Filled.PlayArrow, Modifier.focusRequester(firstButton)) {
                            vm.playMovie(context, stream, fromStart = false)
                        }
                        OutlinedButton(onClick = { vm.playMovie(context, stream, fromStart = true) }, modifier = Modifier.focusRing(Pill)) {
                            Icon(Icons.Filled.Replay, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(t("Von vorn"))
                        }
                    } else {
                        PrimaryButton(t("Abspielen"), Icons.Filled.PlayArrow, Modifier.focusRequester(firstButton)) {
                            vm.playMovie(context, stream, fromStart = true)
                        }
                    }
                    FavoriteButton(vm, stream)
                    DownloadButton(stream.key) { vm.downloadMovie(stream) }
                    TrailerButton(info?.trailer ?: more?.trailer)
                }
            }
            item { People(more?.people.orEmpty(), wide) }
            item { SimilarShelf(vm, stream, wide) }
        }
    }
}

@Composable
fun SeriesScreen(vm: AppViewModel, stream: Stream) {
    val context = LocalContext.current
    var state by remember(stream.key) { mutableStateOf<Load<SeriesInfo>>(Load.Loading) }
    LaunchedEffect(stream.key) {
        val loaded = vm.seriesInfo(stream)
        state = loaded
        val found = (loaded as? Load.Ready<SeriesInfo>)?.value
        vm.loadTmdb(stream, found?.year ?: stream.year, found?.tmdbId)
    }
    val more = vm.tmdb[stream.key]
    var season by rememberSaveable(stream.key) { mutableStateOf<Int?>(null) }
    val tick = resumeTick()
    val info = (state as? Load.Ready<SeriesInfo>)?.value
    val backdrop = info?.backdrop ?: stream.backdrop ?: more?.backdrop
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
                        if (seasons.isNotEmpty()) (if (seasons.size == 1) t("%1 Staffel", 1) else t("%1 Staffeln", seasons.size)) else null,
                        stream.rating?.let { "★ $it" },
                    ),
                    plot = info?.plot ?: stream.plot ?: more?.plot,
                    director = info?.director,
                    cast = if (more?.people.isNullOrEmpty()) info?.cast else null,
                ) {
                    if (episodes.isNotEmpty()) {
                        // Erste Folge, die noch nicht fertig ist, sonst Folge 1
                        val resumeIndex = episodes.indexOfFirst { vm.position(it.key) > 0 }.takeIf { it >= 0 } ?: 0
                        val e = episodes[resumeIndex]
                        PrimaryButton(
                            if (vm.position(e.key) > 0) t("%1 fortsetzen", "S${e.season} E${e.number}") else t("%1 abspielen", "S${e.season} E${e.number}"),
                            Icons.Filled.PlayArrow,
                            Modifier.focusRequester(firstButton),
                        ) { vm.playEpisodes(context, stream, info, episodes, resumeIndex) }
                    }
                    FavoriteButton(vm, stream)
                    TrailerButton(info?.trailer ?: more?.trailer)
                }
            }
            when (val s = state) {
                Load.Idle, Load.Loading -> item {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Klar.Accent) }
                }
                is Load.Error -> item { Text(s.message, modifier = Modifier.padding(32.dp)) }
                is Load.Ready -> {
                    if (seasons.isEmpty()) item { Text(t("Keine Folgen vorhanden."), modifier = Modifier.padding(32.dp)) }
                    if (seasons.size > 1) {
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = if (wide) 48.dp else 16.dp, vertical = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(seasons) { n ->
                                    SeasonChip(if (n == 0) t("Folgen") else t("Staffel %1", n), n == selected) { season = n }
                                }
                            }
                        }
                    } else {
                        item { Spacer(Modifier.padding(top = 16.dp)) }
                    }
                    itemsIndexed(episodes) { i, e ->
                        val position = remember(tick, e.key) { vm.position(e.key) }
                        val progress = vm.continueList.firstOrNull { it.key == e.key }?.progress ?: if (position > 0) 0.05f else 0f
                        Row(Modifier.padding(horizontal = if (wide) 32.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
                            EpisodeRow(
                                modifier = Modifier.weight(1f),
                                title = "${e.number}. ${e.title}",
                                meta = metaLine(e.duration, if (position > 0) t("weiter bei %1", formatTime(position)) else null),
                                plot = e.plot,
                                image = e.image ?: s.value.backdrop ?: s.value.cover ?: stream.logo,
                                progress = progress,
                                onClick = { vm.playEpisodes(context, stream, s.value, episodes, i) },
                            )
                            EpisodeDownload(e.key) { vm.downloadEpisode(stream, s.value, e) }
                        }
                    }
                }
            }
            item { People(more?.people.orEmpty(), wide) }
            item { SimilarShelf(vm, stream, wide) }
        }
    }
}

@Composable
private fun EpisodeDownload(key: String, onClick: () -> Unit) {
    val ask = rememberNotificationPermission()
    val x = Transfers.find(key, TransferType.DOWNLOAD)
    Box(contentAlignment = Alignment.Center) {
        RoundButton(if (x?.state == TransferState.DONE) Icons.Filled.DownloadDone else Icons.Filled.Download, t("Herunterladen")) {
            ask()
            onClick()
        }
        if (x?.active == true) CircularProgressIndicator(progress = { x.progress }, modifier = Modifier.size(44.dp), color = Klar.Accent, strokeWidth = 2.dp)
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
