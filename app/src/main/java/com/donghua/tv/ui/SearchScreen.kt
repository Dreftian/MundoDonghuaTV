package com.donghua.tv.ui

import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.donghua.tv.Focus
import com.donghua.tv.data.Api
import com.donghua.tv.data.HttpStatusException
import com.donghua.tv.data.MediaCard
import com.donghua.tv.data.Prefs

/**
 * Keyboard rows.
 *
 * Built to a consistent width on purpose. The number row has ten keys, the top
 * letter row ten, the home row nine (`ASDFGHJKL` plus the Spanish N) and the bottom
 * seven. Navigation moves by index within a row and clamps on the way down, so a
 * row that is one key short of the one above it puts the highlight off-centre and
 * makes LEFT/RIGHT feel broken. The rows are centred rather than packed to the
 * left so the shorter ones sit under the middle of the longer ones, which is what
 * the layout on a phone keyboard looks like.
 */
/**
 * Labels for the two keys that carry a glyph rather than a letter.
 *
 * Named constants rather than literals because a glyph in a source file is
 * fragile: it has to survive whatever encoding the file was written in, and when
 * it did not, the label silently became mojibake and the `when` that dispatches on
 * it stopped matching. Then Backspace and the microphone did nothing at all, with
 * no error anywhere. Plain text plus a separate icon cannot fail that way.
 */
private const val KEY_BACK = "Borrar"
private const val KEY_VOICE = "Voz"

/** Jumps from the keyboard down to the results without re-running the search. */
private const val KEY_GO = "Resultados"
private val KEY_ROWS = listOf(
    "1234567890",
    "QWERTYUIOP",
    "ASDFGHJKLÑ",
    "ZXCVBNM",
)


