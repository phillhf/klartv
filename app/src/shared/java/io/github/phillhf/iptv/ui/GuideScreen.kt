package io.github.phillhf.iptv.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.EpgState
import io.github.phillhf.iptv.data.Catalog
import io.github.phillhf.iptv.data.Catchup
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Load
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.platform.ListScrollbar
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.platform.mouseDriven
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.toPlayItem
import io.github.phillhf.iptv.transfer.TransferState
import io.github.phillhf.iptv.transfer.TransferType
import io.github.phillhf.iptv.transfer.Transfers
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

private const val MINUTE = 60_000L

/** Breite einer Minute im Raster. */
private val MinuteWidth = 5.dp
private val RowHeight = 62.dp

/** Eine Sendung in der Zeile; Lücken im Programm werden zu leeren Feldern. */
data class GuideCell(val start: Long, val end: Long, val entry: EpgEntry?)

/** Füllt einen Tag lückenlos, damit alle Zeilen gleich lang sind und die Zeiten übereinander stehen. */
fun guideCells(list: List<EpgEntry>, from: Long, to: Long): List<GuideCell> {
    val out = ArrayList<GuideCell>()
    var at = from
    for (e in list.sortedBy { it.start }) {
        val start = maxOf(e.start, at)
        val end = minOf(e.end, to)
        if (end <= start) continue
        if (start > at) out += GuideCell(at, start, null)
        out += GuideCell(start, end, e)
        at = end
    }
    if (at < to) out += GuideCell(at, to, null)
    return out
}

fun dayStart(offset: Int, now: Long = System.currentTimeMillis()): Long =
    Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().plusDays(offset.toLong())
        .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun dayLabel(offset: Int): String = when (offset) {
    0 -> t("Heute")
    1 -> t("Morgen")
    -1 -> t("Gestern")
    else -> DateTimeFormatter.ofPattern("EEE d. MMM", Locale.getDefault()).format(Instant.ofEpochMilli(dayStart(offset)).atZone(ZoneId.systemDefault()))
}

@Composable
fun GuideScreen(vm: AppViewModel, wide: Boolean) {
    LaunchedEffect(vm.active?.id) { vm.ensureLoaded(Kind.LIVE) }
    when (val state = vm.catalog(Kind.LIVE)) {
        Load.Idle, Load.Loading -> LoadingBox(t("Lade Sender …"))
        is Load.Error -> ErrorBox(state.message) { vm.ensureLoaded(Kind.LIVE, force = true) }
        is Load.Ready -> Guide(vm, state.value, wide)
    }
}

@Composable
private fun Guide(vm: AppViewModel, catalog: Catalog, wide: Boolean) {
    val context = LocalAppContext.current
    val current = vm.lastCategory[Kind.LIVE] ?: Catalog.ALL
    val list = remember(catalog, current, vm.favorites) {
        if (current == FAVORITES) vm.favoriteStreams(Kind.LIVE) else catalog.inCategory(current)
    }
    var day by rememberSaveable { mutableIntStateOf(0) }
    val from = dayStart(day)
    val to = dayStart(day + 1)
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val still = LocalInspectionMode.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (!still) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(day) {
        // Heute bei jetzt beginnen, sonst zur Hauptsendezeit
        val at = if (day == 0) System.currentTimeMillis() - 5 * MINUTE else from + 18 * 60 * MINUTE
        scroll.scrollTo(with(density) { (MinuteWidth * ((at - from) / MINUTE).toFloat()).roundToPx() }.coerceAtLeast(0))
    }
    var open by remember { mutableStateOf<Pair<Stream, EpgEntry>?>(null) }
    val nameWidth = if (wide) 190.dp else 104.dp

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(t("Programm"), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            if (mouseDriven) {
                val scope = rememberCoroutineScope()
                val hour = with(density) { (MinuteWidth * 60f).toPx() }
                LabelButton(Icons.Filled.FastRewind, t("1 Stunde")) { scope.launch { scroll.animateScrollBy(-hour) } }
                Spacer(Modifier.width(8.dp))
                LabelButton(Icons.Filled.FastForward, t("1 Stunde")) { scope.launch { scroll.animateScrollBy(hour) } }
                Spacer(Modifier.width(24.dp))
            }
            RoundButton(Icons.Filled.ChevronLeft, t("Tag zurück")) { if (day > -7) day-- }
            Text(dayLabel(day), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 6.dp))
            RoundButton(Icons.Filled.ChevronRight, t("Tag vor")) { if (day < 4) day++ }
        }
        EpgNotice(vm)
        CategoryChips(liveCategories(vm, catalog), current, { vm.chooseCategory(Kind.LIVE, it) }, locked = catalog.lockedIds)
        // Zeitleiste
        Row(Modifier.fillMaxWidth().padding(start = 12.dp)) {
            Spacer(Modifier.width(nameWidth))
            Row(Modifier.weight(1f).horizontalScroll(scroll)) {
                repeat(((to - from) / (30 * MINUTE)).toInt()) { i ->
                    Text(
                        clockTime(from + i * 30 * MINUTE),
                        style = MaterialTheme.typography.labelMedium,
                        color = Klar.TextDim,
                        modifier = Modifier.width(MinuteWidth * 30).padding(start = 8.dp, bottom = 4.dp),
                    )
                }
            }
        }
        val rows = rememberLazyListState()
        Row(Modifier.weight(1f).fillMaxWidth().padding(start = 12.dp)) {
            LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = rows) {
                items(list) { s ->
                    GuideRow(
                        vm, s, from, to, now, scroll, nameWidth,
                        onChannel = { vm.openStream(context, s, list) },
                        onOpen = { open = s to it },
                    )
                }
            }
            ListScrollbar(rows)
        }
    }
    open?.let { (s, e) ->
        ProgrammeDialog(vm, s, e, onDismiss = { open = null }, onWatch = {
            open = null
            vm.play(context, list.map { it.toPlayItem() }, list.indexOf(s).coerceAtLeast(0))
        })
    }
}

