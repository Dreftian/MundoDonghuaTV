package com.donghua.tv

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.donghua.tv.data.Api
import com.donghua.tv.data.Episode
import com.donghua.tv.data.MediaCard
import com.donghua.tv.player.PlayerActivity
import com.donghua.tv.ui.ActivityScreen
import com.donghua.tv.ui.Bg
import com.donghua.tv.ui.DetailScreen
import com.donghua.tv.ui.DonghuaTheme
import com.donghua.tv.ui.EpisodesScreen
import com.donghua.tv.ui.HomeScreen
import com.donghua.tv.ui.InstallTvLayout
import com.donghua.tv.ui.Orange
import com.donghua.tv.ui.SearchScreen
import com.donghua.tv.ui.SeriesListScreen
import com.donghua.tv.ui.SettingsScreen
import com.donghua.tv.ui.TextMuted
import com.donghua.tv.ui.TextPrimary
import com.donghua.tv.ui.tvDp

sealed interface Dest {
    data object Home : Dest
    data object SeriesAll : Dest
    data object SeriesEmision : Dest
    data object SeriesFinalizadas : Dest
    data object SeriesRecopiladas : Dest
    data object Episodes : Dest
    data object Search : Dest

    /**
     * The four sections of the profile page, in the order the site shows them.
     *
     * [Activity] with no sub-section is the first one, so a deep link or a
     * selected parent lands somewhere real rather than on an empty screen.
     */
    data object Activity : Dest
    data object ActivityFavorites : Dest
    data object ActivityPending : Dest
    data object ActivityWatched : Dest
    data object Settings : Dest
    data class Detail(val url: String, val title: String, val thumb: String = "") : Dest
}

class MainActivity : ComponentActivity() {

    private var onVoiceRequested: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()

