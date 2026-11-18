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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.donghua.tv.Focus
import com.donghua.tv.data.Api
import com.donghua.tv.data.ListPage
import com.donghua.tv.data.MediaCard


@Composable
fun SeriesListScreen(
    title: String,
    path: String,
    icon: ImageVector,
    onOpenCard: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    var page by remember { mutableStateOf(1) }
    var data by remember { mutableStateOf<ListPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    LaunchedEffect(path, page, retryKey) {
        error = null
        data = null
        var lastMsg: String? = null
        for (attempt in 0 until 3) {
            try {
                data = Api.fetchList(path, page)
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
        data == null -> LoadingView()
        else -> SeriesListContent(title, icon, data!!, page, { page = it }, onOpenCard, modifier)
    }
}

@Composable
private fun SeriesListContent(
    title: String,
    icon: ImageVector,
    list: ListPage,
    page: Int,
    onPage: (Int) -> Unit,
    onOpenCard: (MediaCard) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cards = list.cards
    val headerCell = 0
    val gridStart = 1
    val gridEnd = gridStart + cards.size - 1
    val pagerCell = if (list.maxPage > 1) gridEnd + 1 else null
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
                    val n = pagerButtons(page, list.maxPage).size
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
                cards.getOrNull(idx)?.let(onOpenCard)
            }
            "pager" -> {
                pagerButtons(page, list.maxPage).getOrNull(pagerSel)?.let { p ->
                    onPage(p.coerceIn(1, list.maxPage))
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
            SectionHeader(icon, title)
        }
        if (cards.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyView("No hay contenido en esta sección")
            }
        } else {
            items(cards.size) { i ->
                val card = cards[i]
                MediaCardView(
                    card,
                    focused = mode == "grid" && cell == gridStart + i,
                    onClick = { onOpenCard(card) },
                )
            }
            if (list.maxPage > 1) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PaginationBar(
                        page,
                        list.maxPage,
                        selIdx = if (mode == "pager") pagerSel else null,
                        onPage = onPage,
                    )
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(24.dp)) }
    }
}
