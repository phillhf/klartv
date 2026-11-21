package io.github.phillhf.iptv

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePreviewHandler
import kotlin.math.abs

/** Zeichnet für „demo://“-Adressen Bilder, die nach Plakat, Szenenbild oder Senderlogo aussehen. */
@OptIn(ExperimentalCoilApi::class)
val FakeImages = AsyncImagePreviewHandler { request ->
    fakeBitmap(request.data.toString()).asImage()
}

private fun hsv(h: Float, s: Float, v: Float) = Color.HSVToColor(floatArrayOf(((h % 360) + 360) % 360, s, v))

fun fakeBitmap(url: String): Bitmap {
    val kind = url.removePrefix("demo://").substringBefore('/')
    val name = url.substringAfterLast('/')
    val hue = (abs(name.hashCode() * 2654435761L) % 360).toFloat()
    return when (kind) {
        "poster" -> scene(300, 450, hue, poster = true)
        "logo" -> logo(name, hue)
        else -> scene(960, 540, hue, poster = false)
    }
}

private fun scene(w: Int, h: Int, hue: Float, poster: Boolean): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), hsv(hue, 0.65f, 0.55f), hsv(hue + 50, 0.8f, 0.12f), Shader.TileMode.CLAMP)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    // Lichtquellen wie in einem Filmbild
    p.shader = RadialGradient(w * 0.72f, h * 0.35f, w * 0.45f, hsv(hue + 20, 0.35f, 1f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    p.alpha = 150
    c.drawCircle(w * 0.72f, h * 0.35f, w * 0.45f, p)
    p.shader = RadialGradient(w * 0.15f, h * 0.85f, w * 0.35f, hsv(hue + 180, 0.6f, 0.7f), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    p.alpha = 110
    c.drawCircle(w * 0.15f, h * 0.85f, w * 0.35f, p)
    // Silhouetten im Vordergrund
    p.shader = null
    p.alpha = 255
    p.color = hsv(hue + 50, 0.7f, 0.07f)
    c.drawCircle(w * 0.3f, h * 1.05f, w * 0.38f, p)
    c.drawCircle(w * 0.85f, h * 1.1f, w * 0.42f, p)
    if (poster) {
        p.color = Color.argb(230, 255, 255, 255)
        c.drawRoundRect(RectF(w * 0.12f, h * 0.78f, w * 0.88f, h * 0.81f), 6f, 6f, p)
        p.color = Color.argb(140, 255, 255, 255)
        c.drawRoundRect(RectF(w * 0.25f, h * 0.84f, w * 0.75f, h * 0.855f), 4f, 4f, p)
    }
    return bmp
}

private fun logo(name: String, hue: Float): Bitmap {
    val w = 320
    val h = 180
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    p.color = hsv(hue, 0.75f, 0.95f)
    c.drawRoundRect(RectF(20f, 40f, 120f, 140f), 26f, 26f, p)
    p.color = Color.WHITE
    p.textSize = 54f
    p.typeface = Typeface.DEFAULT_BOLD
    val initials = name.split(' ', '%').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }.uppercase()
    c.drawText(initials, 70f - p.measureText(initials) / 2, 108f, p)
    p.textSize = 46f
    val word = name.substringBefore(' ').take(7)
    c.drawText(word, 136f, 108f, p)
    return bmp
}
