package com.donghua.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.donghua.tv.Focus
import com.donghua.tv.data.Api
import com.donghua.tv.data.HomeData
import com.donghua.tv.data.MediaCard
import com.donghua.tv.data.Prefs


@Composable
fun HomeScreen(onOpenCard: (MediaCard) -> Unit, modifier: Modifier = Modifier) {
    var home by remember { mutableStateOf<HomeData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retryKey by remember { mutableStateOf(0) }

    LaunchedEffect(retryKey) {
        error = null
        home = null
        var lastMsg: String? = null
        for (attempt in 0 until 3) {
            try {
                home = Api.fetchHome()
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
        home == null -> LoadingView()
        else -> HomeContent(home!!, onOpenCard, Modifier.fillMaxSize())
    }
}

@Composable
private fun HomeContent(home: HomeData, onOpenCard: (MediaCard) -> Unit, modifier: Modifier = Modifier) {
    var selectedTab by remember { mutableStateOf(home.tabs.firstOrNull() ?: "") }
    var tabPage by remember { mutableStateOf(1) }

    var nuevosPages by remember { mutableStateOf(mutableMapOf<Int, List<MediaCard>>()) }
    var nuevosPage by remember { mutableStateOf(1) }
    var nuevosMax by remember { mutableStateOf(1) }
    var nuevosLoading by remember { mutableStateOf(false) }
    var nuevosError by remember { mutableStateOf(false) }

    // Retrying has to be able to re-run the fetch even when the page number
    // does not change. Keyed on the page alone, the button wrote
    // `nuevosPage = 1` while the page was already 1, so nothing was reassigned
    // and the effect never restarted: the error stayed up and the button did
    // nothing, for the life of the process. A separate counter gives the retry
    // a key of its own that is always different from the previous one.
    var nuevosRetry by remember { mutableStateOf(0) }

    LaunchedEffect(nuevosPage, nuevosRetry) {
        if (nuevosPages.containsKey(nuevosPage)) return@LaunchedEffect
        nuevosLoading = true
        nuevosError = false
        try {
            val items = Api.fetchMoreEpisodes((nuevosPage - 1) * 18)
            if (items.isEmpty()) {
                val lastGood = maxOf(nuevosMax, nuevosPage - 1).coerceAtLeast(1)
                nuevosMax = lastGood
                if (nuevosPage > lastGood) nuevosPage = lastGood
            } else {
                nuevosPages[nuevosPage] = items
                nuevosMax = maxOf(nuevosMax, if (items.size < 18) nuevosPage else nuevosPage + 1)
            }
        } catch (e: Exception) {
            nuevosError = true
        }
        nuevosLoading = false
    }

    val nuevosCards = (nuevosPages[nuevosPage] ?: (if (nuevosPage == 1) home.nuevos else emptyList())).take(18)
    val masCards = home.tabCards[selectedTab].orEmpty().take(18)
    val limitedCards = home.limited.take(18)

    fun tabPageTotal(cards: List<MediaCard>): Int =
        maxOf(1, (cards.size + 17) / 18)

    val headerLimited = 0
    val limitedStart = 1
    val limitedEnd = limitedStart + limitedCards.size - 1
    val headerNuevos = limitedEnd + 1
    val nuevosStart = headerNuevos + 1
    val nuevosEnd = nuevosStart + nuevosCards.size - 1
    val pagerNuevosCell = if (nuevosMax > 1) nuevosEnd + 1 else null
    val headerMas = (pagerNuevosCell ?: nuevosEnd) + 1
    val chipsCell = headerMas + 1
    val masStart = chipsCell + 1
    val masEnd = masStart + masCards.size - 1
    val pagerMasCell = if (tabPageTotal(masCards) > 1) masEnd + 1 else null

    val historyCards = Prefs.history().take(6)
    val favCards = Prefs.favorites().take(6)
    val hasHistory = historyCards.isNotEmpty()
    val hasFavs = favCards.isNotEmpty()
    val contHeader = if (hasHistory) (pagerMasCell ?: masEnd) + 1 else null
    val contStart = if (hasHistory) contHeader!! + 1 else 0
    val contEnd = if (hasHistory) contStart + historyCards.size - 1 else -1
    val favHeader = if (hasFavs) (if (hasHistory) contEnd else (pagerMasCell ?: masEnd)) + 1 else null
    val favStart = if (hasFavs) favHeader!! + 1 else 0
    val favEnd = if (hasFavs) favStart + favCards.size - 1 else -1
    val tail = if (hasFavs) favEnd else if (hasHistory) contEnd else (pagerMasCell ?: masEnd)
    val lastCell = tail.coerceAtLeast(masEnd)

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf("grid") }
    val firstCardCell = if (limitedCards.isEmpty()) nuevosStart else limitedStart
    var cell by remember { mutableStateOf(firstCardCell) }
    var pagerSel by remember { mutableStateOf(0) }
    var pagerKind by remember { mutableStateOf("nuevos") }
    var chipSel by remember { mutableStateOf(0) }

    val scrollTo: (Int) -> Unit = { target ->
        scope.launch { gridState.animateScrollToItem(target.coerceIn(0, lastCell)) }
    }

    fun specialCells(): Set<Int> = buildSet {
        add(headerLimited)
        add(headerNuevos)
        add(headerMas)
        contHeader?.let { add(it) }
        favHeader?.let { add(it) }
    }

    fun rowStartOf(c: Int): Int {
        val secStart = when {
            c in limitedStart..limitedEnd -> limitedStart
            c in nuevosStart..nuevosEnd -> nuevosStart
            c in masStart..masEnd -> masStart
            c in contStart..contEnd -> contStart
            else -> favStart
        }
        return c - (c - secStart) % tvColumns()
    }

    fun rowEndOf(c: Int): Int {
        val secEnd = when {
            c in limitedStart..limitedEnd -> limitedEnd
            c in nuevosStart..nuevosEnd -> nuevosEnd
            c in masStart..masEnd -> masEnd
            c in contStart..contEnd -> contEnd
            else -> favEnd
        }
        return minOf(rowStartOf(c) + tvColumns() - 1, secEnd)
    }

    fun currentPage(kind: String): Int = if (kind == "nuevos") nuevosPage else tabPage

    fun pagerTotal(kind: String): Int =
        if (kind == "nuevos") nuevosMax else tabPageTotal(masCards)

    fun pagerSize(kind: String): Int {
        val page = currentPage(kind)
        val total = pagerTotal(kind)
        return pagerButtons(page, total).size
    }

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
                    while (n >= 0 && n in specialCells()) n--
                    if (n < 0) {
                        Focus.goNav()
                        return true
                    }
                    when {
                        n == pagerNuevosCell || n == pagerMasCell -> {
                            mode = "pager"
                            pagerKind = if (n == pagerNuevosCell) "nuevos" else "mas"
                            pagerSel = 0
                            scrollTo(n)
                        }
                        n == chipsCell -> {
                            mode = "chips"
                            chipSel = home.tabs.indexOf(selectedTab).coerceAtLeast(0)
                            scrollTo(n)
                        }
                        else -> { cell = n; scrollTo(n) }
                    }
                }
                "down" -> {
                    var n = cell + tvColumns()
                    while (n <= lastCell && n in specialCells()) n++
                    if (n > lastCell) return true
                    when {
                        n == pagerNuevosCell || n == pagerMasCell -> {
                            mode = "pager"
                            pagerKind = if (n == pagerNuevosCell) "nuevos" else "mas"
                            pagerSel = 0
                            scrollTo(n)
                        }
                        n == chipsCell -> {
                            mode = "chips"
                            chipSel = home.tabs.indexOf(selectedTab).coerceAtLeast(0)
                            scrollTo(n)
                        }
                        else -> { cell = n; scrollTo(n) }
                    }
                }
            }
            "pager" -> when (dir) {
                "left" -> if (pagerSel > 0) pagerSel--
                "right" -> {
                    val n = pagerSize(pagerKind)
                    if (pagerSel < n - 1) pagerSel++
                }
                "up" -> {
                    mode = "grid"
                    val anchor = if (pagerKind == "nuevos") nuevosEnd else masEnd
                    cell = anchor.coerceAtLeast(masStart)
                    scrollTo(cell)
                }
                "down" -> {
                    mode = "grid"
                    val target = if (pagerKind == "nuevos") masStart else lastCell
                    cell = target
                    scrollTo(cell)
                }
            }
            "chips" -> when (dir) {
                "left" -> if (chipSel > 0) chipSel--
                "right" -> if (chipSel < home.tabs.size - 1) chipSel++
                "up" -> {
                    mode = "grid"
                    cell = nuevosEnd.coerceAtLeast(masStart)
                    scrollTo(cell)
                }
                "down" -> {
                    mode = "grid"
                    cell = masStart
                    scrollTo(cell)
                }
            }
        }
        return true
    }

    fun handleCenter() {
        when (mode) {
            "grid" -> {
                val all = when {
                    cell in limitedStart..limitedEnd -> limitedCards
                    cell in nuevosStart..nuevosEnd -> nuevosCards
                    cell in masStart..masEnd -> masCards
                    cell in contStart..contEnd -> historyCards
                    else -> favCards
                }
                val idx = cell - when {
                    cell in limitedStart..limitedEnd -> limitedStart
                    cell in nuevosStart..nuevosEnd -> nuevosStart
                    cell in masStart..masEnd -> masStart
                    cell in contStart..contEnd -> contStart
                    else -> favStart
                }
                all.getOrNull(idx)?.let(onOpenCard)
            }
            "pager" -> {
                val page = currentPage(pagerKind)
                val total = pagerTotal(pagerKind)
                val buttons = pagerButtons(page, total)
                buttons.getOrNull(pagerSel)?.let { p ->
                    val target = p.coerceIn(1, total)
                    if (pagerKind == "nuevos") nuevosPage = target else tabPage = target
                    mode = "grid"
                    cell = if (pagerKind == "nuevos") nuevosStart else masStart
                    scrollTo(cell)
                }
            }
            "chips" -> {
                home.tabs.getOrNull(chipSel)?.let { tab ->
                    selectedTab = tab
                    tabPage = 1
                }
            }
        }
    }

    SideEffect {
        Focus.onDir = { dir -> handleDir(dir) }
        Focus.onCenter = { handleCenter() }
        Focus.onBack = {
            when (mode) {
                "pager" -> {
                    mode = "grid"
                    cell = if (pagerKind == "nuevos") nuevosEnd else masEnd
                    scrollTo(cell)
                    true
                }
                "chips" -> {
                    mode = "grid"
                    cell = masStart
                    scrollTo(cell)
                    true
                }
                else -> false
            }
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(tvColumns()),
        state = gridState,
        modifier = modifier.fillMaxSize().padding(horizontal = gridSidePadding()),
        horizontalArrangement = Arrangement.spacedBy(gridHorizontalGap()),
        verticalArrangement = Arrangement.spacedBy(gridVerticalGap()),
        // Reserves the room a focused card grows into, so the top row is not
        // clipped by the navigation bar and the bottom row is not cut off by
        // the screen edge.
        contentPadding = PaddingValues(vertical = gridEdgePadding()),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(Icons.Default.Lock, "Episodios Limitados")
        }
        items(limitedCards.size) { i ->
            val cellIdx = limitedStart + i
            val shown = limitedCards.getOrNull(i)
            if (shown != null) {
                MediaCardView(
                    shown,
                    focused = mode == "grid" && cell == cellIdx,
                    onClick = { onOpenCard(shown) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(Icons.Default.PlayArrow, "Nuevos Episodios")
        }
        if (nuevosLoading && nuevosCards.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Orange)
                }
            }
        } else {
            items(nuevosCards.take(18).size) { i ->
                val shown = nuevosCards.take(18).getOrNull(i)
                if (shown != null) {
                    val cellIdx = nuevosStart + i
                    MediaCardView(
                        shown,
                        focused = mode == "grid" && cell == cellIdx,
                        onClick = { onOpenCard(shown) },
                    )
                }
            }
            if (nuevosError) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                        PageBtn("Reintentar", selected = true) {
                            nuevosPages.clear()
                            nuevosPage = 1
                            nuevosRetry++
                        }
                    }
                }
            } else if (nuevosMax > 1) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PaginationBar(
                        nuevosPage,
                        nuevosMax,
                        selIdx = if (mode == "pager" && pagerKind == "nuevos") pagerSel else null,
                    ) { nuevosPage = it }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            SectionHeader(Icons.Default.Star, "Más contenido")
        }
        if (home.tabs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(bottom = 10.dp)) {
                    home.tabs.forEachIndexed { i, tab ->
                        TabChip(
                            tab,
                            selected = tab == selectedTab,
                            focused = mode == "chips" && chipSel == i,
                        ) {
                            selectedTab = tab
                            tabPage = 1
                        }
                    }
                }
            }
        }
        if (masCards.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                EmptyView("No hay contenido en esta categoría")
            }
        } else {
            items(masCards.take(18).size) { i ->
                val shown = masCards.take(18).getOrNull(i)
                if (shown != null) {
                    val cellIdx = masStart + i
                    MediaCardView(
                        shown,
                        focused = mode == "grid" && cell == cellIdx,
                        onClick = { onOpenCard(shown) },
                    )
                }
            }
            if (tabPageTotal(masCards) > 1) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    PaginationBar(
                        tabPage,
                        tabPageTotal(masCards),
                        selIdx = if (mode == "pager" && pagerKind == "mas") pagerSel else null,
                    ) { tabPage = it }
                }
            }
        }
        if (hasHistory) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(Icons.Default.Refresh, "Continuar viendo")
            }
            items(historyCards.size) { i ->
                val card = historyCards[i]
                MediaCardView(
                    card,
                    focused = mode == "grid" && cell == contStart + i,
                    onClick = { onOpenCard(card) },
                )
            }
        }
        if (hasFavs) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(Icons.Default.Star, "Favoritos")
            }
            items(favCards.size) { i ->
                val card = favCards[i]
                MediaCardView(
                    card,
                    focused = mode == "grid" && cell == favStart + i,
                    onClick = { onOpenCard(card) },
                )
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(24.dp)) }
    }
}