@Composable
fun SearchScreen(
    onOpenCard: (MediaCard) -> Unit,
    triggerVoice: Boolean = false,
    onVoiceTriggerHandled: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var submittedQuery by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<MediaCard>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf(1) }
    var refresh by remember { mutableStateOf(0) }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            query = spoken
            submittedQuery = spoken
            Prefs.addRecentSearch(spoken)
            refresh++
        }
    }

    fun startVoiceRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Di el nombre del donghua...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "El reconocimiento de voz no esta disponible en esta TV", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(triggerVoice) {
        if (triggerVoice) {
            startVoiceRecognition()
            onVoiceTriggerHandled()
        }
    }

    /**
     * Clears the results when the query is emptied.
     *
     * Searching used to also live here, on a 400ms debounce. That was wrong for a
     * TV: every letter typed fired a request against the site, so "throne of
     * seal" sent thirteen searches, and on this connection each one queued behind
     * the last. The viewer watched a spinner restart with every keypress and the
     * results they wanted were often not the ones on screen.
     *
     * A search now runs when the viewer asks for it, with Buscar, a recent, or the
     * voice result. That is also the only honest option: this keyboard is driven by
     * arrow keys and OK, and it can never know that the word is finished.
     */
    LaunchedEffect(query) {
        if (query.isBlank()) {
            submittedQuery = null
            results = null
            error = null
        }
    }

    LaunchedEffect(submittedQuery) {
        val q = submittedQuery ?: return@LaunchedEffect
        if (q.isBlank()) return@LaunchedEffect
        error = null
        results = null
        page = 1
        var lastMsg: String? = null
        for (attempt in 0 until 3) {
            try {
                results = Api.search(q)
                lastMsg = null
                break
            } catch (e: Exception) {
                lastMsg = e.message ?: "Error desconocido"
                // A status the server already gave is not transient. Retrying a
                // 404 three times just delayed the empty result by six seconds,
                // which on a television reads as the app having hung.
                if (e is HttpStatusException && e.code in listOf(404, 403, 410)) break
                kotlinx.coroutines.delay(2000)
            }
        }
        error = lastMsg
    }

    val recents = remember(refresh) { Prefs.recentSearches().take(6) }

    val list = results
    val resultCards = list?.drop((page - 1) * 18)?.take(18).orEmpty()
    val totalPages = maxOf(1, ((list?.size ?: 0) + 17) / 18)

    val keysStart = 1
    val keysEnd = keysStart + KEY_ROWS.size + 1  // 4 letter rows + 1 action row
    val resStart = keysEnd + 1                   // first result card
    val resEnd = resStart + resultCards.size - 1
    val pagerCell = if (list != null && totalPages > 1) resEnd + 1 else null
    val lastCell = pagerCell ?: resEnd

    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf("keys") }
    var krow by remember { mutableStateOf(1) } // Start on QWERTY row
    var kcol by remember { mutableStateOf(0) }
    var chipSel by remember { mutableStateOf(0) }
    var cell by remember { mutableStateOf(resStart) }
    var pagerSel by remember { mutableStateOf(0) }

    /** Explains a DOWN press that could not leave the keyboard. */
    var keyboardHint by remember { mutableStateOf<String?>(null) }

    val scrollTo: (Int) -> Unit = { target ->
        scope.launch { gridState.animateScrollToItem(target.coerceIn(0, lastCell)) }
    }

    // Keeps the highlight inside the grid whenever the result set changes shape.
    //
    // A voice search (or any new query) resets the results to null and the page
    // to 1, which shrinks resEnd straight back to the "no results yet" value
    // while `cell` still holds whatever it had - 46 into a grid that now ends at
    // 6. Left alone, the results appeared with no focus ring anywhere on screen
    // and three of the four arrows did nothing, and recovering took up to seven
    // UP presses. Clamping here is the whole fix, and it covers paging too.
    LaunchedEffect(resEnd, mode) {
        if (mode == "results" && (cell < resStart || cell > resEnd)) {
            cell = resEnd
            scrollTo(resEnd)
        }
    }

    fun rowStartOf(c: Int): Int = resStart + ((c - resStart) / tvColumns()) * tvColumns()
    fun rowEndOf(c: Int): Int = minOf(rowStartOf(c) + tvColumns() - 1, resEnd)

    val keyboardRows = listOf(
        KEY_ROWS[0].map { KeySpec(it.toString(), true) },
        KEY_ROWS[1].map { KeySpec(it.toString(), true) },
        KEY_ROWS[2].map { KeySpec(it.toString(), true) },
        KEY_ROWS[3].map { KeySpec(it.toString(), true) },
        listOf(
            KeySpec("Espacio", false, widthDp = 104),
            KeySpec(KEY_BACK, false, widthDp = 76),
            KeySpec(KEY_VOICE, false, widthDp = 76),
            KeySpec(KEY_GO, false, widthDp = 104),
            KeySpec("Buscar", false, primary = true, widthDp = 100),
            KeySpec("Limpiar", false, widthDp = 90),
        ),
    )

    fun searchNow(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        submittedQuery = trimmed
        keyboardHint = null
        Prefs.addRecentSearch(trimmed)
        refresh++
    }

    fun keyAction(ch: String) {
        keyboardHint = null
        when (ch) {
            "Espacio" -> if (query.length < 50) query += " "
            KEY_BACK -> if (query.isNotEmpty()) query = query.dropLast(1)
            "Buscar" -> searchNow(query)
            "Limpiar" -> {
                query = ""
                submittedQuery = null
                results = null
            }
            KEY_VOICE -> startVoiceRecognition()
            // Moves the highlight down to the results. A search only runs on
            // Buscar, a recent or the voice result, so without this the viewer
            // had no way to look at what they had already found.
            KEY_GO -> {
                if (list != null && resultCards.isNotEmpty()) {
                    mode = "results"
                    cell = resStart
                    scrollTo(resStart)
                } else {
                    keyboardHint = when {
                        query.isBlank() -> "Escribe un nombre con el mando y pulsa Buscar"
                        submittedQuery == null -> "Pulsa Buscar para buscar"
                        list == null -> "Buscando... los resultados apareceran aqui"
                        else -> "Sin resultados. Prueba con otro nombre"
                    }
                    scrollTo(resStart)
                }
            }
            else -> if (query.length < 50) query += ch
        }
    }

    fun handleDir(dir: String): Boolean {
        when (mode) {
            "recent" -> when (dir) {
                "left" -> if (chipSel > 0) chipSel--
                "right" -> if (chipSel < recents.size - 1) chipSel++
                "up" -> Focus.goNav()
                "down" -> {
                    mode = "keys"
                    krow = 0
                    kcol = 0
                    scrollTo(keysStart)
                }
            }
            "keys" -> when (dir) {
                "left" -> if (kcol > 0) kcol--
                "right" -> if (kcol < keyboardRows[krow].size - 1) kcol++
                "up" -> {
                    if (krow == 0) {
                        if (recents.isNotEmpty()) {
                            mode = "recent"
                            chipSel = 0
                        } else {
                            Focus.goNav()
                        }
                    } else {
                        // Keep the column, clamped to the row above.
                        //
                        // This used to leave kcol alone, so moving up from the right
                        // end of the short bottom row threw the highlight back to
                        // the left edge of a full width row. Clamping both ways is
                        // what makes the highlight feel attached to a finger moving
                        // over the board rather than teleporting.
                        krow--
                        kcol = kcol.coerceIn(0, keyboardRows[krow].size - 1)
                    }
                }
                "down" -> {
                    if (krow == keyboardRows.size - 1) {
                        if (list != null && resultCards.isNotEmpty()) {
                            mode = "results"
                            cell = resStart
                            scrollTo(resStart)
                        } else {
                            // Previously this key was swallowed outright while
                            // results were loading or empty, so the viewer was
                            // parked on the keyboard with no idea why DOWN
                            // stopped working. Say what is going on instead.
                            keyboardHint = when {
                                query.isBlank() ->
                                    "Escribe un nombre con el mando y pulsa Buscar"
                                error != null -> "No se pudo buscar. Pulsa Buscar para reintentar"
                                submittedQuery != null && list == null ->
                                    "Buscando... los resultados apareceran abajo"
                                else -> "Sin resultados. Prueba con otro nombre"
                            }
                            scrollTo(resStart)
                        }
                    } else {
                        keyboardHint = null
                        krow++
                        kcol = kcol.coerceAtMost(keyboardRows[krow].size - 1)
                    }
                }
            }
            "results" -> when (dir) {
                "left" -> {
                    if (cell > rowStartOf(cell)) { cell--; scrollTo(cell) }
                }
                "right" -> {
                    if (cell < rowEndOf(cell)) { cell++; scrollTo(cell) }
                }
                "up" -> {
                    val n = cell - tvColumns()
                    if (n < resStart) {
                        mode = "keys"
                        krow = keyboardRows.size - 1
                        kcol = kcol.coerceAtMost(keyboardRows[krow].size - 1)
                        scrollTo(keysStart)
                    } else {
                        cell = n
                        scrollTo(n)
                    }
                }
                "down" -> {
                    val n = cell + tvColumns()
                    if (n > lastCell) return true
                    if (n == pagerCell) {
                        mode = "pager"
                        pagerSel = 0
                        scrollTo(n)
                    } else if (n in resStart..resEnd) {
                        cell = n
                        scrollTo(n)
                    }
                }
            }
            "pager" -> when (dir) {
                "left" -> if (pagerSel > 0) pagerSel--
                "right" -> {
                    val n = pagerButtons(page, totalPages).size
                    if (pagerSel < n - 1) pagerSel++
                }
                "up" -> {
                    mode = "results"
                    cell = resEnd
                    scrollTo(cell)
                }
                "down" -> {}
            }
        }
        return true
    }

    fun handleCenter() {
        when (mode) {
            "recent" -> {
                recents.getOrNull(chipSel)?.let { r ->
                    query = r
                    searchNow(r)
                    mode = "keys"
                    krow = 1
                    kcol = 0
                }
            }
            "keys" -> {
                val spec = keyboardRows[krow].getOrNull(kcol) ?: return
                keyAction(spec.label)
            }
            "results" -> {
                val idx = cell - resStart
                resultCards.getOrNull(idx)?.let(onOpenCard)
            }
            "pager" -> {
                pagerButtons(page, totalPages).getOrNull(pagerSel)?.let { p ->
                    page = p.coerceIn(1, totalPages)
                    mode = "results"
                    cell = resStart
                    scrollTo(resStart)
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
                    mode = "results"
                    cell = resEnd
                    scrollTo(cell)
                    true
                }
                "results" -> {
                    mode = "keys"
                    krow = keyboardRows.size - 1
                    scrollTo(keysStart)
                    true
                }
                "recent" -> {
                    mode = "keys"
                    krow = 0
                    scrollTo(keysStart)
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
        contentPadding = PaddingValues(vertical = gridEdgePadding()),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            // These three used to be emitted as siblings of a grid item, which
            // a LazyVerticalGrid stacks on top of each other. The search box and
            // the "Recientes" row were therefore drawn over one another. A
            // Column is what makes them stack downwards instead.
            Column(Modifier.fillMaxWidth()) {
                SectionHeader(Icons.Default.Search, "Buscar Donghuas")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(SurfaceHigh, RoundedCornerShape(12.dp))
                        .border(1.dp, Color(0xFF222B36), RoundedCornerShape(12.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = Orange, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = if (query.isEmpty()) "Escribe con el mando o di el nombre..." else query,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (query.isEmpty()) FontWeight.Normal else FontWeight.Bold,
                        color = if (query.isEmpty()) TextMuted else TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (query.isNotEmpty()) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF26323F))
                                .clickable {
                                    query = ""
                                    submittedQuery = null
                                    results = null
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text("Limpiar", color = TextMuted, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                if (recents.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Recientes:",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextMuted,
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        recents.forEachIndexed { i, r ->
                            TabChip(
                                text = r,
                                selected = false,
                                focused = mode == "recent" && chipSel == i,
                            ) {
                                query = r
                                searchNow(r)
                                mode = "keys"
                                krow = 1
                                kcol = 0
                            }
                        }
                    }
                }
            }
        }

        keyboardRows.forEachIndexed { r, row ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (r == 0) 12.dp else 4.dp),
                    // Centre every row on the widest one.
                    //
                    // The rows hold ten, ten, nine and seven keys, so packing them
                    // to the left made the board a staircase: the N sat under the Q
                    // and the bottom row under the A, and moving DOWN appeared to
                    // shift the highlight sideways by a whole key. Centring keeps a
                    // column under a column, which is what makes the arrows feel
                    // right rather than merely functional.
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    row.forEachIndexed { c, spec ->
                        Key(
                            text = spec.label,
                            charWidth = spec.charWidth,
                            customWidth = spec.widthDp,
                            selected = spec.primary,
                            focused = mode == "keys" && krow == r && kcol == c,
                        ) { keyAction(spec.label) }
                    }
                }
            }
        }

        keyboardHint?.let { msg ->
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp)
                        .background(Color(0xFF1B2430), RoundedCornerShape(10.dp))
                        .border(1.dp, Orange.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Info,
                        null,
                        tint = Orange,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary,
                    )
                }
            }
        }

        when {
            error != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                ErrorView(error!!) { submittedQuery = query }
            }
            results == null && submittedQuery != null -> item(span = { GridItemSpan(maxLineSpan) }) {
                LoadingView(Modifier.fillMaxWidth().padding(32.dp))
            }
            results != null -> {
                if (resultCards.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        EmptyView("No se encontraron resultados para \"$submittedQuery\"")
                    }
                } else {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            text = "Resultados para \"$submittedQuery\" (${list?.size ?: 0}):",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Orange,
                            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(resultCards.size) { i ->
                        val card = resultCards[i]
                        MediaCardView(
                            card,
                            focused = mode == "results" && cell == resStart + i,
                            onClick = { onOpenCard(card) },
                        )
                    }
                    if (totalPages > 1) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            PaginationBar(
                                page,
                                totalPages,
                                selIdx = if (mode == "pager") pagerSel else null,
                                onPage = { page = it },
                            )
                        }
                    }
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.size(28.dp)) }
    }
}

private data class KeySpec(
    val label: String,
    val charWidth: Boolean,
    val primary: Boolean = false,
    val widthDp: Int = 42,
)

@Composable
private fun Key(
    text: String,
    charWidth: Boolean,
    customWidth: Int = 42,
    selected: Boolean = false,
    focused: Boolean = false,
    onClick: () -> Unit,
) {
    val bg by animateColorAsState(
        targetValue = when {
            selected -> Orange
            focused -> Orange.copy(alpha = 0.85f)
            else -> SurfaceCard
        },
        animationSpec = tween(120),
        label = "keyBg",
    )
    val width = if (charWidth) 42.dp else customWidth.dp

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0xFF222B35),
        ),
        modifier = Modifier.width(width).height(40.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.SemiBold,
                color = if (selected || focused) Color.White else TextPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}
