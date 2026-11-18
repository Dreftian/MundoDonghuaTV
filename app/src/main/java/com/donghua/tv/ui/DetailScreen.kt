package com.donghua.tv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.launch
import com.donghua.tv.Focus
import com.donghua.tv.data.Api
import com.donghua.tv.data.Episode
import com.donghua.tv.data.MediaCard
import com.donghua.tv.data.Prefs
import com.donghua.tv.data.SeriesDetail


@Composable
fun DetailScreen(
    url: String,
    onBack: () -> Unit,
    onOpenEpisode: (episode: Episode, prev: Episode?, next: Episode?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var detail by remember { mutableStateOf<SeriesDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var epPage by remember { mutableStateOf(1) }

    LaunchedEffect(url, retryKey) {
        error = null
        detail = null
        var lastMsg: String? = null
        for (attempt in 0 until 3) {
            try {
                detail = Api.fetchDetail(url)
                lastMsg = null
                break
            } catch (e: Exception) {
                lastMsg = e.message ?: "Error desconocido"
                kotlinx.coroutines.delay(5000)
            }
        }
        error = lastMsg
    }

    when {
        error != null -> ErrorView(error!!) { retryKey++ }
        detail == null -> LoadingView()
        else -> {
            val d = detail!!
            val epCards = d.episodes.map { ep ->
                MediaCard(
                    title = "Episodio ${ep.number}",
                    thumb = d.poster ?: d.background ?: "",
                    url = ep.url,
                )
            }
            DetailContent(d, epCards, epPage, { epPage = it }, onOpenEpisode, modifier, url)
        }
    }
}

@Composable
private fun DetailContent(
    d: SeriesDetail,
    epCards: List<MediaCard>,
    epPage: Int,
    onEpPage: (Int) -> Unit,
    onOpenEpisode: (episode: Episode, prev: Episode?, next: Episode?) -> Unit,
    modifier: Modifier = Modifier,
    url: String = "",
) {
    val favCell = 1
    val pageCards = epCards.drop((epPage - 1) * 18).take(18)
    val gridStart = 3
    val gridEnd = gridStart + pageCards.size - 1
    val pagerCell = if (d.episodes.size > 18) gridEnd + 1 else null
    val lastCell = pagerCell ?: gridEnd

    val seriesPath = url.removePrefix(Api.BASE).ifEmpty { "/" }
    var favRefresh by remember { mutableStateOf(0) }
    val favTick = favRefresh
    val favCard = MediaCard(d.title, d.poster ?: "", seriesPath)
    val isFav = Prefs.isFavorite(seriesPath)

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf("grid") }
    var cell by remember { mutableStateOf(gridStart) }
    var pagerSel by remember { mutableStateOf(0) }

    val scrollTo: (Int) -> Unit = { target ->
        scope.launch { gridState.animateScrollToItem(target.coerceIn(0, lastCell)) }
    }

    fun rowStartOf(c: Int): Int = gridStart + ((c - gridStart) / tvColumns()) * tvColumns()

    fun rowEndOf(c: Int): Int = minOf(rowStartOf(c) + tvColumns() - 1, gridEnd)

    fun handleDir(dir: String): Boolean {
        when (mode) {
            "grid" -> when (dir) {
                "left" -> {
                    if (cell > rowStartOf(cell)) { cell--; scrollTo(cell) }
                }
                "right" -> {
                    if (cell < rowEndOf(cell)) { cell++; scrollTo(cell) }
                }
                "up" -> {
                    if (cell == favCell) {
                        Focus.goNav()
                        return true
                    }
                    if (cell < gridStart + tvColumns()) {
                        cell = favCell
                        scrollTo(favCell)
                        return true
                    }
                    var n = cell - tvColumns()
                    if (n == pagerCell) {
                        mode = "pager"
                        pagerSel = 0
                        scrollTo(n)
                    } else if (n >= gridStart) {
                        cell = n
                        scrollTo(n)
                    } else {
                        Focus.goNav()
                    }
                }
                "down" -> {
                    var n = cell + tvColumns()
                    if (n > lastCell) return true
                    if (n == pagerCell) {
                        mode = "pager"
                        pagerSel = 0
                        scrollTo(n)
                    } else if (n in gridStart..gridEnd) {
                        cell = n
                        scrollTo(n)
                    }
                }
            }
            "pager" -> when (dir) {
                "left" -> if (pagerSel > 0) pagerSel--
                "right" -> {
                    val n = pagerButtons(epPage, (epCards.size + 17) / 18).size
                    if (pagerSel < n - 1) pagerSel++
                }
                "up" -> {
                    mode = "grid"
                    cell = gridEnd
                    scrollTo(cell)
                }
                "down" -> {}
            }
        }
        return true
    }

    fun handleCenter() {
        when (mode) {
            "grid" -> {
                if (cell == favCell) {
                    Prefs.toggleFavorite(favCard)
                    favRefresh++
                    return
                }
                val idx = (epPage - 1) * 18 + (cell - gridStart)
                val ep = d.episodes.getOrNull(idx) ?: return
                // The list is sorted ascending by episode number
                // (Parser.seriesDetail), so a HIGHER index is a LATER
                // chapter. These two were swapped: "prev" was reaching
                // forward, which sent the previous-chapter button one
                // episode ahead and auto-advance one episode backwards.
                val prev = d.episodes.getOrNull(idx - 1)
                val next = d.episodes.getOrNull(idx + 1)
                onOpenEpisode(ep, prev, next)
            }
            "pager" -> {
                val total = (d.episodes.size + 17) / 18
                pagerButtons(epPage, total).getOrNull(pagerSel)?.let { p ->
                    val target = p.coerceIn(1, total)
                    onEpPage(target)
                    mode = "grid"
                    cell = gridStart
                    scrollTo(gridStart)
                }
            }
        }
    }

    SideEffect {
        Focus.onDir = { dir -> handleDir(dir) }
        Focus.onCenter = { handleCenter() }
        Focus.onBack = {
            if (mode == "pager") {
                mode = "grid"
                cell = gridEnd
                scrollTo(cell)
                true
            } else {
                false
            }
        }
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
            DetailHeader(d)
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
                    .background(if (mode == "grid" && cell == favCell) SurfaceHigh else SurfaceCard, RoundedCornerShape(12.dp))
                    .border(if (mode == "grid" && cell == favCell) 2.dp else 0.dp, Orange, RoundedCornerShape(12.dp))
                    .clickable {
                        Prefs.toggleFavorite(favCard)
                        favRefresh++
                    }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isFav) "★" else "☆",
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (isFav) Orange else TextMuted,
                    modifier = Modifier.size(width = 26.dp, height = 26.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (mode == "grid" && cell == favCell) {
                        if (isFav) "▶ Quitar de favoritos" else "▶ Añadir a favoritos"
                    } else {
                        if (isFav) "★ En favoritos" else "Añadir a favoritos"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (mode == "grid" && cell == favCell) FontWeight.Bold else FontWeight.Normal,
                    color = if (mode == "grid" && cell == favCell) TextPrimary else TextMuted,
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(
                Icons.Default.List,
                "Episodios ${d.episodeCount?.let { "($it)" } ?: ""}",
            )
        }
        if (pageCards.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyView("No hay episodios disponibles")
            }
        } else {
            items(pageCards.size) { i ->
                val card = pageCards[i]
                MediaCardView(
                    card,
                    focused = mode == "grid" && cell == gridStart + i,
                    onClick = {
                        val idx = (epPage - 1) * 18 + i
                        val ep = d.episodes.getOrNull(idx) ?: return@MediaCardView
                        val prev = d.episodes.getOrNull(idx - 1)
                        val next = d.episodes.getOrNull(idx + 1)
                        onOpenEpisode(ep, prev, next)
                    },
                )
            }
            if (pagerCell != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PaginationBar(
                        epPage,
                        (d.episodes.size + 17) / 18,
                        selIdx = if (mode == "pager") pagerSel else null,
                        onPage = onEpPage,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun DetailHeader(d: SeriesDetail) {
    Box(Modifier.fillMaxWidth().height(300.dp)) {
        if (!d.background.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(d.background)
                    .crossfade(250)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(Modifier.fillMaxSize().background(SurfaceCard))
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.verticalGradient(listOf(Color(0x33000000), Bg)))
        )
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 20.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(Modifier.width(190.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp))) {
                if (!d.poster.isNullOrBlank()) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(d.poster)
                            .crossfade(250)
                            .build(),
                        contentDescription = d.title,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Spacer(Modifier.width(24.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = d.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                )
                if (d.info.isNotEmpty()) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        text = d.info.joinToString("  ·  ") { "${it.first}: ${it.second}" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        maxLines = 2,
                    )
                }
                if (d.genres.isNotEmpty()) {
                    Spacer(Modifier.size(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        d.genres.take(8).forEach { g ->
                            Text(
                                text = g,
                                style = MaterialTheme.typography.labelMedium,
                                color = TextPrimary,
                                modifier = Modifier
                                    .background(SurfaceHigh, RoundedCornerShape(20.dp))
                                    .padding(horizontal = 12.dp, vertical = 5.dp),
                            )
                        }
                    }
                }
                if (!d.synopsis.isNullOrBlank()) {
                    Spacer(Modifier.size(10.dp))
                    Text(
                        text = d.synopsis,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMuted,
                        maxLines = 3,
                    )
                }
            }
        }
    }
}
