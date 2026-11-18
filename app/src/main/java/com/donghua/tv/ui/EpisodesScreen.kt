package com.donghua.tv.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.donghua.tv.Focus
import com.donghua.tv.data.Api
import com.donghua.tv.data.MediaCard


@Composable
fun EpisodesScreen(onOpenCard: (MediaCard) -> Unit, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf(1) }
    var cards by remember { mutableStateOf<List<MediaCard>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }
    var maxPage by remember { mutableStateOf(1) }

    LaunchedEffect(page, retryKey) {
        error = null
        cards = null
        var lastMsg: String? = null
        for (attempt in 0 until 3) {
            try {
                val items = Api.fetchMoreEpisodes((page - 1) * 18)
                cards = items
                if (items.isEmpty()) {
                    maxPage = maxOf(1, page - 1).coerceAtLeast(1)
                } else {
                    maxPage = maxOf(maxPage, if (items.size < 18) page else page + 1)
                }
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
        cards == null -> LoadingView()
        else -> EpisodesGrid(cards!!, page, maxPage, { page = it }, onOpenCard, modifier)
    }
}

@Composable
private fun EpisodesGrid(
    epCards: List<MediaCard>,
    page: Int,
    maxPage: Int,
    onPage: (Int) -> Unit,
    onOpenCard: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val headerCell = 0
    val gridStart = 1
    val gridEnd = gridStart + epCards.size - 1
    val pagerCell = if (maxPage > 1) gridEnd + 1 else null
    val lastCell = pagerCell ?: gridEnd

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
                    if (cell < tvColumns()) {
                        Focus.goNav()
                        return true
                    }
                    var n = cell - tvColumns()
                    if (n == headerCell) {
                        Focus.goNav()
                        return true
                    }
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
                    val n = pagerButtons(page, maxPage).size
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
                val idx = cell - gridStart
                epCards.getOrNull(idx)?.let(onOpenCard)
            }
            "pager" -> {
                pagerButtons(page, maxPage).getOrNull(pagerSel)?.let { p ->
                    onPage(p.coerceIn(1, maxPage))
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
            SectionHeader(Icons.Default.PlayArrow, "Episodios")
        }
        if (epCards.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyView("No hay episodios disponibles")
            }
        } else {
            items(epCards.size) { i ->
                val card = epCards[i]
                MediaCardView(
                    card,
                    focused = mode == "grid" && cell == gridStart + i,
                    onClick = { onOpenCard(card) },
                )
            }
            if (maxPage > 1) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PaginationBar(
                        page,
                        maxPage,
                        selIdx = if (mode == "pager") pagerSel else null,
                        onPage = onPage,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(24.dp)) }
    }
}
