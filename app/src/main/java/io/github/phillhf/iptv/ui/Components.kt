package io.github.phillhf.iptv.ui

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import io.github.phillhf.iptv.data.Category
import io.github.phillhf.iptv.data.EpgEntry
import io.github.phillhf.iptv.data.Kind
import io.github.phillhf.iptv.t

val Pill = RoundedCornerShape(50)
val CardShape = RoundedCornerShape(12.dp)

fun isTv(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

/**
 * Deutlicher Rahmen um das Element unter dem Fokus. Auf dem Fernseher sieht man nur so, wo man mit der
 * Fernbedienung steht. Muss vor clickable stehen.
 */
@Composable
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(10.dp)): Modifier {
    var focused by remember { mutableStateOf(false) }
    val ring = if (focused) Klar.Accent else Color.Transparent
    val fill = if (focused) Klar.SurfaceHigh else Color.Transparent
    return this
        .onFocusChanged { focused = it.isFocused }
        .border(2.dp, ring, shape)
        .background(fill, shape)
}

/** Textfelder behalten Hoch/Runter sonst für sich; mit der Fernbedienung käme man nicht mehr heraus. */
@Composable
fun Modifier.leaveWithArrows(): Modifier {
    val manager = LocalFocusManager.current
    return onPreviewKeyEvent {
        val direction = when (it.key) {
            Key.DirectionDown -> FocusDirection.Down
            Key.DirectionUp -> FocusDirection.Up
            else -> return@onPreviewKeyEvent false
        }
        if (it.type == KeyEventType.KeyDown) manager.moveFocus(direction)
        true
    }
}

/**
 * Karte, die unter dem Fokus wächst, leuchtet und einen Rahmen bekommt. Grundlage aller Kacheln.
 */
@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = CardShape,
    onFocused: (() -> Unit)? = null,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.07f else 1f, label = "Kartengröße")
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(if (focused) 18.dp else 0.dp, shape, ambientColor = Klar.Accent, spotColor = Klar.Accent)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) onFocused?.invoke()
            }
            .clip(shape)
            .background(Klar.Surface)
            .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
            .clickable(onClick = onClick),
    ) {
        content(focused)
    }
}

/** Dünner Fortschrittsbalken am unteren Rand einer Karte. */
@Composable
fun BoxScope.CardProgress(progress: Float) {
    if (progress <= 0f) return
    Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.White.copy(alpha = 0.25f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0.02f, 1f)).fillMaxHeight().background(Klar.Accent))
    }
}

@Composable
fun ProgressLine(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.height(height).clip(Pill).background(Color.White.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight().clip(Pill).background(Klar.AccentBrush))
    }
}

/** Kleines Etikett, z. B. Bewertung oben rechts auf dem Cover. */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, color: Color = Color.Black.copy(alpha = 0.65f), textColor: Color = Color.White) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(color).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = textColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ImageFallback(text: String) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Klar.SurfaceHigh, Klar.Surface))),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge,
            color = Klar.TextDim,
            modifier = Modifier.padding(10.dp),
        )
    }
}

/** Filmplakat mit Titel darunter, Bewertung und Fortschritt. */
@Composable
fun PosterCard(
    title: String,
    image: String?,
    modifier: Modifier = Modifier,
    rating: String? = null,
    progress: Float = 0f,
    showTitle: Boolean = true,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier) {
        FocusCard(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), onFocused = onFocused) {
            ImageFallback(title)
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            if (!rating.isNullOrBlank()) Badge("★ $rating", Modifier.align(Alignment.TopEnd).padding(6.dp), textColor = Klar.Gold)
            CardProgress(progress)
        }
        if (showTitle) {
            Text(
                title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = Klar.Text.copy(alpha = 0.9f),
                modifier = Modifier.padding(top = 8.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

/** Breitbild-Karte (16:9) für „Weiterschauen“ und Folgen. */
@Composable
fun WideCard(
    title: String,
    subtitle: String?,
    image: String?,
    modifier: Modifier = Modifier,
    progress: Float = 0f,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier) {
        FocusCard(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onFocused = onFocused) {
            ImageFallback(title)
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
            CardProgress(progress)
        }
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        if (!subtitle.isNullOrBlank()) {
            Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Klar.TextDim)
        }
    }
}

/** Senderkachel: Logo auf dunklem Verlauf, darunter Name und was gerade läuft. */
@Composable
fun ChannelCard(
    name: String,
    logo: String?,
    now: EpgEntry?,
    modifier: Modifier = Modifier,
    onFocused: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Column(modifier) {
        FocusCard(onClick, Modifier.fillMaxWidth().aspectRatio(16f / 9f), onFocused = onFocused) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Klar.SurfaceHigh, Color(0xFF0B1324)))))
            if (!logo.isNullOrBlank()) {
                AsyncImage(
                    model = logo,
                    contentDescription = name,
                    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(name, modifier = Modifier.align(Alignment.Center).padding(12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
            }
            Badge("LIVE", Modifier.align(Alignment.TopStart).padding(6.dp), color = Klar.Live)
            if (now != null) CardProgress(epgProgress(now))
        }
        Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Text(
            now?.title ?: " ",
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall,
            color = Klar.TextDim,
        )
    }
}

fun epgProgress(e: EpgEntry, now: Long = System.currentTimeMillis()): Float =
    if (e.end > e.start) ((now - e.start).toFloat() / (e.end - e.start)).coerceIn(0f, 1f) else 0f

