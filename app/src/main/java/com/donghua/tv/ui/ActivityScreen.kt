package com.donghua.tv.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.donghua.tv.Dest
import com.donghua.tv.Focus
import com.donghua.tv.R
import com.donghua.tv.data.MediaCard
import com.donghua.tv.data.Prefs
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * One section of the profile.
 *
 * The site splits this into four and so does this, in the site's order. What the
 * sections are made of is the interesting part, and it is not a guess:
 *
 *  - **Favoritos** is the one the viewer marked. Read from [Prefs.favorites], and
 *    the only section that is a list of what they chose rather than of what
 *    happened.
 *  - **Pendientes** is the chapters they started and did not finish. That is not
 *    a stored flag; it is a question with an answer, and the answer is in
 *    [Prefs.Resume]: a position that exists, is not at the start, and is not
 *    near the end.
 *  - **Vistas** is the other half of the same list. Splitting one list in two by
 *    a condition is the whole difference between "pendientes" and "vistas", and
 *    it is why both can be empty without either being wrong.
 *  - **Actividad** is the whole list, in the order they watched it, so the other
 *    two are a reordering of this one rather than a separate record.
 *
 * The position comes from [Prefs.resumeOf] rather than from
 * [Prefs.savedPosition], and the difference matters. The second answers "should
 * the player resume here" and answers 0 for a chapter you finished, one you never
 * started and one you opened for ten seconds. Showing 0 for all three is how a
 * profile ends up lying about the two chapters you most want told apart.
 */
@Composable
fun ActivityScreen(
    section: Dest,
    onOpenCard: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Re-read on every composition. The history is written by the player, which
    // is a different activity, so by the time the viewer comes back this is the
    // first time this code has run and a cached copy would be a stale one.
    val history = remember { Prefs.history() }
    val resumes = remember(history) { history.associate { it.url to Prefs.resumeOf(it.url) } }

    when (section) {
        Dest.ActivityFavorites -> FavouritesSection(onOpenCard, modifier)

        Dest.ActivityPending -> ResumeSection(
            title = "Pendientes",
            icon = Icons.Default.PlayArrow,
            cards = history.filter { resumes[it.url]?.let { r -> r.known && !r.finished && !r.untouched } == true },
            resumes = resumes,
            emptyText = "No tienes capítulos a medias",
            onOpenCard = onOpenCard,
            modifier = modifier,
        )

        Dest.ActivityWatched -> ResumeSection(
            title = "Vistas",
            icon = Icons.Default.CheckCircle,
            cards = history.filter { resumes[it.url]?.finished == true },
            resumes = resumes,
            emptyText = "Todavía no has terminado ningún capítulo",
            onOpenCard = onOpenCard,
            modifier = modifier,
        )

        else -> ResumeSection(
            title = "Actividad",
            icon = ImageVector.vectorResource(R.drawable.ic_nav_history),
            cards = history,
            resumes = resumes,
            emptyText = "Todavía no has visto ningún capítulo",
            onOpenCard = onOpenCard,
            modifier = modifier,
        )
    }
}

/** The section that is a list of marks the viewer made, not of things that happened. */
@Composable
private fun FavouritesSection(
    onOpenCard: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards = remember { Prefs.favorites() }
    ActivityGrid(
        title = "Favoritos",
        icon = Icons.Default.Favorite,
        emptyText = "No has marcado ningún favorito",
        cardCount = cards.size,
        modifier = modifier,
        cardAt = { i, focused ->
            val card = cards[i]
            MediaCardView(
                card,
                focused = focused,
                onClick = { onOpenCard(card) },
            )
        },
        onOpen = { onOpenCard(cards[it]) },
    )
}

/**
 * The three sections that are the same list in different orders.
 *
 * They share everything but the heading, the icon, the filter and the empty
 * text, so they share one grid. Four near-identical grids would drift: the
 * padding of one would change and the other three would quietly not.
 */
@Composable
private fun ResumeSection(
    title: String,
    icon: ImageVector,
    cards: List<MediaCard>,
    resumes: Map<String, Prefs.Resume>,
    emptyText: String,
    onOpenCard: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    ActivityGrid(
        title = title,
        icon = icon,
        emptyText = emptyText,
        cardCount = cards.size,
        modifier = modifier,
        cardAt = { i, focused ->
            val card = cards[i]
            ResumeCardView(
                card = card,
                resume = resumes[card.url] ?: Prefs.Resume.NONE,
                focused = focused,
                onClick = { onOpenCard(card) },
            )
        },
        onOpen = { onOpenCard(cards[it]) },
    )
}

/**
 * The grid, the focus model and the empty state, shared by all four sections.
 *
 * The focus model is the part worth sharing. The four sections are the same list
 * in different orders, and a viewer who has learnt that up leaves the grid for
 * the bar must not have to learn it again on the next one, so the arrow keys mean
 * the same thing in all four.
 */
