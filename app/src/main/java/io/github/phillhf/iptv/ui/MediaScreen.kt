package io.github.phillhf.iptv.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.transfer.Transfer
import io.github.phillhf.iptv.transfer.TransferState
import io.github.phillhf.iptv.transfer.TransferType
import io.github.phillhf.iptv.transfer.Transfers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Fragt einmal nach Benachrichtigungen, damit laufende Downloads und Aufnahmen sichtbar sind. */
@Composable
fun rememberNotificationPermission(): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return {
        if (Transfers.needsNotificationPermission(context)) {
            runCatching { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }
}

fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT >= 31) {
        runCatching {
            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
        }
    }
}

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
private fun Hint(text: String) {
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
    val context = LocalContext.current
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

private data class LocalEntry(val id: String, val name: String, val folder: Boolean, val audio: Boolean)

/** Ordner, die der Nutzer freigegeben hat. Dafür braucht die App keinen Zugriff auf alle Medien. */
private object LocalFolders {
    fun list(context: Context): List<Uri> =
        context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }

    fun name(tree: Uri): String =
        DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/').ifEmpty { "/" }

    fun children(context: Context, tree: Uri, documentId: String): List<LocalEntry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<LocalEntry>()
        context.contentResolver.query(uri, columns, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                val mime = c.getString(2).orEmpty()
                val folder = mime == DocumentsContract.Document.MIME_TYPE_DIR
                val audio = mime.startsWith("audio/")
                val video = mime.startsWith("video/") || name.substringAfterLast('.').lowercase() in setOf("mkv", "ts", "avi", "mp4", "m2ts", "flv")
                if (folder || audio || video) out += LocalEntry(c.getString(0), name, folder, audio)
            }
        }
        return out.sortedWith(compareBy({ !it.folder }, { it.name.lowercase() }))
    }
}

@Composable
private fun LocalFiles(vm: AppViewModel) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf(LocalFolders.list(context)) }
    var tree by remember { mutableStateOf<Uri?>(null) }
    val path = remember { mutableStateListOf<Pair<String, String>>() }
    var entries by remember { mutableStateOf<List<LocalEntry>>(emptyList()) }
    var problem by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            folders = LocalFolders.list(context)
        }
    }
    LaunchedEffect(tree, path.toList()) {
        val t = tree ?: return@LaunchedEffect
        val id = path.lastOrNull()?.first ?: DocumentsContract.getTreeDocumentId(t)
        entries = withContext(Dispatchers.IO) { runCatching { LocalFolders.children(context, t, id) }.getOrDefault(emptyList()) }
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val open = tree
        if (open == null) {
            folders.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileRow(Icons.Filled.Folder, LocalFolders.name(f), Modifier.weight(1f)) {
                        path.clear()
                        tree = f
                    }
                    IconButton(
                        onClick = {
                            runCatching { context.contentResolver.releasePersistableUriPermission(f, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                            folders = LocalFolders.list(context)
                        },
                        modifier = Modifier.focusRing(CircleShape),
                    ) { Icon(Icons.Filled.Delete, t("Ordner entfernen")) }
                }
            }
            OutlinedButton(
                onClick = {
                    problem = null
                    runCatching { picker.launch(null) }.onFailure { problem = t("Auf diesem Gerät gibt es keine Dateiauswahl.") }
                },
                modifier = Modifier.focusRing(Pill),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Text(t("Ordner mit Videos oder Musik hinzufügen"), modifier = Modifier.padding(start = 8.dp))
            }
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        } else {
            FileRow(Icons.Filled.FolderOpen, ".. " + (path.lastOrNull()?.second ?: LocalFolders.name(open))) {
                if (path.isEmpty()) tree = null else path.removeAt(path.lastIndex)
            }
            val files = entries.filterNot { it.folder }
            if (entries.isEmpty()) Hint(t("Hier liegen keine Videos oder Musikdateien."))
            entries.forEach { e ->
                FileRow(if (e.folder) Icons.Filled.Folder else if (e.audio) Icons.Filled.Audiotrack else Icons.Filled.Movie, e.name) {
                    if (e.folder) {
                        path.add(e.id to e.name)
                    } else {
                        val items = files.map { f ->
                            val uri = DocumentsContract.buildDocumentUriUsingTree(open, f.id).toString()
                            PlayItem(key = "LOCAL:$uri", title = f.name.substringBeforeLast('.'), url = uri, live = false, local = true)
                        }
                        vm.playLocal(context, items, files.indexOf(e))
                    }
                }
            }
        }
    }
}

@Composable
private fun FileRow(icon: ImageVector, name: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Klar.Accent, modifier = Modifier.size(24.dp))
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}