/** Stand des Programmführers, nur wenn es etwas zu sagen gibt. */
@Composable
private fun EpgNotice(vm: AppViewModel) {
    when (val s = vm.epgState) {
        is EpgState.Loading -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
            Text(t("Lade Programmführer … %1 Sendungen", s.count), style = MaterialTheme.typography.bodySmall, color = Klar.TextDim)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp), color = Klar.Accent)
        }
        is EpgState.Error -> Text(
            t("Programmführer nicht geladen: %1", s.message),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        EpgState.None -> if (!vm.hasEpg) Text(
            t("Dein Anbieter nennt keinen Programmführer. Du kannst in den Einstellungen eine XMLTV-Adresse eintragen."),
            style = MaterialTheme.typography.bodySmall, color = Klar.TextDim,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        else -> Unit
    }
}

@Composable
private fun GuideRow(
    vm: AppViewModel,
    s: Stream,
    from: Long,
    to: Long,
    now: Long,
    scroll: ScrollState,
    nameWidth: Dp,
    onChannel: () -> Unit,
    onOpen: (EpgEntry) -> Unit,
) {
    var cells by remember(s.key, from) { mutableStateOf<List<GuideCell>?>(null) }
    LaunchedEffect(s.key, from, vm.epgState is EpgState.Ready) { cells = guideCells(vm.programmes(s, from, to), from, to) }
    val shape = RoundedCornerShape(8.dp)
    Row(Modifier.fillMaxWidth().height(RowHeight), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.width(nameWidth).fillMaxHeight().padding(vertical = 2.dp).focusRing(shape).clip(shape).clickable(onClick = onChannel).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(s.logo, s.name, 38.dp)
            Text(s.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 8.dp))
        }
        Box(Modifier.weight(1f).fillMaxHeight().horizontalScroll(scroll)) {
            Row(Modifier.fillMaxHeight()) {
                cells?.forEach { c -> Cell(s, c, now, MinuteWidth * ((c.start - from) / MINUTE.toFloat()), scroll, onOpen) } ?: Spacer(Modifier.width(MinuteWidth * ((to - from) / MINUTE).toInt()))
            }
            if (now in from until to) {
                Box(Modifier.offset(x = MinuteWidth * ((now - from) / MINUTE).toFloat()).width(2.dp).fillMaxHeight().background(Klar.Live))
            }
        }
    }
}

