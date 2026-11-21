package io.github.phillhf.iptv.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.Screen
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.toPlayItem

enum class Tab(val label: String, val icon: ImageVector) {
    LIVE("Live-TV", Icons.Filled.LiveTv),
    MOVIES("Filme", Icons.Filled.Movie),
    SERIES("Serien", Icons.Filled.VideoLibrary),
    FAVORITES("Favoriten", Icons.Filled.Star),
    SEARCH("Suche", Icons.Filled.Search),
    SETTINGS("Einstellungen", Icons.Filled.Settings),
}

@Composable
fun HomeScreen(vm: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Tab.entries.forEach { t ->
                        NavigationRailItem(
                            selected = vm.tab == t,
                            onClick = { vm.tab = t },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label, maxLines = 1) },
                        )
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight()) { TabContent(vm, wide = true) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(vm, wide = false) }
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = vm.tab == t,
                            onClick = { vm.tab = t },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            alwaysShowLabel = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TabContent(vm: AppViewModel, wide: Boolean) {
    when (vm.tab) {
        Tab.LIVE -> BrowseScreen(vm, Kind.LIVE, wide)
        Tab.MOVIES -> BrowseScreen(vm, Kind.MOVIE, wide)
        Tab.SERIES -> BrowseScreen(vm, Kind.SERIES, wide)
        Tab.FAVORITES -> FavoritesScreen(vm)
        Tab.SEARCH -> SearchScreen(vm)
        Tab.SETTINGS -> SettingsScreen(vm)
    }
}

@Composable
private fun BrowseScreen(vm: AppViewModel, kind: Kind, wide: Boolean) {
    LaunchedEffect(kind, vm.active?.id) { vm.ensureLoaded(kind) }
    when (val state = vm.catalog(kind)) {
        Load.Idle, Load.Loading -> LoadingBox()
        is Load.Error -> ErrorBox(state.message) { vm.ensureLoaded(kind, force = true) }
        is Load.Ready -> CatalogView(vm, kind, state.value, wide)
    }
}

@Composable
private fun CatalogView(vm: AppViewModel, kind: Kind, catalog: Catalog, wide: Boolean) {
    if (catalog.streams.isEmpty()) {
        Centered { Text("Dein Anbieter hat hier keine Inhalte.") }
        return
    }
    val selected = vm.lastCategory[kind]
    val choose: (String?) -> Unit = { id -> if (id == null) vm.lastCategory.remove(kind) else vm.lastCategory[kind] = id }
    if (wide) {
        val current = selected ?: Catalog.ALL
        Row(Modifier.fillMaxSize()) {
            CategoryList(catalog, current, Modifier.width(260.dp).fillMaxHeight(), choose)
            VerticalDivider()
            Items(vm, kind, catalog, current, Modifier.weight(1f).fillMaxHeight())
        }
    } else if (selected == null) {
        CategoryList(catalog, null, Modifier.fillMaxSize(), choose)
    } else {
        BackHandler { choose(null) }
        Column(Modifier.fillMaxSize()) {
            TitleBar(catalog.categories.firstOrNull { it.id == selected }?.name ?: "") { choose(null) }
            Items(vm, kind, catalog, selected, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

@Composable
private fun CategoryList(catalog: Catalog, selected: String?, modifier: Modifier, onSelect: (String) -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
        items(catalog.categories) { c ->
            val isSelected = c.id == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp)
                    .focusRing(shape)
                    .clip(shape)
                    .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onSelect(c.id) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    catalog.inCategory(c.id).size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Items(vm: AppViewModel, kind: Kind, catalog: Catalog, categoryId: String, modifier: Modifier) {
    val list = remember(catalog, categoryId) { catalog.inCategory(categoryId) }
    val context = LocalContext.current
    if (kind == Kind.LIVE) {
        LazyColumn(modifier, contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(list) { i, s ->
                StreamRow(
                    title = s.name,
                    subtitle = null,
                    image = s.logo,
                    leading = (i + 1).toString(),
                    favorite = s.key in vm.favorites,
                    onClick = { vm.play(context, list.map { it.toPlayItem() }, i) },
                    onFavorite = { vm.toggleFavorite(s) },
                )
            }
        }
    } else {
        LazyVerticalGrid(GridCells.Adaptive(130.dp), modifier, contentPadding = PaddingValues(8.dp)) {
            items(list) { s ->
                PosterCard(s.name, s.logo) {
                    vm.open(if (kind == Kind.MOVIE) Screen.Movie(s) else Screen.Series(s))
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(vm: AppViewModel) {
    LaunchedEffect(vm.active?.id) { Kind.entries.forEach { vm.ensureLoaded(it) } }
    val loading = Kind.entries.any { vm.catalog(it) is Load.Loading }
    val recent = vm.recent.mapNotNull { vm.findStream(it) }
    val favorites = Kind.entries.associateWith { k ->
        vm.readyCatalog(k)?.streams?.filter { it.key in vm.favorites }.orEmpty()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        section(vm, "Zuletzt gesehen", recent)
        section(vm, "Live-TV", favorites.getValue(Kind.LIVE))
        section(vm, "Filme", favorites.getValue(Kind.MOVIE))
        section(vm, "Serien", favorites.getValue(Kind.SERIES))
        if (!loading && recent.isEmpty() && favorites.values.all { it.isEmpty() }) {
            item {
                Text(
                    "Noch keine Favoriten. Tippe bei einem Sender auf den Stern oder öffne einen Film und wähle „Zu Favoriten“.",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.section(vm: AppViewModel, title: String, list: List<Stream>) {
    if (list.isEmpty()) return
    item {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
    items(list) { s ->
        val context = LocalContext.current
        StreamRow(
            title = s.name,
            subtitle = kindLabel(s.kind),
            image = s.logo,
            favorite = s.key in vm.favorites,
            onClick = { vm.openStream(context, s, list) },
            onFavorite = { vm.toggleFavorite(s) },
        )
    }
}

@Composable
private fun SearchScreen(vm: AppViewModel) {
    LaunchedEffect(vm.active?.id) { Kind.entries.forEach { vm.ensureLoaded(it) } }
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val catalogs = Kind.entries.map { vm.readyCatalog(it) }
    val results = remember(query, catalogs) {
        val q = query.trim()
        if (q.length < 2) {
            emptyList()
        } else {
            catalogs.filterNotNull().flatMap { c -> c.streams.filter { it.name.contains(q, ignoreCase = true) } }.take(300)
        }
    }
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Sender, Filme oder Serien suchen") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (Kind.entries.any { vm.catalog(it) is Load.Loading }) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(results) { s ->
                StreamRow(
                    title = s.name,
                    subtitle = kindLabel(s.kind),
                    image = s.logo,
                    favorite = s.key in vm.favorites,
                    onClick = { vm.openStream(context, s, results) },
                    onFavorite = { vm.toggleFavorite(s) },
                )
            }
            if (query.trim().length >= 2 && results.isEmpty()) {
                item { Text("Nichts gefunden.", modifier = Modifier.padding(24.dp)) }
            }
        }
    }
}
