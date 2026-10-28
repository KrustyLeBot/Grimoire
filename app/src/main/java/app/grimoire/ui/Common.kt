package app.grimoire.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.grimoire.GrimoireApp
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest

/** A cover, fetched with its source's headers (some sites refuse images without a referer). */
@Composable
fun Cover(url: String?, source: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as GrimoireApp
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh), Alignment.Center) {
        Icon(Icons.Default.MenuBook, null, tint = MaterialTheme.colorScheme.outline)
        if (url != null) {
            val request = remember(url, source) {
                val headers = app.sources.get(source)?.imageHeaders().orEmpty()
                ImageRequest.Builder(context).data(url)
                    .httpHeaders(NetworkHeaders.Builder().apply { headers.forEach { (k, v) -> set(k, v) } }.build())
                    .build()
            }
            AsyncImage(request, null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

/**
 * One series in a grid: cover with the title over a shadow, an optional badge (unread count).
 * [enabled] false greys it out (offline and nothing downloaded).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SeriesCell(
    title: String,
    cover: String?,
    source: String,
    badge: String? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        Modifier.alpha(if (enabled) 1f else 0.38f).clip(MaterialTheme.shapes.medium)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.7f).clip(MaterialTheme.shapes.medium)) {
            Cover(cover, source, Modifier.matchParentSize())
            Box(
                Modifier.matchParentSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f)),
                ),
            )
            Text(
                title, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
            if (badge != null) {
                Surface(
                    color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = MaterialTheme.shapes.small, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                ) { Text(badge, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
            }
        }
        if (subtitle != null) {
            Text(
                subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
    }
}

fun relativeDay(at: Long, now: Long = System.currentTimeMillis()): String {
    val days = ((now - at) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "Aujourd'hui"
        days == 1 -> "Hier"
        days < 7 -> "Il y a $days jours"
        else -> java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(at))
    }
}
