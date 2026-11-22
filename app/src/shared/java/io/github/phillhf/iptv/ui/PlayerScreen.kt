package io.github.phillhf.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.PlayerActions
import io.github.phillhf.iptv.PlayerPanel
import io.github.phillhf.iptv.PlayerUi
import io.github.phillhf.iptv.cast.Caster
import io.github.phillhf.iptv.data.AppSettings
import io.github.phillhf.iptv.data.EngineChoice
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.player.ScaleMode
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.transfer.Transfers

@Composable
fun PlayerScreen(ui: PlayerUi, actions: PlayerActions) {
    if (ui.inPip) return
    CompositionLocalProvider(LocalContentColor provides Color.White) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (ui.panel != null) actions.openPanel(null) else actions.showControls(!ui.controls) },
                    onDoubleTap = { actions.touchSeek(it.x > size.width / 2) },
                )
            },
        ) {
            PlayerOverlay(ui.item, ui.epg, ui.info && !ui.controls && ui.panel == null, ui.message)
            if (ui.buffering) CircularProgressIndicator(Modifier.align(Alignment.Center), color = Klar.Accent)
            if (ui.digits.isNotEmpty()) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(32.dp).clip(CardShape).background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                ) { Text(ui.digits, style = MaterialTheme.typography.displaySmall) }
            }
            AnimatedVisibility(ui.controls && ui.panel == null, enter = fadeIn(), exit = fadeOut()) { Controls(ui, actions) }
            ui.panel?.let { Panel(it, ui, actions) }
        }
    }
}

@Composable
private fun Controls(ui: PlayerUi, actions: PlayerActions) {
    val item = ui.item ?: return
    val play = remember { FocusRequester() }
    val seek = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { if (ui.seeking) seek.requestFocus() else play.requestFocus() } }
    val (now, next) = currentAndNext(ui.epg)
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 0.3f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.9f)))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.AutoMirrored.Filled.ArrowBack, t("Zurück")) { actions.close() }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (item.live) Badge("LIVE", color = Klar.Live)
                    Text(item.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                (now?.title ?: item.subtitle)?.let { Text(it, color = Color.White.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (ui.canPip) RoundButton(Icons.Filled.PictureInPictureAlt, t("Bild im Bild")) { actions.pip() }
            if (!item.local) RoundButton(Icons.Filled.Cast, t("Übertragen")) { actions.openPanel(PlayerPanel.CAST) }
            RoundButton(Icons.AutoMirrored.Filled.OpenInNew, t("Anderer Player")) { actions.external() }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 24.dp, vertical = 18.dp)) {
            if (ui.duration > 0) {
                SeekBar(ui, actions, seek)
            } else if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clockTime(now.start), style = MaterialTheme.typography.labelLarge)
                    ProgressLine(epgProgress(now), Modifier.weight(1f).padding(horizontal = 12.dp), height = 4.dp)
                    Text(clockTime(now.end), style = MaterialTheme.typography.labelLarge)
                }
                next?.let {
                    Text(
                        t("Danach: %1", "${clockTime(it.start)}  ${it.title}"),
                        style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (ui.items.size > 1) item { RoundButton(Icons.Filled.SkipPrevious, t("Vorheriger")) { actions.step(-1) } }
                if (ui.duration > 0) item { RoundButton(Icons.Filled.FastRewind, t("10 Sekunden zurück")) { actions.seekBy(-10_000) } }
                item {
                    RoundButton(
                        if (ui.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (ui.playing) t("Pause") else t("Abspielen"),
                        modifier = Modifier.focusRequester(play), size = 60.dp, filled = true,
                    ) { actions.togglePlay() }
                }
                if (ui.duration > 0) item { RoundButton(Icons.Filled.FastForward, t("30 Sekunden vor")) { actions.seekBy(30_000) } }
                if (ui.items.size > 1) item { RoundButton(Icons.Filled.SkipNext, t("Nächster")) { actions.step(1) } }
                item { Spacer(Modifier.width(12.dp)) }
                if (ui.canStartOver) item { LabelButton(Icons.Filled.Replay, t("Von vorn")) { actions.startOver() } }
                if (item.live) {
                    item {
                        val recording = Transfers.list.any { it.key == item.key && it.active }
                        LabelButton(Icons.Filled.FiberManualRecord, if (recording) t("Aufnahme beenden") else t("Aufnehmen"), tint = Klar.Live) { actions.record() }
                    }
                    if (ui.items.size > 1) item { LabelButton(Icons.AutoMirrored.Filled.List, t("Sender")) { actions.openPanel(PlayerPanel.CHANNELS) } }
                }
                item { LabelButton(Icons.Filled.Audiotrack, t("Ton")) { actions.openPanel(PlayerPanel.AUDIO) } }
                item { LabelButton(Icons.Filled.Subtitles, t("Untertitel")) { actions.openPanel(PlayerPanel.SUBTITLES) } }
                if (!item.live) item { LabelButton(Icons.Filled.Speed, "${ui.speed}×") { actions.openPanel(PlayerPanel.SPEED) } }
                item { LabelButton(Icons.Filled.AspectRatio, t("Bildformat")) { actions.openPanel(PlayerPanel.SCALE) } }
                item { LabelButton(Icons.Filled.Memory, ui.engineName.ifEmpty { "Player" }) { actions.openPanel(PlayerPanel.ENGINE) } }
                item { LabelButton(Icons.Filled.Nightlight, t("Schlaf-Timer")) { actions.openPanel(PlayerPanel.SLEEP) } }
            }
        }
    }
}

