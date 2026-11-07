package com.wizwear.control.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Stepper
import androidx.wear.compose.material3.Text
import com.wizwear.control.R
import kotlin.math.abs

/** Half the angular length of the level arc (degrees either side of 3 o'clock). */
private const val LEVEL_HALF = 40f

/** Rotary travel needed for one step. */
private const val ROTARY_PX_PER_STEP = 90f

/**
 * Brightness / colour-temperature control, built on the M3 [Stepper]
 * (the same pattern as the system volume screen):
 *
 * - "+" at the top, "−" at the bottom, value in the middle.
 * - A thin level arc on the right edge shows where the value sits.
 * - Crown / bezel steps the value with a haptic tick.
 */
@Composable
fun AdjustScreen(
    title: String,
    initial: Int,
    min: Int,
    max: Int,
    step: Int,
    iconRes: Int,
    gradient: List<Color>,
    valueLabel: (Int) -> String,
    caption: (Int) -> String,
    /** Values between 0 and this are skipped (0 ↔ minNonZero), e.g. WiZ's 10% floor. */
    minNonZero: Int = 0,
    presets: List<Pair<String, Int>> = emptyList(),
    /** Print preset labels as text chips (brightness); colour dots otherwise (temperature). */
    showPresetLabels: Boolean = false,
    onChange: (Int) -> Unit,
) {
    var value by remember { mutableIntStateOf(initial.coerceIn(min, max)) }
    var accum by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val view = LocalView.current

    /** Snap, apply, and (for crown/presets) tick. The Stepper buttons give their own haptics. */
    fun set(v: Int, haptic: Boolean) {
        var n = v.coerceIn(min, max)
        if (n in 1 until minNonZero) n = if (v > value) minNonZero else 0
        if (n == value) return
        value = n
        onChange(n)
        if (haptic) {
            view.performHapticFeedback(
                if (n == min || n == max) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.CLOCK_TICK,
            )
        }
    }

    val fraction = (value - min).toFloat() / (max - min)
    val accent by animateColorAsState(colorAt(gradient, fraction.coerceAtLeast(0.15f)), label = "accent")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val off = value == 0 && minNonZero > 0

    val track = MaterialTheme.colorScheme.outlineVariant
    val animFraction by animateFloatAsState(fraction, label = "level")

    // Clock would collide with the "+" button.
    ScreenScaffold(timeText = {}) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent { e ->
                    accum += e.verticalScrollPixels
                    if (abs(accum) >= ROTARY_PX_PER_STEP) {
                        // Scroll down = lower, scroll up = higher (matches the ring filling upward).
                        val dir = if (accum > 0) -1 else 1
                        accum = 0f
                        set(value + dir * step, haptic = true)
                    }
                    true
                }
                .focusRequester(focus)
                .focusable(),
        ) {
            // Level ring: a thin arc hugging the right edge, filling bottom → top.
            // (The stock StepperLevelIndicator drew a stub on the left with an
            // invisible track on this palette.)
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(inset, inset)
                val style = Stroke(stroke, cap = StrokeCap.Round)
                // 0° = 3 o'clock; sweep from +LEVEL_HALF (bottom) to -LEVEL_HALF (top).
                drawArc(track, LEVEL_HALF, -LEVEL_HALF * 2, false, topLeft, arcSize, style = style)
                if (animFraction > 0.005f) {
                    drawArc(accent, LEVEL_HALF, -LEVEL_HALF * 2 * animFraction, false, topLeft, arcSize, style = style)
                }
            }
            Stepper(
                value = value,
                onValueChange = { set(it, haptic = false) },
                valueProgression = min..max step step,
                decreaseIcon = { Icon(painterResource(R.drawable.ic_minus), contentDescription = "Decrease") },
                increaseIcon = { Icon(painterResource(R.drawable.ic_plus), contentDescription = "Increase") },
            ) {
                // Two lines only: the Stepper's centre slot is short on a round screen.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.semantics(mergeDescendants = true) {
                        contentDescription = "$title ${valueLabel(value)}, ${caption(value)}"
                    },
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(painterResource(iconRes), contentDescription = null, tint = if (off) muted else accent, modifier = Modifier.size(16.dp))
                        Text(" ${caption(value)}", style = MaterialTheme.typography.labelMedium, color = if (off) muted else accent)
                    }
                    Text(
                        valueLabel(value),
                        style = MaterialTheme.typography.numeralMedium,
                        color = if (off) muted else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** Linear interpolation across [stops] (fraction 0..1). */
fun colorAt(stops: List<Color>, fraction: Float): Color {
    if (stops.size == 1) return stops[0]
    val f = fraction.coerceIn(0f, 1f) * (stops.size - 1)
    val i = f.toInt().coerceAtMost(stops.size - 2)
    val t = f - i
    val a = stops[i]
    val b = stops[i + 1]
    return Color(
        a.red + (b.red - a.red) * t,
        a.green + (b.green - a.green) * t,
        a.blue + (b.blue - a.blue) * t,
        1f,
    )
}

val BrightnessGradient = listOf(Color(0xFF8A6A35), Color(0xFFFFB45C), Color(0xFFFFF3D6))
val TemperatureGradient = listOf(2700, 3500, 4300, 5400, 6500).map { kelvinToColor(it) }

fun brightnessCaption(v: Int) = when {
    v == 0 -> "Light off"
    v < 35 -> "Dim"
    v < 70 -> "Medium"
    else -> "Bright"
}

fun temperatureCaption(k: Int) = when {
    k < 3000 -> "Warm"
    k < 4500 -> "Neutral"
    k < 5500 -> "Daylight"
    else -> "Cool"
}
