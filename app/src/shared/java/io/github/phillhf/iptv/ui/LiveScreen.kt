package io.github.phillhf.iptv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.Screen
import io.github.phillhf.iptv.platform.ListScrollbar
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.platform.mouseDriven
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.Category
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.toPlayItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val clock = SimpleDateFormat("HH:mm", Locale.GERMANY)

/** Kennung der Kategorie „Favoriten“ in den Senderlisten. */
const val FAVORITES = "fav"

fun liveCategories(vm: AppViewModel, catalog: Catalog): List<Category> =
    if (vm.favoriteStreams(Kind.LIVE).isEmpty()) catalog.categories else listOf(Category(FAVORITES, "Favoriten")) + catalog.categories

fun timeRange(start: Long, end: Long): String = "${clock.format(Date(start))} – ${clock.format(Date(end))}"

fun clockTime(t: Long): String = clock.format(Date(t))

@Composable
fun LiveScreen(vm: AppViewModel, wide: Boolean) {
    LaunchedEffect(vm.active?.id) { vm.ensureLoaded(Kind.LIVE) }
    when (val state = vm.catalog(Kind.LIVE)) {
        Load.Idle, Load.Loading -> LoadingBox(t("Lade Sender …"))
        is Load.Error -> ErrorBox(state.message) { vm.ensureLoaded(Kind.LIVE, force = true) }
        is Load.Ready -> if (state.value.streams.isEmpty()) {
            Centered { Text(t("Dein Anbieter hat keine Live-Sender.")) }
        } else {
            LiveView(vm, state.value, wide)
        }
    }
}

@Composable
private fun LiveView(vm: AppViewModel, catalog: Catalog, wide: Boolean) {
    val context = LocalAppContext.current
    val current = vm.lastCategory[Kind.LIVE] ?: Catalog.ALL
    val list = remember(catalog, current, vm.favorites) {
        if (current == FAVORITES) vm.favoriteStreams(Kind.LIVE) else catalog.inCategory(current)
    }
    val categories = liveCategories(vm, catalog)
    var selected by remember(current) { mutableStateOf(list.firstOrNull()) }
    var clicked by remember(current) { mutableStateOf(list.firstOrNull()?.key) }
    val preview = rememberLivePreview()
    val play = { i: Int ->
        preview.stop()
        vm.play(context, list.map { it.toPlayItem() }, i)
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            CategoryRail(
                categories,
                current,
                { if (it == FAVORITES) vm.favoriteStreams(Kind.LIVE).size else catalog.inCategory(it).size },
                Modifier.width(230.dp).fillMaxHeight(),
                locked = catalog.lockedIds,
                header = {
                    LabelButton(Icons.Filled.GridView, t("Mehrere Sender")) {
                        preview.stop()
                        vm.open(Screen.MultiView)
                    }
                },
            ) { vm.chooseCategory(Kind.LIVE, it) }
            val rows = rememberLazyListState()
            LazyColumn(Modifier.weight(1.1f).fillMaxHeight(), state = rows, contentPadding = PaddingValues(vertical = 16.dp)) {
                itemsIndexed(list) { i, s ->
                    LaunchedEffect(s.id) { vm.requestEpg(s) }
                    ChannelRow(
                        vm = vm,
                        stream = s,
                        number = i + 1,
                        selected = s.key == selected?.key,
                        onFocused = { selected = s },
                        onClick = {
                            if (mouseDriven && vm.livePreview && clicked != s.key) clicked = s.key else play(i)
                        },
                    )
                }
            }
            ListScrollbar(rows)
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = 24.dp, end = 32.dp, start = 12.dp)) {
                selected?.let { PreviewPane(vm, it, preview) }
            }
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Live-TV"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                RoundButton(Icons.Filled.CalendarViewDay, t("Programm")) { vm.tab = Tab.GUIDE }
                RoundButton(Icons.Filled.GridView, t("Mehrere Sender")) { vm.open(Screen.MultiView) }
            }
            CategoryChips(categories, current, { vm.chooseCategory(Kind.LIVE, it) }, locked = catalog.lockedIds)
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(list) { i, s ->
                    LaunchedEffect(s.id) { vm.requestEpg(s) }
                    ChannelRow(vm, s, i + 1, selected = false, onFocused = {}, onClick = { play(i) })
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(vm: AppViewModel, stream: Stream, number: Int, selected: Boolean, onFocused: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    val (now, _) = currentAndNext(vm.nowEpg[stream.id].orEmpty())
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .onFocusChanged { if (it.isFocused) onFocused() }
                .focusRing(shape)
                .clip(shape)
                .background(if (selected) Klar.Accent.copy(alpha = 0.10f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) Klar.Accent else Klar.TextDim,
                modifier = Modifier.width(38.dp),
            )
            Box(Modifier.size(width = 72.dp, height = 44.dp).clip(RoundedCornerShape(8.dp)).background(Klar.SurfaceHigh), contentAlignment = Alignment.Center) {
                if (!stream.logo.isNullOrBlank()) {
                    AsyncImage(stream.logo, null, Modifier.fillMaxSize().padding(5.dp), contentScale = ContentScale.Fit)
                } else {
                    Text(stream.name.take(3).uppercase(), style = MaterialTheme.typography.labelMedium, color = Klar.TextDim)
                }
            }
            Column(Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(stream.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                if (now != null) {
                    Text(now.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Klar.TextDim)
                    ProgressLine(epgProgress(now), Modifier.fillMaxWidth(0.85f))
                }
            }
        }
        FavoriteStar(vm.isFavorite(stream.key)) { vm.toggleFavorite(stream) }
    }
}

