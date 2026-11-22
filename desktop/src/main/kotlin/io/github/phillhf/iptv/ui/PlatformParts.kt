package io.github.phillhf.iptv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.platform.AppContext
import io.github.phillhf.iptv.platform.LocalAppContext
import io.github.phillhf.iptv.platform.openPrefs
import io.github.phillhf.iptv.player.ScaleMode
import io.github.phillhf.iptv.player.VlcPlayer
import io.github.phillhf.iptv.player.VlcVideo
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.toPlayItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser

// Windows-Gegenstücke zu app/src/main/…/ui/PlatformVideo.kt und PlatformFiles.kt

// ---------- Kleine Videobilder ----------

/** Hält den Vorschau-Player. Vor dem Vollbild anhalten, damit nur eine Verbindung zum Anbieter offen ist. */
class LivePreview {
    var player by mutableStateOf<VlcPlayer?>(null)
        private set

    fun play(url: String) {
        val p = player ?: VlcPlayer(854, 480).also { player = it }
        p.load(PlayItem(key = url, title = "", url = url, live = true), 0)
    }

    fun stop() {
        player?.stop()
    }

    fun release() {
        player?.release()
        player = null
    }
}

@Composable
internal fun rememberLivePreview(): LivePreview {
    val preview = remember { LivePreview() }
    DisposableEffect(Unit) { onDispose { preview.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { preview.release() }
    return preview
}

/** Startet die Vorschau erst, wenn die Auswahl kurz auf einem Sender bleibt. */
@Composable
internal fun PreviewVideo(preview: LivePreview, url: String, modifier: Modifier) {
    if (LocalInspectionMode.current) {
        AsyncImage("demo://backdrop/live", null, modifier, contentScale = ContentScale.Crop)
        return
    }
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { resumes++ }
    LaunchedEffect(url, resumes) {
        preview.stop()
        delay(700)
        preview.play(url)
    }
    preview.player?.let { VlcVideo(it, ScaleMode.FIT, modifier) }
}

@Composable
internal fun TileVideo(s: Stream, sound: Boolean) {
    if (LocalInspectionMode.current) {
        AsyncImage("demo://backdrop/${s.id}", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        return
    }
    var starts by remember { mutableIntStateOf(0) }
    var failures by remember(s.key) { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<VlcPlayer?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { starts++ }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player?.release()
        player = null
    }
    DisposableEffect(s.key, starts) {
        val p = VlcPlayer(960, 540)
        p.listener = object : VlcPlayer.Listener {
            override fun onError() {
                failures++
            }

            override fun onEnded() {
                failures++
            }
        }
        p.setVolume(0f)
        p.load(s.toPlayItem(), 0)
        player = p
        onDispose {
            p.release()
            if (player === p) player = null
        }
    }
    LaunchedEffect(failures) {
        if (failures in 1..5) {
            delay(1_500L * failures)
            player?.load(s.toPlayItem(), 0)
        }
    }
    LaunchedEffect(player, sound) { player?.setVolume(if (sound) 1f else 0f) }
    player?.let { VlcVideo(it, ScaleMode.FIT, Modifier.fillMaxSize()) }
}

// ---------- Dateien und Berechtigungen ----------

/** Unter Windows ist dafür keine Erlaubnis nötig. */
@Composable
fun rememberNotificationPermission(): () -> Unit = {}

fun openExactAlarmSettings(@Suppress("UNUSED_PARAMETER") context: AppContext) = Unit

private object LocalFolders {
    private val video = setOf("mkv", "ts", "avi", "mp4", "m4v", "m2ts", "mts", "flv", "mov", "wmv", "webm", "mpg", "mpeg", "vob", "3gp", "ogv")
    private val audio = setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "wma", "ac3", "dts")

    fun list(context: AppContext): List<File> =
        context.openPrefs("folders").getString("list", "").orEmpty().split('\n').filter { it.isNotBlank() }.map { File(it) }

    fun save(context: AppContext, folders: List<File>) {
        context.openPrefs("folders").edit().putString("list", folders.map { it.path }.distinct().joinToString("\n")).apply()
    }

    fun name(f: File): String = f.name.ifEmpty { f.path }

    fun isAudio(f: File) = f.extension.lowercase() in audio

    fun children(folder: File): List<File> = folder.listFiles().orEmpty()
        .filter { !it.isHidden && (it.isDirectory || it.extension.lowercase() in video || isAudio(it)) }
        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
}

@Composable
internal fun LocalFiles(vm: AppViewModel) {
    val context = LocalAppContext.current
    var folders by remember { mutableStateOf(LocalFolders.list(context)) }
    var root by remember { mutableStateOf<File?>(null) }
    var current by remember { mutableStateOf<File?>(null) }
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    LaunchedEffect(current) {
        val c = current
        entries = if (c == null) emptyList() else withContext(Dispatchers.IO) { runCatching { LocalFolders.children(c) }.getOrDefault(emptyList()) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val open = current
        if (open == null) {
            folders.forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FileRow(Icons.Filled.Folder, f.path, Modifier.weight(1f)) {
                        root = f
                        current = f
                    }
                    IconButton(
                        onClick = {
                            folders = folders - f
                            LocalFolders.save(context, folders)
                        },
                        modifier = Modifier.focusRing(CircleShape),
                    ) { Icon(Icons.Filled.Delete, t("Ordner entfernen")) }
                }
            }
            OutlinedButton(
                onClick = {
                    val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.DIRECTORIES_ONLY }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        folders = (folders + chooser.selectedFile).distinct()
                        LocalFolders.save(context, folders)
                    }
                },
                modifier = Modifier.focusRing(Pill),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Text(t("Ordner mit Videos oder Musik hinzufügen"), modifier = Modifier.padding(start = 8.dp))
            }
        } else {
            FileRow(Icons.Filled.FolderOpen, ".. " + LocalFolders.name(open)) { current = if (open == root) null else open.parentFile }
            val files = entries.filterNot { it.isDirectory }
            if (entries.isEmpty()) Hint(t("Hier liegen keine Videos oder Musikdateien."))
            entries.forEach { e ->
                FileRow(if (e.isDirectory) Icons.Filled.Folder else if (LocalFolders.isAudio(e)) Icons.Filled.Audiotrack else Icons.Filled.Movie, e.name) {
                    if (e.isDirectory) {
                        current = e
                    } else {
                        val items = files.map { f ->
                            val uri = f.toURI().toASCIIString().replaceFirst("file:/", "file:///")
                            PlayItem(key = "LOCAL:$uri", title = f.nameWithoutExtension, url = uri, live = false, local = true)
                        }
                        vm.playLocal(context, items, files.indexOf(e))
                    }
                }
            }
        }
    }
}