@Composable
private fun ActivityGrid(
    title: String,
    icon: ImageVector,
    emptyText: String,
    cardCount: Int,
    modifier: Modifier = Modifier,
    cardAt: @Composable (Int, Boolean) -> Unit,
    onOpen: (Int) -> Unit,
) {
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Cell 0 is the heading, which is not focusable, so the cards start at 1 and
    // there is always one cell that is "above the first row" to hand back to the
    // navigation bar. That is what makes up from the first row mean "go to the
    // bar" rather than "do nothing".
    val gridStart = 1
    val gridEnd = gridStart + cardCount - 1
    val lastCell = if (cardCount == 0) 0 else gridEnd

    var cell by remember { mutableStateOf(gridStart) }

    val scrollTo: (Int) -> Unit = { target ->
        scope.launch { gridState.animateScrollToItem(target.coerceIn(0, lastCell)) }
    }

    fun rowStartOf(c: Int): Int = gridStart + ((c - gridStart) / tvColumns()) * tvColumns()
    fun rowEndOf(c: Int): Int = minOf(rowStartOf(c) + tvColumns() - 1, gridEnd)

    fun handleDir(dir: String): Boolean {
        when (dir) {
            "left" -> if (cell > rowStartOf(cell)) { cell--; scrollTo(cell) }
            "right" -> if (cell < rowEndOf(cell)) { cell++; scrollTo(cell) }
            "up" -> {
                if (cardCount == 0) { Focus.goNav(); return true }
                val n = cell - tvColumns()
                if (n < gridStart) { Focus.goNav(); return true }
                cell = n
                scrollTo(n)
            }
            "down" -> {
                if (cardCount == 0) return true
                val n = cell + tvColumns()
                if (n <= gridEnd) { cell = n; scrollTo(n) }
            }
        }
        return true
    }

    SideEffect {
        Focus.onDir = { dir -> handleDir(dir) }
        // The index, not the card: the grid does not hold the list, and the
        // section that does is the one that knows what the card is.
        Focus.onCenter = {
            (cell - gridStart).takeIf { i -> i in 0 until cardCount }?.let(onOpen)
        }
        Focus.onBack = { false }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(tvColumns()),
        state = gridState,
        modifier = modifier.fillMaxSize().padding(horizontal = gridSidePadding()),
        horizontalArrangement = Arrangement.spacedBy(gridHorizontalGap()),
        verticalArrangement = Arrangement.spacedBy(gridVerticalGap()),
        contentPadding = PaddingValues(vertical = gridEdgePadding()),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(icon, title)
        }
        if (cardCount == 0) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyView(emptyText) }
        } else {
            items(cardCount) { i ->
                // Positional, not named: `cardAt` is a function type and Kotlin
                // does not allow a named argument on one.
                cardAt(i, cell == gridStart + i)
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(24.dp)) }
    }
}

/**
 * A history card: the poster, the chapter, and where it was left.
 *
 * Built as its own composable rather than as [MediaCardView] with extra text,
 * because the thing this screen exists to show is not the poster. It is the
 * sentence under it. That sentence has four states and all four have to be
 * spelled out, because the failure mode is a card that looks resumable and is
 * not:
 *
 *  - a real position: "Te quedaste en 12:34 de 24:10", with a bar
 *  - watched to the end: "Terminado", with a full bar
 *  - a few seconds in: "Empezado, nada guardado"
 *  - nothing usable: "Sin progreso guardado", and no bar to draw
 *
 * A bar that is empty because the chapter was never watched and a bar that is
 * empty because there is no data to show are different things, and only one of
 * them is worth drawing.
 */
@Composable
private fun ResumeCardView(
    card: MediaCard,
    resume: Prefs.Resume,
    focused: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (focused) cardFocusScale else 1f,
        animationSpec = tween(durationMillis = 180),
        label = "activityCardScale",
    )
    val shape = RoundedCornerShape(12.dp)
    val progress by animateFloatAsState(
        targetValue = resume.fraction,
        animationSpec = tween(durationMillis = 220),
        label = "activityProgress",
    )

    val (line, showBar) = when {
        resume.finished -> "Terminado" to true
        !resume.known -> "Sin progreso guardado" to false
        resume.untouched -> "Empezado, nada guardado" to false
        else -> "Te quedaste en ${formatClock(resume.positionMs)} de ${formatClock(resume.durationMs)}" to true
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (focused) 5f else 1f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(if (focused) Color(0xFF222B36) else SurfaceCard)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = if (focused) Orange else Color(0xFF222B35),
                shape = shape,
            )
            .clickable { onClick() }
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 2.95f)
        ) {
            Box(Modifier.fillMaxSize().background(Color(0xFF10151D)))
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(card.thumb)
                    .crossfade(200)
                    .build(),
                contentDescription = card.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            if (focused) {
                Box(
                    Modifier
                        .align(Alignment.Center)
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
        }

        // The bar sits on the boundary between the poster and the text, the way
        // it does on the chapter cards in the player: it reads as a property of
        // the picture above it rather than as a line in the caption below.
        if (showBar) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(Color(0x33FFFFFF))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp)
                        .background(Orange)
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
                maxLines = 2,
                minLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(2.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Milliseconds as a clock reading, the way the player's scrub bar writes them.
 *
 * The same spelling as the player on purpose: a viewer who has read "12:34" at
 * the bottom of the player and then reads "12:34" here has been told the same
 * thing twice, and a viewer who has to work out that the two formats mean the
 * same thing has been told nothing.
 */
fun formatClock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0L)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.US, "%02d:%02d", m, sec)
    }
}
