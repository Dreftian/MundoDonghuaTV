package com.donghua.tv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

object Focus {
    var mode by mutableStateOf("nav")  // "nav" | "submenu" | "screen"
    var navIdx by mutableStateOf(0)
    var subIdx by mutableStateOf(0)
    var subOpen by mutableStateOf(false)
    var navHasSubs = false

    /**
     * How many chips the focused entry's sub-menu has.
     *
     * Left and right in the sub-menu wrap, and a wrap needs to know where the end
     * is. It used to be written as a literal 3, which was the length of the one
     * sub-menu there was; with "Actividad" carrying four chips the fourth was
     * unreachable, and pressing right on the third wrapped to the first.
     */
    var subCount = 0

    var syncNav = true

    var onDir: ((String) -> Boolean)? = null
    var onCenter: (() -> Unit)? = null
    var onNavItem: ((Int) -> Unit)? = null
    var onSubItem: ((Int) -> Unit)? = null
    var onBack: (() -> Boolean)? = null
    var onPop: (() -> Unit)? = null

    fun goNav() {
        mode = "nav"
        subOpen = false
        syncNav = true
    }

    fun goSubmenu() {
        // Always land on a chip that exists.
        //
        // Moving along the nav bar leaves subIdx untouched - and goNav() sets
        // syncNav, but the re-sync only runs when the nav index changes, so
        // walking left across to a menu with fewer chips carried the old index
        // with it. Coming down then asked for chip 3 of a 3-chip menu: no chip
        // matched, so the row had no highlight and OK did nothing, and only a
        // RIGHT brought it back into range.
        if (subIdx >= subCount) subIdx = 0
        mode = "submenu"
        subOpen = true
    }

    fun goScreen() {
        mode = "screen"
        subOpen = false
    }
}