fun currentAndNext(list: List<EpgEntry>, now: Long = System.currentTimeMillis()): Pair<EpgEntry?, EpgEntry?> {
    val i = list.indexOfFirst { now >= it.start && now < it.end }
    return if (i >= 0) list[i] to list.getOrNull(i + 1) else null to list.firstOrNull { it.start > now }
}

/** Überschrift einer Reihe und die Reihe selbst. */
@Composable
fun Shelf(
    title: String,
    side: Dp,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = side, bottom = 4.dp))
        LazyRow(
            contentPadding = PaddingValues(start = side, end = side, top = 12.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/**
 * Großes, abgedunkeltes Hintergrundbild wie im Mediacenter. Nach unten und links läuft es in den
 * Hintergrund aus, damit Text darauf lesbar bleibt. Ist nur ein Cover da, wird es weichgezeichnet.
 */
@Composable
fun Backdrop(url: String?, isCover: Boolean = false, modifier: Modifier = Modifier.fillMaxSize()) {
    val bg = Klar.Bg
    Box(modifier.background(bg)) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = if (isCover) 0.4f else 0.75f,
                modifier = Modifier.fillMaxSize().then(if (isCover) Modifier.blur(28.dp) else Modifier),
            )
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to bg.copy(alpha = 0.45f), 0.8f to bg)))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = 0.95f), 0.45f to bg.copy(alpha = 0.55f), 0.8f to Color.Transparent)))
    }
}

/** Titel, Eckdaten und Beschreibung, groß und lesbar auf dem Hintergrundbild. */
@Composable
fun HeroInfo(title: String, meta: String, plot: String?, modifier: Modifier = Modifier, plotLines: Int = 4, big: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = if (big) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.titleSmall, color = Klar.Accent)
        if (!plot.isNullOrBlank()) {
            Text(
                plot,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = plotLines,
                overflow = TextOverflow.Ellipsis,
                color = Klar.Text.copy(alpha = 0.82f),
            )
        }
    }
}

fun metaLine(vararg parts: String?): String =
    parts.mapNotNull { it?.trim() }.filter { it.isNotEmpty() }.joinToString("  ·  ")

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
fun LoadingBox(text: String = t("Lade Inhalte …")) {
    Centered {
        CircularProgressIndicator(color = Klar.Accent)
        Spacer(Modifier.height(16.dp))
        Text(text, color = Klar.TextDim)
    }
}

@Composable
fun ErrorBox(message: String, onRetry: () -> Unit) {
    Centered {
        Text(message, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry, modifier = Modifier.focusRing(Pill)) { Text(t("Erneut versuchen")) }
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = t("Zurück"))
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
        Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(Klar.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp), contentScale = ContentScale.Fit)
        } else {
            Text(fallback.trim().take(2).uppercase(), style = MaterialTheme.typography.labelLarge, color = Klar.TextDim)
        }
    }
}

@Composable
fun PosterImage(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).shadow(24.dp, CardShape).clip(CardShape).background(Klar.SurfaceHigh)) {
        ImageFallback(title)
        if (!url.isNullOrBlank()) {
            AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

/** Waagrechte Kategorie-Auswahl als Pillen (Handy und Suche). */
@Composable
fun CategoryChips(
    categories: List<Category>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    locked: Set<String> = emptySet(),
) {
    LazyRow(
        modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories) { c ->
            val isSelected = c.id == selected
            Box(
                Modifier
                    .focusRing(Pill)
                    .clip(Pill)
                    .background(if (isSelected) Klar.Accent else Klar.SurfaceHigh)
                    .clickable { onSelect(c.id) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    if (c.id in locked) "🔒 ${t(c.name)}" else t(c.name),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Klar.OnAccent else Klar.Text,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Zeile für Suchtreffer und Listen. Der Stern ist ein eigenes Ziel für die Fernbedienung. */
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
                Text(leading, style = MaterialTheme.typography.labelMedium, color = Klar.TextDim, modifier = Modifier.width(40.dp))
            }
            Logo(image, title, 44.dp)
            Column(Modifier.padding(start = 12.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Klar.TextDim)
                }
            }
        }
        if (onFavorite != null) FavoriteStar(favorite, onFavorite)
    }
}

@Composable
fun FavoriteStar(favorite: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.focusRing(CircleShape)) {
        Icon(
            if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
            contentDescription = if (favorite) t("Aus Favoriten entfernen") else t("Zu Favoriten"),
            tint = if (favorite) Klar.Gold else Klar.TextDim,
        )
    }
}

/** Folge mit Vorschaubild im Breitformat, Eckdaten und kurzer Beschreibung. */
@Composable
fun EpisodeRow(title: String, meta: String, plot: String?, image: String?, progress: Float, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusRing(shape)
            .clip(shape)
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Klar.SurfaceHigh)) {
            if (!image.isNullOrBlank()) {
                AsyncImage(model = image, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            CardProgress(progress)
        }
        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
            if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelMedium, color = Klar.Accent)
            if (!plot.isNullOrBlank()) {
                Text(plot, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = Klar.TextDim)
            }
        }
    }
}

fun kindLabel(kind: Kind) = when (kind) {
    Kind.LIVE -> t("Live-TV")
    Kind.MOVIE -> t("Film")
    Kind.SERIES -> t("Serie")
}

fun formatTime(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

fun minutesLeft(position: Long, duration: Long): String? =
    if (duration > 0) t("noch %1 Min.", ((duration - position) / 60_000).coerceAtLeast(1)) else null