        setContent {
            DonghuaTheme {
                DonghuaApp(
                    onOpenPlayer = { url, title, ep, prevUrl, nextUrl, thumb ->
                        startActivity(PlayerActivity.intent(this, url, title, prevUrl, nextUrl, thumb))
                    },
                    registerVoiceHandler = { handler -> onVoiceRequested = handler },
                )
            }
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_SEARCH,
                KeyEvent.KEYCODE_VOICE_ASSIST,
                84, 231, 219 -> { // Voice / Assistant keys
                    onVoiceRequested?.invoke()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> return handleDir("up")
                KeyEvent.KEYCODE_DPAD_DOWN -> return handleDir("down")
                KeyEvent.KEYCODE_DPAD_LEFT -> return handleDir("left")
                KeyEvent.KEYCODE_DPAD_RIGHT -> return handleDir("right")
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> return handleCenter()
                KeyEvent.KEYCODE_BACK -> {
                    if (handleBack()) return true
                    return super.dispatchKeyEvent(event)
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun handleDir(dir: String): Boolean {
        if (Focus.mode == "nav") {
            when (dir) {
                "left" -> {
                    Focus.navIdx = (Focus.navIdx + NAV_SIZE - 1) % NAV_SIZE
                    Focus.subOpen = false
                }
                "right" -> {
                    Focus.navIdx = (Focus.navIdx + 1) % NAV_SIZE
                    Focus.subOpen = false
                }
                "down" -> {
                    if (Focus.navHasSubs) {
                        Focus.goSubmenu()
                    } else {
                        Focus.goScreen()
                    }
                }
                "up" -> {}
            }
            return true
        }
        if (Focus.mode == "submenu") {
            // Wrapping needs the length of the row the viewer is actually
            // standing on, which is not always the same length: "Series" has
            // three chips and "Actividad" has four.
            val n = Focus.subCount.coerceAtLeast(1)
            when (dir) {
                "left" -> Focus.subIdx = (Focus.subIdx + n - 1) % n
                "right" -> Focus.subIdx = (Focus.subIdx + 1) % n
                "up" -> Focus.goNav()
                "down" -> Focus.onSubItem?.invoke(Focus.subIdx)
            }
            return true
        }
        Focus.onDir?.invoke(dir)
        return true
    }

    private fun handleCenter(): Boolean {
        when (Focus.mode) {
            "nav" -> {
                if (Focus.navHasSubs) {
                    Focus.goSubmenu()
                } else {
                    Focus.onNavItem?.invoke(Focus.navIdx)
                }
            }
            "submenu" -> Focus.onSubItem?.invoke(Focus.subIdx)
            else -> Focus.onCenter?.invoke()
        }
        return true
    }

    private fun handleBack(): Boolean {
        if (Focus.mode == "submenu") {
            Focus.goNav()
            return true
        }
        if (Focus.mode == "screen") {
            if (Focus.onBack?.invoke() == true) return true
            Focus.onPop?.invoke()
            return true
        }
        return false
    }
}

private const val NAV_SIZE = 7

/**
 * How many of the navigation entries are destinations, the rest being controls.
 *
 * The bar is laid out in two clusters: the destinations centred as a group, and
 * the controls pinned to the right. This is the split point between them, and it
 * doubles as the index the focus ring crosses from one cluster to the other.
 *
 * Search is on the controls side and not on the destinations side, which is why
 * this is 5 and not 6: the destinations are the five places you can *be*, and
 * search is not one of them, it is a way of leaving them.
 */
private const val NAV_DESTINATIONS = 5

/**
 * Where a destination lives in the bar: the top-level entry, and the chip under
 * it when the destination is one of that entry's sub-sections.
 *
 * Resolving both at once is what lets a sub-section be a first-class screen. The
 * two callers that need it are the one that syncs the focus ring when the screen
 * changes underneath the viewer, and the one that decides which sub-menu row to
 * draw, and both used to know the answer only for "Series".
 */
private fun locateDest(navDefs: List<NavDef>, selected: Dest): Pair<Int, Int> {
    navDefs.forEachIndexed { i, def ->
        if (def.dest == selected) return i to -1
        val sub = def.subs.indexOfFirst { it.dest == selected }
        if (sub >= 0) return i to sub
    }
    return -1 to -1
}

@Composable
fun DonghuaApp(
    onOpenPlayer: (String, String, Int, String?, String?, String?) -> Unit,
    registerVoiceHandler: (() -> Unit) -> Unit = {},
) {
    CompositionLocalProvider(
        LocalDensity provides Density(
            LocalDensity.current.density,
            fontScale = 1f,
        )
    ) {
        DonghuaAppContent(onOpenPlayer, registerVoiceHandler)
    }
}

@Composable
private fun DonghuaAppContent(
    onOpenPlayer: (String, String, Int, String?, String?, String?) -> Unit,
    registerVoiceHandler: (() -> Unit) -> Unit = {},
) {
    // Publishes the screen metrics the non-composable grid navigation reads, so
    // the columns a grid is drawn with and the columns its focus maths assumes
    // are always the same number.
    InstallTvLayout()
    var stack by remember { mutableStateOf(listOf<Dest>(Dest.Home)) }
    var refreshKey by remember { mutableStateOf(0) }
    var triggerVoiceSearch by remember { mutableStateOf(false) }
    val current = stack.last()

    registerVoiceHandler {
        stack = listOf(Dest.Search)
        triggerVoiceSearch = true
        Focus.goScreen()
    }

    // Loaded before the remember, not inside it: reading a vector resource is a
    // composable call, and the list itself is built in a non-composable block.
    val historyIcon = ImageVector.vectorResource(R.drawable.ic_nav_history)

    val navDefs = remember(historyIcon) {
        listOf(
            NavDef(Icons.Default.Home, "Inicio", Dest.Home),
            NavDef(
                Icons.AutoMirrored.Filled.List,
                "Series",
                subs = listOf(
                    NavDef(null, "Todas", Dest.SeriesAll),
                    NavDef(null, "En Emisión", Dest.SeriesEmision),
                    NavDef(null, "Finalizadas", Dest.SeriesFinalizadas),
                ),
            ),
            NavDef(Icons.Default.Star, "Recopiladas", Dest.SeriesRecopiladas),
            NavDef(Icons.Default.PlayArrow, "Episodios", Dest.Episodes),
            // A clock with a hand wound back, and the only vector in the bar that
            // is not from the Material core set, because the profile is the one
            // entry here that is not a place to browse for something new. See
            // res/drawable/ic_nav_history.xml for why it is not from the
            // extended set either.
            //
            // The four sections are the ones the site shows on its profile page,
            // in its order, because the one thing a profile screen has to get
            // right is that it is the profile screen the viewer has already seen.
            NavDef(
                historyIcon,
                "Actividad",
                Dest.Activity,
                subs = listOf(
                    NavDef(null, "Favoritos", Dest.ActivityFavorites),
                    NavDef(null, "Pendientes", Dest.ActivityPending),
                    NavDef(null, "Vistas", Dest.ActivityWatched),
                    NavDef(null, "Actividad", Dest.Activity),
                ),
                subLabel = "Sección:",
            ),
            // Search is an icon and nothing else. It is the one entry here that
            // is not a destination you arrive at: it is a mode, the on-screen
            // keyboard and the voice search take the screen over entirely, and
            // there is no list of results sitting under a heading waiting for a
            // heading. A word next to it was spending a quarter of the bar's
            // width to say what the magnifying glass already says.
            //
            // It lives in the right cluster for the same reason refresh and
            // settings do: those are controls, and this is one.
            NavDef(Icons.Default.Search, "", Dest.Search),
            // Refresh is not here any more. It was the third glyph in a cluster
            // of three, one press of left from the end of the destinations, and
            // on a bar this size that cost the five destinations about a tenth of
            // their width each. It belongs in Settings with the other things that
            // change the app rather than move through it, and the space it frees
            // goes into making the boxes you actually aim at bigger.
            NavDef(Icons.Default.Settings, "", Dest.Settings),
        )
    }

    SideEffect {
        Focus.onNavItem = { i ->
            val def = navDefs[i]
            if (def.action != null) {
                def.action()
            } else if (def.dest != null) {
                stack = listOf(def.dest)
                Focus.goScreen()
            }
        }
        Focus.onSubItem = { i ->
            // The entry the viewer is standing on, not a hardcoded one. It was
            // index 1, which meant the only entry that could have a sub-menu was
            // "Series" and any other would have opened the series filters.
            val sub = navDefs.getOrNull(Focus.navIdx)?.subs?.getOrNull(i)
            if (sub != null) {
                if (sub.action != null) {
                    sub.action()
                } else if (sub.dest != null) {
                    stack = listOf(sub.dest)
                    Focus.goScreen()
                }
            }
        }
        Focus.onPop = {
            if (stack.size > 1) {
                stack = stack.dropLast(1)
                Focus.goScreen()
            } else {
                Focus.goNav()
            }
        }
    }

    val openCard: (MediaCard) -> Unit = { card ->
        if (card.isEpisode) {
            onOpenPlayer(card.targetUrl, card.title, card.episodeNumber, null, null, card.thumb)
        } else {
            stack = stack + Dest.Detail(card.url, card.title, card.thumb)
            Focus.goScreen()
        }
    }

    val navSelect: (Dest) -> Unit = { dest ->
        stack = listOf(dest)
        Focus.goScreen()
    }

    Column(Modifier.fillMaxSize().background(Bg)) {
        TopNav(
            navDefs = navDefs,
            selected = current,
            onSelect = navSelect,
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(refreshKey, current) {
                when (val s = current) {
                    is Dest.Home -> HomeScreen(openCard)
                    is Dest.SeriesAll -> SeriesListScreen(
                        "Todas las Series", "/lista-donghuas", Icons.AutoMirrored.Filled.List, openCard
                    )
                    is Dest.SeriesEmision -> SeriesListScreen(
                        "En Emisión", "/lista-donghuas-emision", Icons.Default.PlayArrow, openCard
                    )
                    is Dest.SeriesFinalizadas -> SeriesListScreen(
                        "Finalizadas", "/lista-donghuas-finalizados", Icons.Default.CheckCircle, openCard
                    )
                    is Dest.SeriesRecopiladas -> SeriesListScreen(
                        "Recopiladas", "/lista-donghuas-recopilados", Icons.Default.Star, openCard
                    )
                    is Dest.Search -> SearchScreen(
                        onOpenCard = openCard,
                        triggerVoice = triggerVoiceSearch,
                        onVoiceTriggerHandled = { triggerVoiceSearch = false },
                    )
                    is Dest.Settings -> SettingsScreen(
                        onRefresh = {
                            refreshKey++
                            // Land on Home so the refresh is visible. Reloading a
                            // screen nobody is looking at is a button that looks
                            // broken, and the whole point of asking for a refresh
                            // is to see the new episodes.
                            stack = listOf(Dest.Home)
                            Focus.goScreen()
                        },
                    )
                    is Dest.Episodes -> EpisodesScreen(openCard)
                    is Dest.Activity,
                    is Dest.ActivityFavorites,
                    is Dest.ActivityPending,
                    is Dest.ActivityWatched -> ActivityScreen(current, openCard)
                    is Dest.Detail -> DetailScreen(
                        url = Api.BASE + s.url,
                        onBack = {
                            stack = stack.dropLast(1)
                            Focus.goScreen()
                        },
                        onOpenEpisode = { ep: Episode, prev: Episode?, next: Episode? ->
                            onOpenPlayer(
                                Api.BASE + ep.url,
                                "${s.title} - Episodio ${ep.number}",
                                ep.number,
                                prev?.let { Api.BASE + it.url },
                                next?.let { Api.BASE + it.url },
                                s.thumb,
                            )
                        },
                    )
                }
            }
        }
    }
}

private data class NavDef(
    val icon: ImageVector?,
    val label: String,
    val dest: Dest? = null,
    val action: (() -> Unit)? = null,
    val subs: List<NavDef> = emptyList(),
    /**
     * The word printed before the sub-menu chips, so the row says what it is
     * filtering. It was hardcoded to "Filtro:" because only one entry had a
     * sub-menu; with two, an unlabelled row of chips is a row of buttons with
     * no idea what they are buttons *of*.
     */
    val subLabel: String = "Filtro:",
)

@Composable
private fun TopNav(
    navDefs: List<NavDef>,
    selected: Dest,
    onSelect: (Dest) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Every destination is weighted across the full width, so there is nothing to
    // scroll to bring the focused one into view. The scroll state and the effect
    // that drove it are gone with it.
    SideEffect {
        // Published to the key handler, which sits outside this composable and
        // needs to know how wide the sub-menu row is to wrap left and right in
        // it. Both are written on every composition because navIdx can change
        // from a screen a long way from here, not only from this bar.
        val subs = navDefs.getOrNull(Focus.navIdx)?.subs.orEmpty()
        Focus.navHasSubs = subs.isNotEmpty()
        Focus.subCount = subs.size
        if (Focus.mode == "nav" && Focus.syncNav) {
            val (nav, sub) = locateDest(navDefs, selected)
            if (nav >= 0) {
                Focus.navIdx = nav
                // -1 means "this destination is the entry itself", and the
                // focus should start on the first chip rather than on a chip
                // that does not exist.
                Focus.subIdx = if (sub >= 0) sub else 0
            }
            Focus.syncNav = false
        }
    }

    Column(
        modifier
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0F151E), Color(0xFF0B0E14))
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.horizontalGradient(listOf(Color(0xFF1F2937), Color(0xFF141C26))),
                shape = RoundedCornerShape(0.dp)
            )
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(tvDp(68f, 96f))
                .padding(horizontal = tvDp(28f, 64f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_md),
                    contentDescription = "Mundo Donghua TV",
                    modifier = Modifier.height(34.dp),
                )
            }
            Spacer(Modifier.width(20.dp))
            // The destinations are centred as a group, and refresh and settings
            // are pinned to the right edge instead of trailing the menu.
            //
            // A plain Row, not a scrollable one: the destinations share the
            // available width evenly, so the group centres itself and every entry
            // stays reachable without a sideways swipe.
            Box(
                Modifier
                    .weight(1f)
                    .height(tvDp(40f, 48f)),
                contentAlignment = Alignment.Center,
            ) {
                Row(
                    // No scroll, and every destination weighted equally.
                    //
                    // The row used to be a `horizontalScroll`, so the five
                    // destinations were laid out at their natural width and the
                    // leftover space collected on one side. That is what made the
                    // bar look pushed together and off-centre: the items were not
                    // too large, they were not distributed. Weighting each one
                    // spreads them across the available width, which centres the
                    // group by construction and removes the scroll that could hide
                    // a destination behind an edge on a narrower screen.
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(tvDp(4f, 6f)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    navDefs.take(NAV_DESTINATIONS).forEachIndexed { i, def ->
                        val isCurrentSelected = def.dest != null && (
                            def.dest == selected ||
                                (def.dest == Dest.SeriesAll && (selected is Dest.SeriesAll || selected is Dest.SeriesEmision || selected is Dest.SeriesFinalizadas))
                            )
                        val isCurrentFocused = (Focus.mode == "nav" || Focus.mode == "submenu") && Focus.navIdx == i

                        NavItem(
                            icon = def.icon,
                            label = def.label,
                            selected = isCurrentSelected,
                            focused = isCurrentFocused,
                            // An equal share of the bar per destination. The gaps
                            // between items are therefore identical by
                            // construction, whatever the length of the word, which
                            // is what "evenly spaced" has to mean when the labels
                            // differ. Any leftover width is split between them
                            // rather than pooling at one end.
                            modifier = Modifier.weight(1f),
                            onClick = {
                                if (def.subs.isNotEmpty()) {
                                    Focus.goSubmenu()
                                } else if (def.action != null) {
                                    def.action()
                                } else def.dest?.let { onSelect(it) }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            // Right cluster: refresh, then settings. Scaled and spaced with the
            // same scale factor as the rest of the bar, so they do not end up a
            // different size from the items next to them.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                navDefs.drop(NAV_DESTINATIONS).forEachIndexed { k, def ->
                    val i = NAV_DESTINATIONS + k
                    val isCurrentFocused =
                        (Focus.mode == "nav" || Focus.mode == "submenu") && Focus.navIdx == i
                    NavItem(
                        icon = def.icon,
                        label = def.label,
                        selected = false,
                        focused = isCurrentFocused,
                        onClick = {
                            if (def.action != null) def.action()
                            else def.dest?.let { onSelect(it) }
                        },
                    )
                }
            }
        }
        // Whichever entry with a sub-menu the viewer is on, or has a screen open
        // from. It used to be "Series" by index, and the row it drew was the
        // series filters whatever else was selected; with two entries carrying
        // sub-menus the row has to follow whichever one it is.
        val subDef = navDefs.getOrNull(Focus.navIdx)?.takeIf { it.subs.isNotEmpty() }
            ?: navDefs.firstOrNull {
                it.subs.isNotEmpty() && it.subs.any { s -> s.dest == selected }
            }
        val showSub = subDef != null && (
            Focus.subOpen || subDef.subs.any { it.dest == selected } || subDef.dest == selected
            )
        if (showSub && subDef != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF090C11))
                    .padding(horizontal = 24.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    subDef.subLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    modifier = Modifier.padding(end = 4.dp),
                )
                subDef.subs.forEachIndexed { i, sub ->
                    SubChip(
                        label = sub.label,
                        selected = sub.dest == selected || (sub.dest == subDef.dest && selected == subDef.dest),
                        focused = Focus.mode == "submenu" && Focus.subIdx == i,
                        onClick = {
                            Focus.onSubItem?.invoke(i)
                        },
                    )
                }
            }
        }
    }
}

