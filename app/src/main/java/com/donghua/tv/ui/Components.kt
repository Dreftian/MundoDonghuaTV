package com.donghua.tv.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.donghua.tv.data.MediaCard

import androidx.compose.ui.zIndex

private const val COLUMNS = 6
private val CARD_SHAPE = RoundedCornerShape(12.dp)

/**
 * The corner chips: the source badge and the "limited" marker.
 *
 * One shape and one colour table for both. They used to be written out twice
 * with two different oranges, so a limited card carried a brown chip and an
 * orange lock badge and the pair read as a mistake rather than as a set.
 */
private val BADGE_SHAPE = RoundedCornerShape(5.dp)
private val BadgeLimited = Color(0xFFB45309)

@Composable
fun MediaCardView(
    card: MediaCard,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (focused) cardFocusScale else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "cardScale",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (focused) 5f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CARD_SHAPE)
            .background(if (focused) Color(0xFF222B36) else SurfaceCard)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Orange else Color(0xFF222B35),
                shape = CARD_SHAPE,
            )
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 2.95f)
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color(0xFF1B232E), Color(0xFF10151D))))
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(card.thumb)
                    .crossfade(200)
                    .build(),
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Transparent,
                                if (focused) Color(0x66000000) else Color(0x44000000)
                            )
                        )
                    )
            )
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.Center)
                        // Scaled with the rest of the card. It was a fixed 44dp,
                        // which on a 4K panel became a dot too small to read as a
                        // control and on a small one covered the poster.
                        .size(tvDp(44f, 60f))
                        .background(Orange, RoundedCornerShape(percent = 50)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(tvDp(26f, 36f)),
                    )
                }
            }
            // The two corner markers are inset past the focus ring rather than
            // sitting on it. At 6dp the chip touched the border, and a focused
            // card then grew around it, so the orange chip and the orange ring
            // merged into one collided shape instead of reading as a badge.
            val badgePad = tvDp(10f, 14f)
            if (card.badge != null) {
                Text(
                    text = card.badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(badgePad)
                        .background(if (card.limited) BadgeLimited else Orange, BADGE_SHAPE)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (card.limited) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Limitado",
                    tint = Color(0xFFFFD54F),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(badgePad)
                        .size(tvDp(16f, 22f)),
                )
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                text = card.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (focused) FontWeight.Bold else FontWeight.SemiBold,
                color = if (focused) Orange else TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            val meta = listOfNotNull(
                card.date,
                if (card.limited) "Limitado" else null,
            ).joinToString(" · ")
            Spacer(Modifier.size(2.dp))
            Text(
                text = meta.ifEmpty { " " },
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                textAlign = TextAlign.Center,
                minLines = 1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun pagesList(page: Int, total: Int): List<Int?> {
    if (total <= 8) return (1..total).toList()
    val res = mutableListOf<Int?>()
    val start = (page - 1).coerceAtLeast(1)
    val end = (start + 3).coerceAtMost(total - 1)
    val s2 = (end - 3).coerceAtLeast(1)
    if (s2 > 1) {
        res.add(1)
        if (s2 > 2) res.add(null)
    }
    for (p in s2..end) res.add(p)
    if (end < total - 1) {
        res.add(null)
        res.add(total)
    } else if (end == total - 1) {
        res.add(total)
    }
    return res
}

fun pagerButtons(page: Int, total: Int): List<Int> {
    if (total <= 1) return emptyList()
    val pages = pagesList(page, total)
    return listOf(page - 1) + pages.filterNotNull() + listOf(page + 1)
}

@Composable
fun PaginationBar(
    page: Int,
    totalPages: Int,
    selIdx: Int? = null,
    modifier: Modifier = Modifier,
    onPage: (Int) -> Unit,
) {
    if (totalPages <= 1) return
    val buttons = pagerButtons(page, totalPages)
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PageBtn("◀", enabled = page > 1, focused = selIdx == 0, contentDesc = "Página anterior") {
            onPage(page - 1)
        }
        pagesList(page, totalPages).forEach { p ->
            if (p == null) {
                Text(
                    text = "…",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextMuted,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            } else {
                val btnIdx = buttons.indexOf(p)
                PageBtn(
                    p.toString(),
                    selected = p == page,
                    focused = selIdx == btnIdx,
                    contentDesc = "Página $p",
                ) { onPage(p) }
            }
        }
        PageBtn("▶", enabled = page < totalPages, focused = selIdx == buttons.size - 1, contentDesc = "Página siguiente") {
            onPage(page + 1)
        }
    }
}

@Composable
fun PageBtn(
    text: String,
    selected: Boolean = false,
    focused: Boolean = false,
    enabled: Boolean = true,
    contentDesc: String = text,
    onClick: () -> Unit,
) {
    val bg = if (selected) Orange else if (focused) SurfaceHigh else SurfaceCard
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(
            if (selected || focused) 2.dp else 1.dp,
            if (selected || focused) Orange else Color(0xFF2A3540)
        ),
        modifier = Modifier.padding(horizontal = 2.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                selected -> Color.White
                focused -> Color.White
                enabled -> TextMuted
                else -> TextMuted.copy(alpha = 0.4f)
            },
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp)
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}

@Composable
fun SectionHeader(icon: ImageVector, title: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 10.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Orange, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
        )
    }
}

@Composable
fun TabChip(
    text: String,
    selected: Boolean,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = if (selected) Orange else if (focused) SurfaceHigh else SurfaceCard
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = BorderStroke(
            if (focused && !selected) 2.dp else 0.dp,
            Orange,
        ),
        modifier = Modifier.padding(end = 10.dp),
    ) {
        Text(
            text = if (focused && !selected) "▶ $text" else text,
            color = if (selected || focused) Color.White else TextMuted,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Orange)
    }
}

@Composable
fun ErrorView(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No se pudo cargar el contenido",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(20.dp))
        PageBtn("Reintentar", selected = true) { onRetry() }
    }
}

@Composable
fun EmptyView(message: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text = message, style = MaterialTheme.typography.bodyLarge, color = TextMuted)
    }
}


