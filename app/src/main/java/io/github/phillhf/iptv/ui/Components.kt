package io.github.phillhf.iptv.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.phillhf.iptv.data.Kind

private val Colors = darkColorScheme(
    primary = Color(0xFF38BDF8),
    onPrimary = Color(0xFF00202E),
    primaryContainer = Color(0xFF0C4A6E),
    onPrimaryContainer = Color(0xFFE0F2FE),
    secondaryContainer = Color(0xFF1E3A5F),
    onSecondaryContainer = Color(0xFFE0F2FE),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5E7EB),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE5E7EB),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF94A3B8),
    surfaceContainer = Color(0xFF111A2E),
)

@Composable
fun KlarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}

val Pill = RoundedCornerShape(50)

fun isTv(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/**
 * Deutlicher Rahmen um das Element, das gerade den Fokus hat. Auf dem Fernseher sieht man nur so,
 * wo man mit der Fernbedienung steht. Muss vor clickable stehen.
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = if (focused) MaterialTheme.colorScheme.primary else Color.Transparent
    val fill = if (focused) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(3.dp, ring, shape)
        .background(fill, shape)
}

@Composable
fun Centered(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
fun LoadingBox(text: String = "Lade Inhalte …") {
    Centered {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(text)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Centered {
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.focusRing(Pill)) { Text("Erneut versuchen") }
    }
}

@Composable
fun TitleBar(title: String, onBack: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.focusRing(CircleShape)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
        }
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
fun Logo(url: String?, fallback: String, size: Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(fallback.trim().take(2).uppercase(), style = MaterialTheme.typography.labelLarge)
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}

@Composable
fun PosterImage(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(
        modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, maxLines = 4, textAlign = TextAlign.Center, modifier = Modifier.padding(8.dp))
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/**
 * Großes, abgedunkeltes Hintergrundbild wie im Mediacenter. Nach unten und links läuft es in den
 * Hintergrund aus, damit Text darauf lesbar bleibt. Ist nur ein Cover da, wird es weichgezeichnet.
 */
@Composable
fun Backdrop(url: String?, isCover: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    val bg = MaterialTheme.colorScheme.background
    Box(modifier) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (isCover) 0.35f else 0.5f,
                modifier = Modifier.fillMaxSize().then(if (isCover) Modifier.blur(24.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.5f to bg.copy(alpha = 0.55f), 1f to bg)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.85f), 0.65f to Color.Transparent)))
    }
}

/** Titel, Eckdaten und Beschreibung über dem Cover-Raster bzw. auf der Detailseite. */
@Composable
fun HeroInfo(title: String, meta: String, plot: String?, modifier: Modifier = Modifier, plotLines: Int = 4) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (!plot.isNullOrBlank()) {
            Text(
                plot,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = plotLines,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
            )
        }
    }
}

fun metaLine(vararg parts: String?): String =
    parts.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.joinToString("  ·  ")

@Composable
fun PosterCard(title: String, image: String?, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .onFocusChanged { if (it.isFocused) onFocused?.invoke() }
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(6.dp),
    ) {
        PosterImage(image, title, Modifier.fillMaxWidth())
        Text(
            title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** Zeile für Sender, Suchtreffer und Folgen. Der Stern ist ein eigenes Ziel für die Fernbedienung. */
@Composable
fun StreamRow(
    title: String,
    subtitle: String?,
    image: String?,
    onClick: () -> Unit,
    leading: String? = null,
    favorite: Boolean = false,
    onFavorite: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).focusRing(shape).clip(shape).clickable(onClick = onClick).padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                Text(
                    leading,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
            }
            Logo(image, title, 44.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (onFavorite != null) {
            IconButton(onClick = onFavorite, modifier = Modifier.focusRing(CircleShape)) {
                Icon(
                    if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (favorite) "Aus Favoriten entfernen" else "Zu Favoriten",
                    tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Folge mit Vorschaubild im Breitformat, Eckdaten und kurzer Beschreibung. */
@Composable
fun EpisodeRow(title: String, meta: String, plot: String?, image: String?, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 3.dp)
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (!plot.isNullOrBlank()) {
                Text(
                    plot,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

fun kindLabel(kind: Kind) = when (kind) {
    Kind.LIVE -> "Live-TV"
    Kind.MOVIE -> "Film"
    Kind.SERIES -> "Serie"
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}