@Composable
private fun PreviewPane(vm: AppViewModel, stream: Stream, preview: LivePreview) {
    val list = vm.nowEpg[stream.id].orEmpty()
    val (now, next) = currentAndNext(list)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(CardShape).background(Color.Black)) {
            if (vm.livePreview) {
                PreviewVideo(preview, stream.url, Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Logo(stream.logo, stream.name, 96.dp) }
            }
            Badge("LIVE", Modifier.align(Alignment.TopStart).padding(10.dp), color = Klar.Live)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(stream.logo, stream.name, 52.dp)
            Text(
                stream.name,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 14.dp),
            )
        }
        if (now != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t("JETZT"), style = MaterialTheme.typography.labelLarge, color = Klar.Accent)
                Text("   ${timeRange(now.start, now.end)}", style = MaterialTheme.typography.labelLarge, color = Klar.TextDim)
            }
            Text(now.title, style = MaterialTheme.typography.titleLarge)
            ProgressLine(epgProgress(now), Modifier.fillMaxWidth(), height = 4.dp)
            if (now.description.isNotBlank()) {
                Text(now.description, style = MaterialTheme.typography.bodyMedium, color = Klar.Text.copy(alpha = 0.8f), maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
        } else if (!vm.hasEpg) {
            Text(t("Dein Anbieter liefert für diesen Sender kein Programm."), color = Klar.TextDim)
        }
        if (next != null) {
            Row {
                Text(t("DANACH"), style = MaterialTheme.typography.labelLarge, color = Klar.Accent2)
                Text("   ${clockTime(next.start)}  ${next.title}", style = MaterialTheme.typography.labelLarge, color = Klar.TextDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(if (mouseDriven) t("Noch einmal klicken: groß ansehen") else t("OK: Vollbild  ·  Im Vollbild ▲ ▼: Sender wechseln"), style = MaterialTheme.typography.labelMedium, color = Klar.TextDim)
    }
}

/** Kategorien als Spalte links (TV und Tablet). */
@Composable
fun CategoryRail(
    categories: List<Category>,
    selected: String,
    count: (String) -> Int,
    modifier: Modifier,
    locked: Set<String> = emptySet(),
    header: (@Composable () -> Unit)? = null,
    onSelect: (String) -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (header != null) item { Box(Modifier.padding(bottom = 12.dp)) { header() } }
        item {
            Text(t("KATEGORIEN"), style = MaterialTheme.typography.labelMedium, color = Klar.TextDim, modifier = Modifier.padding(start = 14.dp, bottom = 8.dp))
        }
        items(categories) { c ->
            val isSelected = c.id == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .focusRing(shape)
                    .clip(shape)
                    .background(if (isSelected) Klar.Accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(c.id) }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t(c.name),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isSelected) Klar.Accent else Klar.Text,
                    modifier = Modifier.weight(1f),
                )
                if (c.id in locked) {
                    Icon(Icons.Filled.Lock, contentDescription = t("Gesperrt"), tint = Klar.Gold, modifier = Modifier.size(16.dp))
                } else Text(count(c.id).toString(), style = MaterialTheme.typography.labelSmall, color = Klar.TextDim, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}
