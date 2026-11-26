package io.github.phillhf.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.Screen
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.Category
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.platform.BackHandler
import io.github.phillhf.iptv.platform.ListScrollbar
import io.github.phillhf.iptv.platform.appIcon
import io.github.phillhf.iptv.platform.mouseDriven
import io.github.phillhf.iptv.t

enum class Tab(val label: String, val icon: ImageVector) {
    START("Start", Icons.Filled.Home),
    LIVE("Live-TV", Icons.Filled.LiveTv),
    GUIDE("Programm", Icons.Filled.CalendarViewDay),
    MOVIES("Filme", Icons.Filled.Movie),
    SERIES("Serien", Icons.Filled.VideoLibrary),
    FAVORITES("Favoriten", Icons.Filled.Star),
    MEDIA("Mediathek", Icons.Filled.Folder),
    SEARCH("Suche", Icons.Filled.Search),
    SETTINGS("Einstellungen", Icons.Filled.Settings),
}

/** Auf dem Handy passen nicht alle Bereiche in die Leiste; die übrigen sind oben auf „Start“. */
private val PhoneTabs = listOf(Tab.START, Tab.LIVE, Tab.MOVIES, Tab.SERIES, Tab.SEARCH)

private val NavCollapsed = 84.dp

@Composable
fun HomeScreen(vm: AppViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        if (wide) {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().padding(start = NavCollapsed)) { TabContent(vm, wide = true) }
                SideNav(vm, vm.tab, { vm.tab = it }, Modifier.align(Alignment.CenterStart))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) { TabContent(vm, wide = false) }
                NavigationBar(containerColor = Klar.Surface) {
                    PhoneTabs.forEach { item ->
                        NavigationBarItem(
                            selected = vm.tab == item,
                            onClick = { vm.tab = item },
                            icon = { Icon(item.icon, contentDescription = t(item.label)) },
                            label = { Text(t(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Klar.OnAccent,
                                indicatorColor = Klar.Accent,
                                unselectedIconColor = Klar.TextDim,
                                unselectedTextColor = Klar.TextDim,
                                selectedTextColor = Klar.Text,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Seitenleiste für TV und Tablet: schmal mit Symbolen, klappt mit Beschriftung auf, sobald die
 * Fernbedienung sie ansteuert, und legt sich dabei über den Inhalt.
 */
@Composable
private fun SideNav(vm: AppViewModel, selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    var focusInside by remember { mutableStateOf(false) }
    val mouse = remember { MutableInteractionSource() }
    val hovered by mouse.collectIsHoveredAsState()
    val expanded = if (mouseDriven) hovered else focusInside
    val width by animateDpAsState(if (expanded) 250.dp else NavCollapsed, label = "Seitenleiste")
    Column(
        modifier
            .fillMaxHeight()
            .width(width)
            .background(
                if (expanded) {
                    Brush.horizontalGradient(listOf(Klar.Bg, Klar.Bg.copy(alpha = 0.97f), Klar.Bg.copy(alpha = 0.9f)))
                } else {
                    Brush.horizontalGradient(listOf(Klar.Bg, Klar.Bg.copy(alpha = 0.85f)))
                },
            )
            .onFocusChanged { focusInside = it.hasFocus }
            .hoverable(mouse)
            // Handy quer: Die Leiste ist höher als der Bildschirm
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(Modifier.padding(start = 6.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(appIcon(), contentDescription = null, modifier = Modifier.size(44.dp))
            AnimatedVisibility(expanded, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.padding(start = 6.dp)) {
                    Text("Klar", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text(" TV", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black, color = Klar.Accent)
                }
            }
        }
        if (vm.profiles.size > 1) {
            Row(Modifier.padding(start = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileButton(vm, size = 36.dp)
                if (expanded) Text(profileName(vm.profile), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp))
            }
        }
        Tab.entries.forEach { item ->
            if (item == Tab.SETTINGS) Spacer(Modifier.height(8.dp))
            NavItem(item, item == selected, expanded) { onSelect(item) }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, expanded: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val bg = when {
        focused -> Klar.Accent
        selected -> Klar.Accent.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val fg = when {
        focused -> Klar.OnAccent
        selected -> Klar.Accent
        else -> Klar.TextDim
    }
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(Pill)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(tab.icon, contentDescription = t(tab.label), tint = fg)
        if (expanded) {
            Text(
                t(tab.label),
                color = if (focused) fg else Klar.Text,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun TabContent(vm: AppViewModel, wide: Boolean) {
    Crossfade(targetState = vm.tab, animationSpec = tween(220), label = "Bereich") { tab ->
        when (tab) {
            Tab.START -> StartScreen(vm, wide)
            Tab.LIVE -> LiveScreen(vm, wide)
            Tab.GUIDE -> GuideScreen(vm, wide)
            Tab.MEDIA -> MediaLibraryScreen(vm, wide)
            Tab.MOVIES -> MediaScreen(vm, Kind.MOVIE, wide)
            Tab.SERIES -> MediaScreen(vm, Kind.SERIES, wide)
            Tab.FAVORITES -> FavoritesScreen(vm, wide)
            Tab.SEARCH -> SearchScreen(vm, wide)
            Tab.SETTINGS -> SettingsScreen(vm)
        }
    }
}

@Composable
private fun MediaScreen(vm: AppViewModel, kind: Kind, wide: Boolean) {
    LaunchedEffect(kind, vm.active?.id) { vm.ensureLoaded(kind) }
    when (val state = vm.catalog(kind)) {
        Load.Idle, Load.Loading -> LoadingBox(if (kind == Kind.MOVIE) t("Lade Filme …") else t("Lade Serien …"))
        is Load.Error -> ErrorBox(state.message) { vm.ensureLoaded(kind, force = true) }
        is Load.Ready -> when {
            state.value.streams.isEmpty() -> Centered { Text(t("Dein Anbieter hat hier keine Inhalte.")) }
            wide -> MediaWide(vm, kind, state.value)
            else -> MediaPhone(vm, kind, state.value)
        }
    }
}

private fun openScreen(kind: Kind, s: io.github.phillhf.iptv.data.Stream) =
    if (kind == Kind.MOVIE) Screen.Movie(s) else Screen.Series(s)

/**
 * Mediacenter-Ansicht: Hintergrundbild und Infobereich zum Titel unter dem Fokus, darunter das
 * Cover-Raster, links die Kategorien.
 */
@Composable
private fun MediaWide(vm: AppViewModel, kind: Kind, catalog: Catalog) {
    val current = vm.lastCategory[kind] ?: Catalog.ALL
    val list = remember(catalog, current) { catalog.inCategory(current) }
    val item = vm.focused?.takeIf { it.kind == kind } ?: list.firstOrNull()
    val spot = item?.let { spotFor(vm, it) }
    var groupMenu by remember { mutableStateOf<Category?>(null) }
    groupMenu?.let { c -> GroupMenu(vm, kind, c, onDismiss = { groupMenu = null }) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val infoHeight = (maxHeight * 0.36f).coerceAtLeast(170.dp)
        Crossfade(targetState = spot?.backdrop to (spot?.isCover == true), animationSpec = tween(450), label = "Hintergrund") { (url, cover) ->
            Backdrop(url, isCover = cover)
        }
        Row(Modifier.fillMaxSize()) {
            CategoryRail(
                catalog.categories,
                current,
                { catalog.inCategory(it).size },
                Modifier.width(240.dp).fillMaxHeight().background(Klar.Bg.copy(alpha = 0.6f)),
                locked = catalog.lockedIds,
                onMenu = { id -> catalog.categories.firstOrNull { it.id == id && id != Catalog.ALL }?.let { groupMenu = it } },
            ) { vm.chooseCategory(kind, it) }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                Crossfade(targetState = spot, animationSpec = tween(250), label = "Info") { s ->
                    if (s != null) {
                        HeroInfo(
                            s.title,
                            s.meta,
                            s.plot,
                            Modifier.fillMaxWidth().height(infoHeight).padding(start = 32.dp, end = 48.dp, top = 32.dp).widthIn(max = 760.dp),
                            plotLines = 3,
                        )
                    } else {
                        Spacer(Modifier.height(infoHeight))
                    }
                }
                val grid = rememberLazyGridState()
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    LazyVerticalGrid(
                        GridCells.Adaptive(112.dp),
                        Modifier.weight(1f).fillMaxHeight(),
                        state = grid,
                        contentPadding = PaddingValues(start = 28.dp, end = 32.dp, top = 8.dp, bottom = 32.dp),
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        items(list) { s ->
                            val image = vm.cover(s)
                            PosterCard(
                                title = s.name,
                                image = image,
                                rating = s.rating,
                                progress = vm.progress(s.key),
                                onFocused = { vm.focus(s) },
                                onImageError = { vm.coverFailed(s, image) },
                                watched = vm.watchMark(s).first,
                                seen = vm.watchMark(s).second,
                            ) {
                                vm.focus(s)
                                vm.open(openScreen(kind, s))
                            }
                        }
                    }
                    ListScrollbar(grid)
                }
            }
        }
    }
}

@Composable
private fun MediaPhone(vm: AppViewModel, kind: Kind, catalog: Catalog) {
    val current = vm.lastCategory[kind] ?: Catalog.ALL
    val list = remember(catalog, current) { catalog.inCategory(current) }
    var groupMenu by remember { mutableStateOf<Category?>(null) }
    groupMenu?.let { c -> GroupMenu(vm, kind, c, onDismiss = { groupMenu = null }) }
    Column(Modifier.fillMaxSize()) {
        Text(
            if (kind == Kind.MOVIE) t("Filme") else t("Serien"),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp),
        )
        CategoryChips(
            catalog.categories, current, { vm.chooseCategory(kind, it) }, locked = catalog.lockedIds,
            onMenu = { id -> catalog.categories.firstOrNull { it.id == id && id != Catalog.ALL }?.let { groupMenu = it } },
        )
        LazyVerticalGrid(
            GridCells.Adaptive(108.dp),
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(list) { s ->
                val image = vm.cover(s)
                val mark = vm.watchMark(s)
                PosterCard(
                    title = s.name, image = image, rating = s.rating, progress = vm.progress(s.key), onImageError = { vm.coverFailed(s, image) },
                    watched = mark.first, seen = mark.second,
                ) {
                    vm.focus(s)
                    vm.open(openScreen(kind, s))
                }
            }
        }
    }
}

@Composable
private fun FavoritesScreen(vm: AppViewModel, wide: Boolean) {
    LaunchedEffect(vm.active?.id) { Kind.entries.forEach { vm.ensureLoaded(it) } }
    val loading = Kind.entries.any { vm.catalog(it) is Load.Loading }
    var sorting by rememberSaveable { mutableStateOf(false) }
    if (sorting) {
        SortFavorites(vm, wide) { sorting = false }
        return
    }
    val shelves = buildList {
        val resume = vm.shownContinue
        if (resume.isNotEmpty()) add(ShelfData(t("Weiterschauen"), ShelfStyle.WIDE, resume.map { Tile.Resume(it) }))
        fun favs(kind: Kind) = vm.favoriteStreams(kind).map { Tile.Item(it) }
        favs(Kind.LIVE).takeIf { it.isNotEmpty() }?.let { add(ShelfData(t("Sender"), ShelfStyle.CHANNEL, it)) }
        favs(Kind.MOVIE).takeIf { it.isNotEmpty() }?.let { add(ShelfData(t("Filme"), ShelfStyle.POSTER, it)) }
        favs(Kind.SERIES).takeIf { it.isNotEmpty() }?.let { add(ShelfData(t("Serien"), ShelfStyle.POSTER, it)) }
    }
    val side = if (wide) 48.dp else 16.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)) {
        item {
            Row(Modifier.padding(start = side, end = side, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t("Favoriten"), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                if (vm.favorites.size > 1) LabelButton(Icons.Filled.SwapVert, t("Sortieren")) { sorting = true }
            }
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = side), color = Klar.Accent) }
        items(shelves) { ShelfView(vm, it, side, wide) {} }
        if (!loading && shelves.isEmpty()) {
            item {
                Text(
                    t("Noch nichts gemerkt. Markiere Sender mit dem Stern oder wähle bei Filmen und Serien „Zu Favoriten“."),
                    modifier = Modifier.padding(horizontal = side, vertical = 24.dp),
                    color = Klar.TextDim,
                )
            }
        }
    }
}

/** Reihenfolge der Favoriten mit Hoch/Runter, das geht mit Fernbedienung und Touch. */
@Composable
private fun SortFavorites(vm: AppViewModel, wide: Boolean, onDone: () -> Unit) {
    BackHandler { onDone() }
    val side = if (wide) 48.dp else 16.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 16.dp, bottom = 32.dp)) {
        item { TitleBar(t("Favoriten sortieren"), onBack = onDone) }
        Kind.entries.forEach { kind ->
            val list = vm.favoriteStreams(kind)
            if (list.size > 1) {
                item { Text(kindPlural(kind), style = MaterialTheme.typography.titleLarge, color = Klar.Accent, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)) }
                items(list, key = { it.key }) { s ->
                    Row(Modifier.animateItem().fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo(s.logo, s.name, 44.dp)
                        Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 12.dp))
                        RoundButton(Icons.Filled.KeyboardArrowUp, t("Nach oben")) { vm.moveFavorite(s, up = true) }
                        RoundButton(Icons.Filled.KeyboardArrowDown, t("Nach unten")) { vm.moveFavorite(s, up = false) }
                        RoundButton(Icons.Filled.Delete, t("Entfernen")) { vm.toggleFavorite(s) }
                    }
                }
            }
        }
    }
}

private fun kindPlural(kind: Kind) = when (kind) {
    Kind.LIVE -> t("Sender")
    Kind.MOVIE -> t("Filme")
    Kind.SERIES -> t("Serien")
}

@Composable
private fun SearchScreen(vm: AppViewModel, wide: Boolean) {
    LaunchedEffect(vm.active?.id) { Kind.entries.forEach { vm.ensureLoaded(it) } }
    var query by rememberSaveable { mutableStateOf("") }
    val catalogs = Kind.entries.map { vm.readyCatalog(it) }
    val q = query.trim()
    val results = remember(q, catalogs) {
        if (q.length < 2) {
            emptyList()
        } else {
            Kind.entries.mapNotNull { kind ->
                val hits = vm.readyCatalog(kind)?.streams.orEmpty().filter { it.name.contains(q, ignoreCase = true) }.take(60)
                if (hits.isEmpty()) {
                    null
                } else {
                    ShelfData(
                        when (kind) {
                            Kind.LIVE -> t("Sender")
                            Kind.MOVIE -> t("Filme")
                            Kind.SERIES -> t("Serien")
                        },
                        if (kind == Kind.LIVE) ShelfStyle.CHANNEL else ShelfStyle.POSTER,
                        hits.map { Tile.Item(it) },
                    )
                }
            }
        }
    }
    val side = if (wide) 48.dp else 16.dp
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 24.dp, bottom = 32.dp)) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(t("Sender, Filme oder Serien suchen")) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                shape = Pill,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Klar.Accent,
                    unfocusedBorderColor = Klar.SurfaceHigh,
                    focusedContainerColor = Klar.Surface,
                    unfocusedContainerColor = Klar.Surface,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = side).widthIn(max = 720.dp).leaveWithArrows(),
            )
        }
        if (Kind.entries.any { vm.catalog(it) is Load.Loading }) {
            item { LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = side, vertical = 8.dp), color = Klar.Accent) }
        }
        item { Spacer(Modifier.height(20.dp)) }
        items(results) { ShelfView(vm, it, side, wide) {} }
        if (q.length >= 2 && results.isEmpty()) {
            item { Text(t("Nichts gefunden für „%1“.", q), modifier = Modifier.padding(horizontal = side), color = Klar.TextDim) }
        }
    }
}
