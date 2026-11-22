package io.github.phillhf.iptv.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.transfer.Transfer
import io.github.phillhf.iptv.transfer.TransferState
import io.github.phillhf.iptv.transfer.TransferType
import io.github.phillhf.iptv.transfer.Transfers
import java.text.DateFormat
import java.util.Date

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%d MB".format(bytes shr 20)
    else -> "%d KB".format(bytes shr 10)
}

@Composable
fun MediaLibraryScreen(vm: AppViewModel, wide: Boolean) {
    val side = if (wide) 48.dp else 16.dp
    val shown = Transfers.list.filterNot { vm.hidden(it.parentKey) }
    val downloads = shown.filter { it.type == TransferType.DOWNLOAD }
    val records = shown.filter { it.type == TransferType.RECORD }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = side, end = side, top = 24.dp, bottom = 32.dp)) {
        item { Text(t("Mediathek"), style = MaterialTheme.typography.headlineMedium) }
        item { SectionTitle(t("Aufnahmen")) }
        if (records.isEmpty()) item { Hint(t("Noch keine Aufnahmen. Starte sie im Player oder im Programm.")) }
        items(records, key = { it.id }) { TransferRow(vm, it) }
        item { SectionTitle(t("Downloads")) }
        if (downloads.isEmpty()) item { Hint(t("Noch keine Downloads. Auf der Seite eines Films oder einer Folge gibt es „Herunterladen“.")) }
        items(downloads, key = { it.id }) { TransferRow(vm, it) }
        item { SectionTitle(t("Auf diesem Gerät")) }
        item { LocalFiles(vm) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge, color = Klar.Accent, modifier = Modifier.padding(top = 24.dp, bottom = 8.dp))
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = Klar.TextDim)
}

private fun describe(x: Transfer): String {
    val time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    return when (x.state) {
        TransferState.SCHEDULED -> t("Geplant: %1", "${time.format(Date(x.startAt))} – ${clockTime(x.endAt)}")
        TransferState.QUEUED -> t("Wartet …")
        TransferState.RUNNING -> if (x.type == TransferType.RECORD) {
            t("Nimmt auf bis %1", clockTime(x.endAt)) + " · " + formatSize(x.bytes)
        } else {
            formatSize(x.bytes) + if (x.total > 0) " / ${formatSize(x.total)}" else ""
        }
        TransferState.DONE -> listOfNotNull(formatSize(x.bytes), if (x.type == TransferType.RECORD) time.format(Date(x.startAt)) else null, x.error).joinToString(" · ")
        TransferState.FAILED -> t("Fehlgeschlagen: %1", t(x.error ?: "unbekannt"))
    }
}

@Composable
private fun TransferRow(vm: AppViewModel, x: Transfer) {
    val context = LocalAppContext.current
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).focusRing(shape).clip(shape)
                .clickable { if (x.state == TransferState.DONE) vm.playTransfer(context, x) else if (x.state == TransferState.FAILED && x.type == TransferType.DOWNLOAD) Transfers.retry(x) }
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Logo(x.image, x.title, 56.dp)
            Column(Modifier.padding(start = 14.dp)) {
                Text(x.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                x.subtitle?.let { Text(it, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = Klar.TextDim) }
                Text(
                    describe(x), style = MaterialTheme.typography.bodySmall,
                    color = if (x.state == TransferState.FAILED) MaterialTheme.colorScheme.error else Klar.TextDim,
                )
                if (x.state == TransferState.RUNNING && x.total > 0) {
                    LinearProgressIndicator(progress = { x.progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp), color = Klar.Accent)
                }
            }
        }
        if (x.active) {
            IconButton(onClick = { Transfers.stop(x) }, modifier = Modifier.focusRing(CircleShape)) { Icon(Icons.Filled.Stop, t("Anhalten")) }
        } else if (x.state == TransferState.FAILED && x.type == TransferType.DOWNLOAD) {
            IconButton(onClick = { Transfers.retry(x) }, modifier = Modifier.focusRing(CircleShape)) { Icon(Icons.Filled.Refresh, t("Fortsetzen")) }
        }
        IconButton(onClick = { Transfers.remove(x) }, modifier = Modifier.focusRing(CircleShape)) { Icon(Icons.Filled.Delete, t("Löschen")) }
    }
}

// ---------- Dateien auf dem Gerät ----------

@Composable
internal fun FileRow(icon: ImageVector, name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Klar.Accent, modifier = Modifier.size(24.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}
