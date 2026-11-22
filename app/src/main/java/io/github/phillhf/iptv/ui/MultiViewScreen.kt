package io.github.phillhf.iptv.ui

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import io.github.phillhf.iptv.AppViewModel
import io.github.phillhf.iptv.Players
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.data.Stream
import io.github.phillhf.iptv.t
import io.github.phillhf.iptv.toMediaItem
import io.github.phillhf.iptv.toPlayItem
import kotlinx.coroutines.delay

/** Bis zu vier Sender gleichzeitig; Ton kommt vom gewählten Bild. */
@Composable
fun MultiViewScreen(vm: AppViewModel) {
    LaunchedEffect(vm.active?.id) { vm.ensureLoaded(Kind.LIVE) }
    val tiles = remember { mutableStateListOf<Stream?>(null, null, null, null) }
    var sound by remember { mutableIntStateOf(0) }
    var picking by remember { mutableStateOf<Int?>(null) }
    val preview = LocalInspectionMode.current
    LaunchedEffect(Unit) {
        // Für Screenshots schon belegt
        if (preview) vm.readyCatalog(Kind.LIVE)?.streams?.take(3)?.forEachIndexed { i, s -> tiles[i] = s }
    }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        TitleBar(t("Mehrere Sender"), onBack = { vm.back() })
        Text(
            t("Jedes Bild braucht eine eigene Verbindung. Dein Anbieter muss so viele gleichzeitig erlauben."),
            style = MaterialTheme.typography.bodySmall, color = Klar.TextDim, modifier = Modifier.padding(start = 16.dp, bottom = 6.dp),
        )
        for (row in 0..1) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (column in 0..1) {
                    val i = row * 2 + column
                    MultiTile(
                        tiles[i], sound == i, Modifier.weight(1f).fillMaxSize().padding(3.dp),
                        onFocused = { if (tiles[i] != null) sound = i },
                        onClick = { if (tiles[i] != null && sound != i) sound = i else picking = i },
                    )
                }
            }
        }
    }
    picking?.let { i ->
        ChannelPicker(
            vm,
            canRemove = tiles[i] != null,
            onPick = {
                tiles[i] = it
                sound = i
                picking = null
            },
            onRemove = {
                tiles[i] = null
                picking = null
            },
            onDismiss = { picking = null },
        )
    }
}

@Composable
private fun MultiTile(s: Stream?, sound: Boolean, modifier: Modifier, onFocused: () -> Unit, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clip(CardShape)
            .background(Klar.Surface)
            .border(3.dp, if (focused) Color.White else if (sound && s != null) Klar.Accent else Color.Transparent, CardShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (s == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Klar.Accent)
                Text(t("Sender wählen"), color = Klar.TextDim)
            }
        } else {
            TileVideo(s, sound)
            Row(Modifier.align(Alignment.BottomStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(s.name)
                if (sound) Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = t("Ton an"), tint = Klar.Accent, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun TileVideo(s: Stream, sound: Boolean) {
    if (LocalInspectionMode.current) {
        AsyncImage("demo://backdrop/${s.id}", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        return
    }
    val context = LocalContext.current
    var starts by remember { mutableIntStateOf(0) }
    var failures by remember(s.key) { mutableIntStateOf(0) }
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { starts++ }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        player?.release()
        player = null
    }
    DisposableEffect(s.key, starts) {
        // Ohne Audiofokus, sonst würden sich die vier Player gegenseitig anhalten
        val p = Players.create(context, handleAudioFocus = false)
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                failures++
            }
        })
        p.setMediaItem(s.toPlayItem().toMediaItem())
        p.prepare()
        p.playWhenReady = true
        player = p
        onDispose {
            p.release()
            if (player === p) player = null
        }
    }
    LaunchedEffect(failures) {
        if (failures in 1..5) {
            delay(1_500L * failures)
            player?.prepare()
        }
    }
    LaunchedEffect(player, sound) { player?.volume = if (sound) 1f else 0f }
    AndroidView(
        factory = {
            PlayerView(it).apply {
                useController = false
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                keepScreenOn = true
            }
        },
        update = { it.player = player },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun ChannelPicker(vm: AppViewModel, canRemove: Boolean, onPick: (Stream) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val favorites = vm.favoriteStreams(Kind.LIVE)
    val all = vm.readyCatalog(Kind.LIVE)?.streams.orEmpty()
    val list = remember(query, all, favorites) {
        val q = query.trim()
        if (q.isEmpty()) (favorites + all).distinctBy { it.key }.take(300) else all.filter { it.name.contains(q, ignoreCase = true) }.take(300)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Klar.Surface,
        title = { Text(t("Sender wählen")) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, label = { Text(t("Suche")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                LazyColumn(Modifier.height(260.dp).padding(top = 8.dp)) {
                    items(list) { s ->
                        val shape = RoundedCornerShape(8.dp)
                        Row(
                            Modifier.fillMaxWidth().focusRing(shape).clip(shape).clickable { onPick(s) }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Logo(s.logo, s.name, 36.dp)
                            Text(s.name, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRing(Pill)) { Text(t("Abbrechen")) } },
        dismissButton = { if (canRemove) TextButton(onClick = onRemove, modifier = Modifier.focusRing(Pill)) { Text(t("Bild leeren")) } },
    )
}
