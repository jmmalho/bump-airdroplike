package pt.johnny.bump.ui

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.text.format.DateUtils
import android.util.Size
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.johnny.bump.data.Files
import pt.johnny.bump.ui.handoff.Palette
import pt.johnny.bump.ui.theme.Brand
import kotlin.math.PI
import kotlin.math.sin

/** Breathing presence orb: this device, visible (active) or not. */
@Composable
fun PresenceOrb(active: Boolean, tablet: Boolean, modifier: Modifier = Modifier, size: Dp = 168.dp) {
    val loop = rememberInfiniteTransition(label = "orb")
    val phase by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2800, easing = LinearEasing)), label = "phase")
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val c = center
            val r = this.size.minDimension / 2f
            if (active) {
                for (i in 0 until 3) {
                    val p = (phase + i / 3f) % 1f
                    drawCircle(primary.copy(alpha = 0.22f * (1f - p)), radius = r * (0.42f + 0.58f * p), center = c, style = Stroke(width = 2.dp.toPx()))
                }
            }
            drawCircle(
                Brush.radialGradient(
                    listOf(if (active) Brand.Cyan.copy(alpha = .55f) else Color.Gray.copy(alpha = .18f), Color.Transparent),
                    center = c, radius = r * .62f,
                ),
                radius = r * .62f, center = c,
            )
            drawCircle(surface, radius = r * .36f, center = c)
            drawCircle(
                Brush.linearGradient(
                    if (active) listOf(Brand.Blue, Brand.Violet) else listOf(Color(0xFF9AA3B5), Color(0xFFB9C0CE)),
                    start = Offset(c.x - r * .36f, c.y - r * .36f), end = Offset(c.x + r * .36f, c.y + r * .36f),
                ),
                radius = r * .33f, center = c,
            )
        }
        Icon(if (tablet) Icons.Rounded.TabletAndroid else Icons.Rounded.Smartphone, null, tint = Color.White, modifier = Modifier.size(size * .2f))
    }
}

/** Two devices meeting top-to-top, with the light seam at the contact point. Loops. */
@Composable
fun BumpIllustration(modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    val loop = rememberInfiniteTransition(label = "bump")
    val t by loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "t")
    val body = MaterialTheme.colorScheme.onSurface
    val screen = MaterialTheme.colorScheme.surfaceContainerHigh
    Canvas(modifier) {
        val w = size.width; val h = size.height
        // approach 0..0.35, contact 0.35..0.55, rest after
        val approach = (t / .35f).coerceIn(0f, 1f)
        val eased = 1f - (1f - approach) * (1f - approach) * (1f - approach)
        val gap = (1f - eased) * h * .16f
        val contact = if (t in .33f..0.75f) sin(((t - .33f) / .42f) * PI).toFloat() else 0f
        val pw = w * .26f; val ph = h * .44f
        val tw = w * .52f; val th = h * .40f
        val mid = h * .5f
        // Phone (top), upside down so the tops meet.
        val phoneTop = mid - gap / 2 - ph
        drawRoundRect(body.copy(alpha = .9f), Offset(w / 2 - pw / 2, phoneTop), GSize(pw, ph), CornerRadius(pw * .18f))
        drawRoundRect(screen, Offset(w / 2 - pw / 2 + 3.dp.toPx(), phoneTop + 3.dp.toPx()), GSize(pw - 6.dp.toPx(), ph - 6.dp.toPx()), CornerRadius(pw * .14f))
        // Tablet (bottom)
        val tabTop = mid + gap / 2
        drawRoundRect(body.copy(alpha = .9f), Offset(w / 2 - tw / 2, tabTop), GSize(tw, th), CornerRadius(tw * .07f))
        drawRoundRect(screen, Offset(w / 2 - tw / 2 + 3.dp.toPx(), tabTop + 3.dp.toPx()), GSize(tw - 6.dp.toPx(), th - 6.dp.toPx()), CornerRadius(tw * .05f))
        // Seam light at the contact line.
        val glow = .25f + .75f * contact
        drawOval(
            Brush.radialGradient(listOf(Color.White.copy(alpha = glow), Brand.Cyan.copy(alpha = .7f * glow), accent.copy(alpha = .35f * glow), Color.Transparent),
                center = Offset(w / 2, mid), radius = w * .38f),
            topLeft = Offset(w / 2 - w * .38f, mid - h * .09f), size = GSize(w * .76f, h * .18f),
        )
    }
}

@Composable
fun KindTile(name: String, mime: String, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val kind = Files.kind(name, mime)
    val tint = Color(Palette.kindColor(kind))
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * .3f)).background(tint.copy(alpha = .12f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(kind.label, color = tint, fontSize = (size.value * .24f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Image thumbnail when the system can make one, the kind tile otherwise. */
@Composable
fun FileThumb(uri: Uri?, name: String, mime: String, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = if (uri == null || Build.VERSION.SDK_INT < 29) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.loadThumbnail(uri, Size(256, 256), null) }.getOrNull()
        }
    }
    val b = bitmap
    if (b != null) {
        Image(b.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = modifier.size(size).clip(RoundedCornerShape(size * .28f)))
    } else KindTile(name, mime, modifier, size)
}

@Composable
fun DeviceAvatar(
    name: String,
    tablet: Boolean,
    modifier: Modifier = Modifier,
    ring: Color? = null,
    size: Dp = 64.dp,
    badge: (@Composable () -> Unit)? = null,
) {
    Column(modifier.width(size + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(size).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.surfaceContainerHigh)))
                    .then(if (ring != null) Modifier.border(3.dp, ring, CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (tablet) Icons.Rounded.TabletAndroid else Icons.Rounded.Smartphone, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size * .42f))
            }
            if (badge != null) Box(Modifier.align(Alignment.BottomEnd)) { badge() }
        }
        Spacer(Modifier.padding(top = 6.dp))
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 26.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

/** Inline call-to-action banner (radios off, permission missing…). */
@Composable
fun Banner(icon: ImageVector, title: String, body: String, actionLabel: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .8f))
            }
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

fun relativeTime(time: Long): String =
    DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