@Composable
private fun SeekBar(ui: PlayerUi, actions: PlayerActions, focus: FocusRequester) {
    var dragging by remember { mutableStateOf(false) }
    var value by remember { mutableFloatStateOf(0f) }
    var focused by remember { mutableStateOf(false) }
    val shown = if (dragging) (value * ui.duration).toLong() else ui.position
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTime(shown), style = MaterialTheme.typography.labelLarge)
        Slider(
            value = if (dragging) value else (ui.position.toFloat() / ui.duration).coerceIn(0f, 1f),
            onValueChange = {
                dragging = true
                value = it
            },
            onValueChangeFinished = {
                actions.seekTo((value * ui.duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = if (focused) Color.White else Klar.Accent,
                activeTrackColor = Klar.Accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.25f),
            ),
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp)
                .focusRequester(focus)
                .onFocusChanged { focused = it.hasFocus }
                // Mit der Fernbedienung in festen Schritten springen statt in Prozent
                .onPreviewKeyEvent {
                    val down = it.type == KeyEventType.KeyDown
                    when (it.key) {
                        Key.DirectionLeft -> if (down) actions.seekBy(-10_000L)
                        Key.DirectionRight -> if (down) actions.seekBy(10_000L)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> if (down) actions.togglePlay()
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                },
        )
        Text(formatTime(ui.duration), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RoundButton(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .size(size)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .background(if (focused) Klar.Accent else if (filled) Color.White else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = if (focused) Klar.OnAccent else if (filled) Color.Black else Color.White)
    }
}

@Composable
fun LabelButton(icon: ImageVector, label: String, tint: Color = Color.White, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier
            .height(44.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(Pill)
            .background(if (focused) Klar.Accent else Color.White.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (focused) Klar.OnAccent else tint, modifier = Modifier.size(20.dp))
        Text(label, color = if (focused) Klar.OnAccent else Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

private class Choice(val label: String, val selected: Boolean = false, val hint: String? = null, val onClick: () -> Unit)

@Composable
private fun Panel(panel: PlayerPanel, ui: PlayerUi, actions: PlayerActions) {
    val context = LocalAppContext.current
    val title: String
    val choices: List<Choice>
    when (panel) {
        PlayerPanel.AUDIO -> {
            title = t("Tonspur")
            choices = ui.audio.map { Choice(it.label, it.selected) { actions.pickAudio(it.id) } }
        }
        PlayerPanel.SUBTITLES -> {
            title = t("Untertitel")
            choices = buildList {
                add(Choice(t("Aus"), ui.text.none { it.selected }) { actions.pickText(null) })
                ui.text.forEach { add(Choice(it.label, it.selected) { actions.pickText(it.id) }) }
                add(Choice(t("Datei laden …")) { actions.loadSubtitleFile() })
                if (AppSettings.openSubtitlesKey.isNotBlank() && ui.item?.live == false) {
                    add(Choice(t("Im Internet suchen …"), hint = t("Der Titel wird an OpenSubtitles gesendet.")) { actions.findSubtitles() })
                }
            }
        }
        PlayerPanel.FIND_SUBTITLES -> {
            title = t("Gefundene Untertitel")
            choices = ui.found.orEmpty().map { hit -> Choice(hit.name, hint = "${hit.language} · ${hit.downloads}×") { actions.useFound(hit) } }
        }
        PlayerPanel.SPEED -> {
            title = t("Tempo")
            choices = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { Choice("$it×", it == ui.speed) { actions.speed(it) } }
        }
        PlayerPanel.SCALE -> {
            title = t("Bildformat")
            choices = listOf(
                Choice(t("Einpassen"), ui.scale == ScaleMode.FIT) { actions.scale(ScaleMode.FIT) },
                Choice(t("Ausfüllen (Ränder abschneiden)"), ui.scale == ScaleMode.ZOOM) { actions.scale(ScaleMode.ZOOM) },
                Choice(t("Strecken"), ui.scale == ScaleMode.STRETCH) { actions.scale(ScaleMode.STRETCH) },
            )
        }
        PlayerPanel.ENGINE -> {
            title = t("Player-Engine")
            choices = listOf(
                Choice("ExoPlayer", ui.engineName == "ExoPlayer", t("Sparsam, nutzt die Decoder des Geräts")) { actions.engine(EngineChoice.EXO) },
                Choice("VLC", ui.engineName == "VLC", t("Spielt fast jedes Format, auch Dolby und DTS")) { actions.engine(EngineChoice.VLC) },
            )
        }
        PlayerPanel.SLEEP -> {
            title = t("Schlaf-Timer")
            choices = listOf(Choice(t("Aus"), ui.sleepAt == 0L) { actions.sleep(0) }) +
                listOf(15, 30, 45, 60, 90, 120).map { Choice(t("%1 Minuten", it)) { actions.sleep(it) } }
        }
        PlayerPanel.CHANNELS -> {
            title = t("Sender")
            choices = ui.items.mapIndexed { i, it -> Choice("${i + 1}  ${it.title}", i == ui.index) { actions.jumpTo(i) } }
        }
        PlayerPanel.CAST -> {
            title = t("Übertragen auf")
            choices = buildList {
                Caster.connectedTo?.let { name -> add(Choice(t("Verbindung zu %1 beenden", name)) { Caster.disconnect(context) }) }
                Caster.routes.forEach { r -> add(Choice(r.name) { actions.cast(r.id) }) }
            }
        }
    }
    val empty = when (panel) {
        PlayerPanel.CAST -> Caster.problem ?: t("Suche Geräte im WLAN …")
        PlayerPanel.FIND_SUBTITLES -> if (ui.found == null) t("Suche …") else t("Nichts gefunden.")
        else -> t("Dieser Stream bietet nur eine Spur.")
    }
    SidePanel(title, choices, empty)
}

@Composable
private fun SidePanel(title: String, choices: List<Choice>, empty: String) {
    val first = remember { FocusRequester() }
    val start = choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    val state = rememberLazyListState(initialFirstVisibleItemIndex = (start - 3).coerceAtLeast(0))
    LaunchedEffect(choices.isEmpty()) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(360.dp).widthIn(max = 420.dp)
                .background(Klar.Bg.copy(alpha = 0.95f)).pointerInput(Unit) { detectTapGestures { } }.padding(vertical = 24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp))
            if (choices.isEmpty()) {
                Text(empty, color = Klar.TextDim, modifier = Modifier.padding(horizontal = 24.dp).focusRequester(first).focusable())
            }
            LazyColumn(state = state, contentPadding = PaddingValues(horizontal = 12.dp)) {
                itemsIndexed(choices) { i, c ->
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier.fillMaxWidth().then(if (i == start) Modifier.focusRequester(first) else Modifier)
                            .focusRing(shape).clip(shape).clickable(onClick = c.onClick).padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.label, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (c.selected) Klar.Accent else Klar.Text)
                            c.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Klar.TextDim) }
                        }
                        if (c.selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Klar.Accent)
                    }
                }
            }
        }
    }
}
