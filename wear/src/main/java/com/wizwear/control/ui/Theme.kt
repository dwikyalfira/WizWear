package com.wizwear.control.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import com.wizwear.control.data.BulbStatus
import com.wizwear.control.net.PilotState
import kotlin.math.ln
import kotlin.math.pow

val Warm = Color(WizPalette.primary)

/** Full M3 role set — every surface/content pair is defined, not left to defaults. */
private val scheme = with(WizPalette) {
    ColorScheme(
        primary = Color(primary),
        primaryDim = Color(primaryDim),
        primaryContainer = Color(primaryContainer),
        onPrimary = Color(onPrimary),
        onPrimaryContainer = Color(onPrimaryContainer),
        secondary = Color(secondary),
        secondaryDim = Color(secondaryDim),
        secondaryContainer = Color(secondaryContainer),
        onSecondary = Color(onSecondary),
        onSecondaryContainer = Color(onSecondaryContainer),
        tertiary = Color(tertiary),
        tertiaryDim = Color(tertiaryDim),
        tertiaryContainer = Color(tertiaryContainer),
        onTertiary = Color(onTertiary),
        onTertiaryContainer = Color(onTertiaryContainer),
        surfaceContainerLow = Color(surfaceContainerLow),
        surfaceContainer = Color(surfaceContainer),
        surfaceContainerHigh = Color(surfaceContainerHigh),
        onSurface = Color(onSurface),
        onSurfaceVariant = Color(onSurfaceVariant),
        outline = Color(outline),
        outlineVariant = Color(outlineVariant),
        background = Color(background),
        onBackground = Color(onBackground),
        error = Color(error),
        errorDim = Color(errorDim),
        errorContainer = Color(errorContainer),
        onError = Color(onError),
        onErrorContainer = Color(onErrorContainer),
    )
}

/**
 * M3 theme: follows the watch's dynamic colour (Wear OS 6+) when the user
 * has it enabled, otherwise the warm WiZ palette.
 */
@Composable
fun WizTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colors = remember(context) { dynamicColorScheme(context) ?: scheme }
    MaterialTheme(colorScheme = colors, content = content)
}

/** Approximate colour of the light, for the little indicator dots. */
fun PilotState.displayColor(): Color = when {
    !on -> Color(0xFF55585F)
    r != null && g != null && b != null && (r + g + b) > 0 -> Color(r, g, b)
    temp != null -> kelvinToColor(temp)
    else -> Warm
}

fun BulbStatus?.summary(): String = when (this) {
    is BulbStatus.Online -> state.summary()
    BulbStatus.Offline -> "Offline"
    else -> "…"
}

fun PilotState.summary(): String {
    if (!on) return "Off"
    val parts = mutableListOf("On")
    dimming?.let { parts += "$it%" }
    when {
        sceneId != null -> com.wizwear.control.data.Presets.sceneName(sceneId)?.let { parts += it }
        temp != null -> parts += "${temp}K"
    }
    return parts.joinToString(" · ")
}

/** Tanner Helland's blackbody approximation. */
fun kelvinToColor(kelvin: Int): Color {
    val t = kelvin / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return Color(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
}