@Composable
private fun Cell(s: Stream, c: GuideCell, now: Long, x: Dp, scroll: ScrollState, onOpen: (EpgEntry) -> Unit) {
    val width = MinuteWidth * ((c.end - c.start) / MINUTE.toFloat())
    val e = c.entry
    if (e == null) {
        Box(Modifier.width(width).fillMaxHeight().padding(2.dp).clip(RoundedCornerShape(8.dp)).background(Klar.Surface.copy(alpha = 0.35f)))
        return
    }
    var focused by remember { mutableStateOf(false) }
    val running = now >= e.start && now < e.end
    val replay = e.end <= now && Catchup.available(s, e.start, now)
    val shape = RoundedCornerShape(8.dp)
    Column(
        Modifier
            .width(width)
            .fillMaxHeight()
            .padding(2.dp)
            .onFocusChanged { focused = it.isFocused }
            .clip(shape)
            .background(
                when {
                    focused -> Klar.Accent.copy(alpha = 0.35f)
                    running -> Klar.SurfaceHigh
                    else -> Klar.Surface.copy(alpha = if (e.end <= now && !replay) 0.45f else 1f)
                },
            )
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable { onOpen(e) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        // Titel bleibt sichtbar, auch wenn der Anfang der Sendung links aus dem Bild gerollt ist
        val stick = Modifier.offset {
            val shift = scroll.value - x.roundToPx()
            IntOffset(shift.coerceIn(0, (width.roundToPx() - 72.dp.roundToPx()).coerceAtLeast(0)), 0)
        }
        Row(stick, verticalAlignment = Alignment.CenterVertically) {
            if (replay) Icon(Icons.Filled.Replay, contentDescription = t("Nachschauen möglich"), tint = Klar.Accent, modifier = Modifier.height(16.dp).padding(end = 4.dp))
            Text(
                e.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                color = if (e.end <= now && !replay) Klar.TextDim else Klar.Text,
            )
        }
        Text(timeRange(e.start, e.end), stick, maxLines = 1, overflow = TextOverflow.Clip, style = MaterialTheme.typography.labelSmall, color = Klar.TextDim)
        if (running) ProgressLine(epgProgress(e, now), Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** Details einer Sendung mit allem, was man damit tun kann. */
@Composable
fun ProgrammeDialog(vm: AppViewModel, s: Stream, e: EpgEntry, onDismiss: () -> Unit, onWatch: () -> Unit) {
    val context = LocalAppContext.current
    val now = System.currentTimeMillis()
    var note by remember { mutableStateOf<String?>(null) }
    val askNotify = rememberNotificationPermission()
    val planned = Transfers.list.firstOrNull {
        it.type == TransferType.RECORD && it.key == s.key && it.title == e.title && it.endAt == e.end + 5 * MINUTE &&
            (it.active || it.state == TransferState.SCHEDULED)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Klar.Surface,
        title = { Text(e.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${s.name}  ·  ${timeRange(e.start, e.end)}", color = Klar.Accent)
                if (e.description.isNotBlank()) Text(e.description, maxLines = 8, overflow = TextOverflow.Ellipsis)
                if (now < e.end) DialogAction(Icons.Filled.LiveTv, t("Sender ansehen"), onWatch)
                if (Catchup.available(s, e.start, now)) {
                    DialogAction(Icons.Filled.Replay, if (e.end > now) t("Von vorn ansehen") else t("Sendung nachschauen")) {
                        vm.playCatchup(context, s, e) { note = t("Der Anbieter stellt diese Sendung nicht bereit.") }
                    }
                }
                if (now < e.end) {
                    if (planned != null) {
                        DialogAction(Icons.Filled.FiberManualRecord, t("Aufnahme abbrechen")) {
                            Transfers.stop(planned)
                            note = t("Aufnahme abgebrochen.")
                        }
                    } else {
                        DialogAction(Icons.Filled.FiberManualRecord, t("Aufnehmen")) {
                            if (e.start > now && !Transfers.canSchedule()) {
                                note = t("Erlaube Klar TV zuerst genaue Wecker, sonst startet die Aufnahme nicht.")
                                openExactAlarmSettings(context)
                            } else {
                                askNotify()
                                vm.record(s, e)
                                note = if (e.start > now) t("Aufnahme geplant.") else t("Aufnahme läuft.")
                            }
                        }
                    }
                }
                note?.let { Text(it, color = Klar.Gold) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(Pill)) { Text(t("Schließen")) } },
    )
}

@Composable
private fun DialogAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    LabelButton(icon, label, onClick = onClick)
}
