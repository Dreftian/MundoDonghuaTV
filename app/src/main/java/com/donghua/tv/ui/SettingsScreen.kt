package com.donghua.tv.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.donghua.tv.Focus
import com.donghua.tv.data.Prefs
import com.donghua.tv.BuildConfig

private val PROVIDERS = listOf(
    "asura" to "Asura",
    "tamamo" to "Tamamo",
    "vhide" to "Vidhide (Vh)",
    "amagi" to "Voe",
    "swish" to "Swish (Sw)",
    "fmoon" to "Fm",
)

private fun providerName(id: String?): String =
    PROVIDERS.firstOrNull { it.first == id }?.second ?: "Asura"

@Composable
fun SettingsScreen(
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf("rows") }   // rows | providers | confirm
    var row by remember { mutableStateOf(0) }
    var provSel by remember { mutableStateOf(0) }
    var confirmSel by remember { mutableStateOf(0) }
    var confirmKind by remember { mutableStateOf("history") }
    var autoNext by remember { mutableStateOf(Prefs.autoNext) }
    var historyOn by remember { mutableStateOf(Prefs.historyEnabled) }
    var lastProv by remember { mutableStateOf(Prefs.lastProvider) }
    var favCount by remember { mutableStateOf(Prefs.favorites().size) }
    var historyCount by remember { mutableStateOf(Prefs.history().size) }

    fun a11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return false
        return enabled.contains(context.packageName)
    }

    fun handleDir(dir: String): Boolean {
        when (mode) {
            "rows" -> when (dir) {
                "up" -> if (row > 0) row-- else Focus.goNav()
                "down" -> if (row < 7) row++
                "left", "right" -> {}
            }
            "providers" -> when (dir) {
                "left" -> if (provSel > 0) provSel--
                "right" -> if (provSel < PROVIDERS.size - 1) provSel++
                "up" -> {}
                "down" -> {}
            }
            "confirm" -> when (dir) {
                "left" -> if (confirmSel > 0) confirmSel--
                "right" -> if (confirmSel < 1) confirmSel++
                "up", "down" -> {}
            }
        }
        return true
    }

    fun handleCenter() {
        when (mode) {
            "rows" -> when (row) {
                // First, because it is the one of these eight anybody actually
                // presses. It used to be a glyph in the top bar, one press from
                // the end of the destinations; moving it here costs the bar a
                // tenth of its width on every item, and this is where it belongs
                // anyway - it is an action on the app, not a place in it.
                0 -> onRefresh()
                1 -> { autoNext = !autoNext; Prefs.autoNext = autoNext }
                2 -> { provSel = PROVIDERS.indexOfFirst { it.first == lastProv }.coerceAtLeast(0); mode = "providers" }
                3 -> { historyOn = !historyOn; Prefs.historyEnabled = historyOn }
                4 -> { confirmKind = "history"; confirmSel = 0; mode = "confirm" }
                5 -> { confirmKind = "favorites"; confirmSel = 0; mode = "confirm" }
                6 -> {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                    }
                }
                7 -> {}
            }
            "providers" -> {
                val id = PROVIDERS[provSel].first
                lastProv = id
                Prefs.lastProvider = id
                mode = "rows"
            }
            "confirm" -> {
                if (confirmSel == 1) {
                    if (confirmKind == "history") {
                        Prefs.clearHistory()
                        // Resume positions belong to the history too.
                        Prefs.clearPositions()
                        historyCount = 0
                    } else {
                        Prefs.clearFavorites()
                        favCount = 0
                    }
                }
                mode = "rows"
            }
        }
    }

    SideEffect {
        Focus.onDir = { handleDir(it) }
        Focus.onCenter = { handleCenter() }
        Focus.onBack = {
            when (mode) {
                "providers", "confirm" -> {
                    mode = "rows"
                    true
                }
                else -> false
            }
        }
    }

    val shape = RoundedCornerShape(12.dp)

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionHeader(Icons.Default.Settings, "Ajustes")
        }
        if (mode == "providers") {
            item {
                Text(
                    "Proveedor por defecto (usa las flechas y OK)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            PROVIDERS.forEachIndexed { i, (id, label) ->
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (provSel == i) SurfaceHigh else SurfaceCard, shape)
                            .border(if (provSel == i) 2.dp else 0.dp, Orange, shape)
                            .clickable {
                                lastProv = id
                                Prefs.lastProvider = id
                                mode = "rows"
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (provSel == i) "▶ $label" else label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (provSel == i) TextPrimary else TextMuted,
                        )
                        if (id == lastProv) {
                            Spacer(Modifier.width(12.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = "Actual",
                                tint = Orange,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        } else if (mode == "confirm") {
            item {
                Text(
                    text = if (confirmKind == "history") "¿Borrar el historial de reproducción?" else "¿Borrar todos los favoritos?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
            val options = listOf("No", "Sí")
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    options.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .background(if (confirmSel == i) Orange else SurfaceCard, shape)
                                .border(if (confirmSel == i) 0.dp else 1.dp, Color(0xFF2A3540), shape)
                                .clickable {
                                    if (i == 1) {
                                        if (confirmKind == "history") {
                                            Prefs.clearHistory()
                                            Prefs.clearPositions()
                                            historyCount = 0
                                        } else {
                                            Prefs.clearFavorites()
                                            favCount = 0
                                        }
                                    }
                                    mode = "rows"
                                }
                                .padding(horizontal = 28.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = if (confirmSel == i) "▶ $label" else label,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = if (confirmSel == i) Color.White else TextMuted,
                            )
                        }
                    }
                }
            }
        } else {
            val rows = listOf(
                Triple("Actualizar", "Volver a cargar todo", true),
                Triple("Siguiente episodio automático", if (autoNext) "Activado" else "Desactivado", true),
                Triple("Proveedor por defecto", providerName(lastProv), true),
                Triple("Recordar historial", if (historyOn) "Activado" else "Desactivado", true),
                Triple("Borrar historial", if (historyCount > 0) "$historyCount guardados" else "Vacío", true),
                Triple("Borrar favoritos", if (favCount > 0) "$favCount favoritos" else "Vacío", true),
                Triple("Servicio de accesibilidad", if (a11yEnabled()) "Activo" else "Inactivo", true),
                Triple("Acerca de", "Versión ${BuildConfig.VERSION_NAME}", false),
            )
            rows.forEachIndexed { i, (title, value, actionable) ->
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (row == i) SurfaceHigh else SurfaceCard, shape)
                            .border(if (row == i) 2.dp else 0.dp, Orange, shape)
                            .clickable {
                                if (actionable) {
                                    when (i) {
                                        0 -> { autoNext = !autoNext; Prefs.autoNext = autoNext }
                                        1 -> { provSel = PROVIDERS.indexOfFirst { it.first == lastProv }.coerceAtLeast(0); mode = "providers" }
                                        2 -> { historyOn = !historyOn; Prefs.historyEnabled = historyOn }
                                        3 -> { confirmKind = "history"; confirmSel = 0; mode = "confirm" }
                                        4 -> { confirmKind = "favorites"; confirmSel = 0; mode = "confirm" }
                                        5 -> {
                                            try {
                                                context.startActivity(
                                                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                )
                                            } catch (e: Exception) {
                                            }
                                        }
                                    }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (row == i) "▶ $title" else title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (row == i) FontWeight.Bold else FontWeight.Normal,
                            color = if (row == i) TextPrimary else TextMuted,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (row == i) Orange else TextMuted,
                        )
                        if (!actionable) Spacer(Modifier.width(1.dp))
                    }
                }
            }
            item {
                Text(
                    text = "OK cambia o abre cada opción",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextMuted.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        item { Spacer(Modifier.size(24.dp)) }
    }
}
