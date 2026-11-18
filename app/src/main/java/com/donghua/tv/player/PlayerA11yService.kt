package com.donghua.tv.player

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

class PlayerA11yService : AccessibilityService() {

    companion object {
        private const val TAG = "DonghuaA11y"

        /** Deep enough for any real window, shallow enough not to blow the stack. */
        private const val MAX_TREE_DEPTH = 60

        @Volatile
        private var instance: PlayerA11yService? = null

        fun tapScreen(x: Int, y: Int): Boolean {
            val service = instance ?: return false
            return service.dispatchTapAt(x, y)
        }

        fun playerState(): String {
            val service = instance ?: return "none"
            return service.detectState()
        }

        fun focusPlayButton(): Boolean {
            val service = instance ?: return false
            return service.findAndFocus(
                texts = listOf("Reanudar", "Haz clic", "verificar", "reproducci"),
                descs = listOf("Play"),
            )
        }

        fun focusFullscreenButton(): Boolean {
            val service = instance ?: return false
            return service.findAndFocus(
                texts = emptyList(),
                descs = listOf("Fullscreen", "Pantalla completa"),
            )
        }

        fun targetPoint(): IntArray? {
            val service = instance ?: return null
            return service.findTargetPoint()
        }

        fun fullscreenPoint(): IntArray? {
            val service = instance ?: return null
            val all = service.snapshot()
            val node = all.filter { it.isClickable }.firstOrNull {
                service.descOf(it).contains("Fullscreen", ignoreCase = true) ||
                    service.descOf(it).contains("Pantalla completa", ignoreCase = true)
            } ?: return null
            return service.centerOf(node)
        }

        fun focusNextButton(): Boolean {
            val service = instance ?: return false
            return service.findAndFocus(
                texts = listOf(
                    "Siguiente", "siguiente", "Next episode", "Siguiente episodio",
                    "Episodio siguiente", "Ver siguiente",
                ),
                descs = listOf("Next", "Siguiente"),
            )
        }

        fun playerTimeText(): String? {
            val service = instance ?: return null
            val all = service.snapshot()
            val node = all.firstOrNull {
                val t = service.textOf(it)
                t.contains(" of ") && (t.contains("minute") || t.contains("second") || t.contains("hour"))
            } ?: return null
            return service.textOf(node)
        }

        fun dialogActive(): Boolean {
            val service = instance ?: return false
            val all = service.snapshot()
            return all.any {
                val t = service.textOf(it)
                t.contains("Reanudar", ignoreCase = true) ||
                    t.contains("Empezar de nuevo", ignoreCase = true) ||
                    t.contains("Haz clic", ignoreCase = true) ||
                    t.contains("verificar", ignoreCase = true)
            }
        }

        fun adActive(): Boolean {
            val service = instance ?: return false
            val all = service.snapshot()
            return all.any {
                val t = service.textOf(it)
                t.contains("Open the game", ignoreCase = true) ||
                    t.contains("continue your", ignoreCase = true) ||
                    t.contains("secret quest", ignoreCase = true) ||
                    t.contains("Play now", ignoreCase = true) ||
                    t.contains("Install now", ignoreCase = true) ||
                    t.contains("Download the app", ignoreCase = true) ||
                    t.contains("Download now", ignoreCase = true)
            }
        }

        fun clickGuestButton(): Boolean {
            val service = instance ?: return false
            val all = service.snapshot()
            if (all.isEmpty()) return false
            val target = all.firstOrNull {
                val t = service.textOf(it) + " " + service.descOf(it)
                t.contains("please wait", ignoreCase = true) ||
                    t.contains("guest mode", ignoreCase = true) ||
                    t.contains("continue to watch", ignoreCase = true)
            } ?: return false
            var node = target
            while (node != null && !node.isClickable) {
                node = node.parent
            }
            if (node != null) {
                Log.i(TAG, "boton guest clickeado")
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                return true
            }
            return false
        }
    }