/**
 * One chip in the sub-menu row.
 *
 * Built to the same rule as [NavItem]: bigger than a phone would make it, and
 * with its three states far enough apart to read from across a room. A row of
 * chips a size down from the bar above it reads as a caption to the bar rather
 * than as the next row of the same control, and it is not - it is the filter the
 * screen below is showing.
 */
@Composable
private fun SubChip(
    label: String,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    val bg by animateColorAsState(
        targetValue = when {
            selected -> Orange
            focused -> Color(0xFF2C3A4E)
            else -> Color(0xFF131A24)
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "subChipBg",
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            focused -> Color.White
            selected -> Orange
            else -> Color(0xFF202A38)
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "subChipBorder",
    )
    Row(
        modifier = Modifier
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 1.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected || focused) Color.White else TextMuted,
            maxLines = 1,
        )
    }
}

/**
 * One entry in the top bar.
 *
 * The bar is aimed at with a remote from three metres away, so everything that
 * carries the aim is bigger than it would be on a phone: a 64px box, a 34px
 * glyph, `titleMedium` rather than `bodyMedium`. The previous numbers (56px,
 * 30px, `bodyMedium`) fitted the labels in comfortably and were still the
 * smallest text on the screen by a distance, which made the one thing the viewer
 * is navigating by the one thing they had to lean in for.
 *
 * The two states are also further apart than they were. Selected is a filled
 * orange pill, focused but not selected is a lifted dark plate with a white
 * outline, and neither one is a slightly different shade of the other: on a
 * remote you cannot point at the box, you read it, and a 6% difference in grey
 * does not survive being read from across a room.
 */
