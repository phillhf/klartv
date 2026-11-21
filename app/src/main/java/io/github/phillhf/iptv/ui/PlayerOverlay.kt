package io.github.phillhf.iptv.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.phillhf.iptv.PlayItem
import io.github.phillhf.iptv.data.EpgEntry
import kotlinx.coroutines.delay

/** Senderinfo unten im Vollbild: Logo, Name, Uhrzeit, Jetzt/Danach mit Fortschritt. */
@Composable
fun PlayerOverlay(item: PlayItem?, epg: List<EpgEntry>, visible: Boolean, message: String?) {
    val shown = item
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val still = LocalInspectionMode.current
    LaunchedEffect(Unit) {
        while (!still) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible && shown != null,
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut() + slideOutVertically { it / 3 },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val p = shown ?: return@AnimatedVisibility
            val (current, next) = currentAndNext(epg, now)
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.75f), Color.Black.copy(alpha = 0.92f))))
                    .padding(start = 40.dp, end = 40.dp, top = 48.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (p.live) {
                        Logo(p.image, p.title, 64.dp)
                        Spacer(Modifier.width(18.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (p.live) Badge("LIVE", color = Klar.Live)
                            Text(p.title, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        p.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Klar.TextDim, maxLines = 1) }
                    }
                    Text(clockTime(now), style = MaterialTheme.typography.headlineSmall, color = Klar.Text)
                }
                if (current != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("JETZT", style = MaterialTheme.typography.labelLarge, color = Klar.Accent)
                        Text("   ${timeRange(current.start, current.end)}", style = MaterialTheme.typography.labelLarge, color = Klar.TextDim)
                    }
                    Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    ProgressLine(epgProgress(current, now), Modifier.fillMaxWidth(), height = 4.dp)
                }
                if (next != null) {
                    Row {
                        Text("DANACH", style = MaterialTheme.typography.labelLarge, color = Klar.Accent2)
                        Text(
                            "   ${clockTime(next.start)}  ${next.title}",
                            style = MaterialTheme.typography.labelLarge,
                            color = Klar.TextDim,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (message != null) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(28.dp)
                    .widthIn(max = 640.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.8f))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(message, color = Klar.Text)
            }
        }
    }
}