    override fun onServiceConnected() {
        instance = this
        Log.i(TAG, "Servicio de accesibilidad conectado")
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    private fun snapshot(): List<AccessibilityNodeInfo> {
        val root = rootInActiveWindow ?: return emptyList()
        val all = mutableListOf<AccessibilityNodeInfo>()
        try {
            collectAll(root, all, 0)
        } catch (e: Exception) {
            Log.w(TAG, "scan error: $e")
            return emptyList()
        } catch (e: StackOverflowError) {
            // Deliberately not covered by the catch above: this is an Error, not
            // an Exception, so it walked straight out of the service and took
            // the whole process with it. The tree this service is written to read
            // is a WebView-hosted player, which is the deepest DOM the app ever
            // puts on screen.
            Log.w(TAG, "accessibility tree too deep, stopping the scan")
            return emptyList()
        }
        return all
    }

    /**
     * Walks the node tree breadth of the window.
     *
     * Bounded by [MAX_TREE_DEPTH] rather than trusting the tree to terminate:
     * a site controls how deeply nested its DOM is, and a cycle would otherwise
     * recurse until the stack gave out.
     */
    private fun collectAll(
        node: AccessibilityNodeInfo,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int,
    ) {
        if (depth > MAX_TREE_DEPTH) return
        out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAll(it, out, depth + 1) }
        }
    }

    private fun textOf(n: AccessibilityNodeInfo): String = n.text?.toString() ?: ""

    private fun descOf(n: AccessibilityNodeInfo): String = n.contentDescription?.toString() ?: ""

    private fun detectState(): String {
        val all = snapshot()
        if (all.isEmpty()) return "none"
        val clickable = all.filter { it.isClickable }
        return when {
            clickable.any { descOf(it).contains("Pause", ignoreCase = true) } -> "pause"
            clickable.any { descOf(it).contains("Play", ignoreCase = true) } -> "play"
            else -> "none"
        }
    }

    private fun findAndFocus(texts: List<String>, descs: List<String>): Boolean {
        val all = snapshot()
        if (all.isEmpty()) return false
        Log.i(TAG, "nodos: ${all.size}")

        val clickable = all.filter { it.isClickable }
        val match: (AccessibilityNodeInfo) -> Boolean = { n ->
            val t = textOf(n)
            val d = descOf(n)
            texts.any { t.contains(it, ignoreCase = true) || d.contains(it, ignoreCase = true) } ||
                descs.any { d.contains(it, ignoreCase = true) }
        }

        // 1) nodo clicable con texto/desc coincidente
        val direct = clickable.firstOrNull(match)
        if (direct != null) return focusNode(direct)

        // 2) texto "Haz clic..." del gate -> botón clicable más cercano
        val gateText = all.firstOrNull { textOf(it).contains("Haz clic", ignoreCase = true) }
        if (gateText != null) {
            val tb = Rect()
            gateText.getBoundsInScreen(tb)
            val near = clickable.minByOrNull {
                val b = Rect()
                it.getBoundsInScreen(b)
                abs(b.exactCenterX() - tb.exactCenterX()) + abs(b.exactCenterY() - tb.exactCenterY())
            }
            if (near != null) return focusNode(near)
        }

        // 3) texto coincidente sin nodo clicable
        val textNode = all.firstOrNull(match)
        if (textNode != null) return focusNode(textNode)

        return false
    }

    private fun focusNode(node: AccessibilityNodeInfo): Boolean {
        val ok = try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        } catch (e: Exception) {
            false
        }
        Log.i(TAG, "focus -> $ok (${textOf(node)})")
        return ok
    }

    private fun dispatchTapAt(x: Int, y: Int): Boolean {
        if (Build.VERSION.SDK_INT < 24) return false
        return try {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = dispatchGesture(gesture, null, null)
            Log.i(TAG, "gesto en ($x,$y) -> $ok")
            ok
        } catch (e: Exception) {
            Log.w(TAG, "gesture error: $e")
            false
        }
    }

    private fun findTargetPoint(): IntArray? {
        val all = snapshot()
        if (all.isEmpty()) return null
        val clickable = all.filter { it.isClickable }
        val textOfNode: (AccessibilityNodeInfo) -> String = { n -> textOf(n) + " | " + descOf(n) }

        // 1) botón "Reanudar"
        clickable.firstOrNull { textOfNode(it).contains("Reanudar", ignoreCase = true) }?.let {
            return centerOf(it)
        }
        // 2) gate: botón clicable más cercano al texto "Haz clic..."
        val gateText = all.firstOrNull { textOf(it).contains("Haz clic", ignoreCase = true) }
        if (gateText != null) {
            val tb = Rect()
            gateText.getBoundsInScreen(tb)
            val near = clickable.minByOrNull {
                val b = Rect()
                it.getBoundsInScreen(b)
                abs(b.exactCenterX() - tb.exactCenterX()) + abs(b.exactCenterY() - tb.exactCenterY())
            }
            near?.let { return centerOf(it) }
        }
        // 3) botón central Play (el grande)
        clickable.firstOrNull { descOf(it).contains("Play", ignoreCase = true) }?.let {
            val b = Rect()
            it.getBoundsInScreen(b)
            if (b.width() > 120) return centerOf(it)
        }
        // 4) "Empezar de nuevo" / "Cerrar" no: evitar. fallback: botón Play pequeño inferior
        clickable.firstOrNull { descOf(it).contains("Play", ignoreCase = true) }?.let {
            return centerOf(it)
        }
        return null
    }

    private fun centerOf(node: AccessibilityNodeInfo): IntArray {
        val b = Rect()
        node.getBoundsInScreen(b)
        Log.i(TAG, "target: '${textOf(node)}' bounds=$b")
        return intArrayOf(b.exactCenterX().toInt(), b.exactCenterY().toInt())
    }
}