@Composable
private fun NavItem(
    icon: ImageVector?,
    label: String,
    selected: Boolean,
    focused: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    // 1.08 rather than 1.05, and eased rather than linear: the box is the only
    // thing telling the viewer where they are between two presses of the
    // remote, and it has to be readable the instant it lands, not while it
    // arrives.
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.08f else 1f,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "navScale",
    )
    val bg by animateColorAsState(
        targetValue = when {
            selected -> Orange
            focused -> Color(0xFF2C3A4E)
            else -> Color.Transparent
        },
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "navItemBg",
    )
    val borderColor by animateColorAsState(
        targetValue = if (focused) Color.White else Color.Transparent,
        animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing),
        label = "navItemBorder",
    )
    Row(
        modifier = modifier
            .height(tvDp(40f, 48f))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(bg)
            .border(
                width = if (focused) 2.5.dp else 0.dp,
                color = borderColor,
                shape = shape,
            )
            .clickable { onClick() }
            // No horizontal padding of its own any more.
            //
            // Every destination is given an equal share of the bar's width, so the
            // gaps between them are already even. Padding on top of that did not
            // add breathing room, it pushed each label away from the middle of its
            // own cell: "Inicio" sat at the far left of a wide box and "Actividad"
            // at the far left of a wider one, which is what made the row look
            // unevenly spaced rather than regularly spaced.
            //
            // Centre instead, so the glyph and the word form one group sitting in
            // the middle of an equally sized cell.
            .padding(vertical = tvDp(6f, 9f)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(tvDp(6f, 8f), Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = when {
                    selected || focused -> Color.White
                    else -> TextMuted
                },
                modifier = Modifier.size(tvDp(19f, 24f)),
            )
        }
        if (label.isNotEmpty()) {
            Text(
                text = label,
                // One size down from the bodyMedium it used to be. The bar is a
                // wayfinding strip, not a heading: the words only have to say
                // which section this is, and at the previous size they were the
                // largest text on a 65" screen, which is not what a navigation
                // label should be.
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.SemiBold,
                color = when {
                    selected || focused -> Color.White
                    else -> TextMuted
                },
                maxLines = 1,
            )
        }
    }
}
