package com.wizwear.control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnItemScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AlertDialog
import androidx.wear.compose.material3.AlertDialogDefaults
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.LocalContentColor
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.RadioButton
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SplitSwitchButton
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.wizwear.control.R
import com.wizwear.control.data.Bulb
import com.wizwear.control.data.BulbStatus
import com.wizwear.control.data.Presets
import com.wizwear.control.net.PilotState

// ── Shared list shell ─────────────────────────────────────────

/**
 * M3 list screen: ListHeader + TransformingLazyColumn with the standard
 * scroll morph, and an optional EdgeButton hugging the bottom edge.
 */
@Composable
private fun WearList(
    title: String,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: TransformingLazyColumnScope.(TransformationSpec) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState(initialAnchorItemIndex = 0)
    val spec = rememberTransformationSpec()
    val ld = LocalLayoutDirection.current

    val column: @Composable BoxScope.(PaddingValues) -> Unit = { p ->
        TransformingLazyColumn(
            state = listState,
            // Scaffold padding (which reserves room for the EdgeButton), but never
            // less than enough to clear the clock on a round screen.
            contentPadding = PaddingValues(
                start = max(p.calculateStartPadding(ld), 10.dp),
                end = max(p.calculateEndPadding(ld), 10.dp),
                top = max(p.calculateTopPadding(), 34.dp),
                bottom = p.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(title, textAlign = TextAlign.Center) }
            }
            content(spec)
        }
    }
    if (edgeButton != null) ScreenScaffold(scrollState = listState, edgeButton = edgeButton, content = column)
    else ScreenScaffold(scrollState = listState, content = column)
}

/** Same scroll scale/fade as the M3 cards, for plain (non-surface) items. */
private fun Modifier.morph(scope: TransformingLazyColumnItemScope, spec: TransformationSpec): Modifier =
    this
        .transformedHeight(scope, spec)
        .graphicsLayer {
            with(scope) {
                val progress = scrollProgress
                with(spec) { applyContainerTransformation(progress) }
            }
        }

@Composable
private fun Dot(color: Color, size: Int = 18) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

@Composable
private fun DrawableIcon(res: Int, tint: Color = LocalContentColor.current, size: Int = 24) {
    Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.size(size.dp))
}

// ── Home: list of bulbs ───────────────────────────────────────

@Composable
fun HomeScreen(
    bulbs: List<Bulb>,
    status: Map<String, BulbStatus>,
    busy: Boolean,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onAll: (Boolean) -> Unit,
    onDiscover: () -> Unit,
    onRefresh: () -> Unit,
) {
    WearList(
        title = "WiZ Lights",
        // Primary screen action lives in the M3 EdgeButton.
        edgeButton = {
            EdgeButton(onClick = onDiscover, enabled = !busy) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp))
                else Icon(Icons.Rounded.Search, contentDescription = "Find lights")
            }
        },
    ) { spec ->
        if (bulbs.isEmpty()) {
            item {
                Text(
                    if (busy) "Searching Wi-Fi for lights…" else "No lights yet. Make sure the watch and lights are on the same Wi-Fi.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).morph(this, spec),
                )
            }
        }
        items(bulbs, key = { it.mac }) { bulb ->
            val s = status[bulb.mac]
            val state = (s as? BulbStatus.Online)?.state
            // One surface: tap the card to open, tap the switch to toggle.
            SplitSwitchButton(
                checked = state?.on == true,
                onCheckedChange = { onToggle(bulb.mac) },
                toggleContentDescription = "Toggle ${bulb.name}",
                onContainerClick = { onOpen(bulb.mac) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                label = { Text(bulb.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(s.summary(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        if (bulbs.size > 1) {
            item {
                ListSubHeader(Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                    Text("All lights")
                }
            }
            item {
                FilledTonalButton(
                    onClick = { onAll(true) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    icon = { DrawableIcon(R.drawable.ic_bulb) },
                    label = { Text("All on") },
                )
            }
            item {
                FilledTonalButton(
                    onClick = { onAll(false) },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    icon = { DrawableIcon(R.drawable.ic_bulb_off) },
                    label = { Text("All off") },
                )
            }
        }
        if (bulbs.isNotEmpty()) {
            item {
                FilledTonalButton(
                    onClick = onRefresh,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                    icon = { Icon(Icons.Rounded.Refresh, contentDescription = null) },
                    label = { Text("Refresh status") },
                )
            }
        }
    }
}

// ── Bulb detail ───────────────────────────────────────────────

@Composable
fun BulbScreen(
    bulb: Bulb,
    status: BulbStatus?,
    onPower: (Boolean) -> Unit,
    onBrightness: () -> Unit,
    onTemperature: () -> Unit,
    onColors: () -> Unit,
    onScenes: () -> Unit,
    onRemove: () -> Unit,
) {
    val state = (status as? BulbStatus.Online)?.state
    val on = state?.on == true
    var confirmRemove by remember { mutableStateOf(false) }

    WearList(bulb.name) { spec ->
        // On/off is a binary setting → M3 SwitchButton.
        item {
            SwitchButton(
                checked = on,
                onCheckedChange = onPower,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                icon = { DrawableIcon(R.drawable.ic_power) },
                label = { Text("Power") },
                secondaryLabel = { Text(status.summary(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
        item {
            ListSubHeader(Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                Text("Adjust")
            }
        }
        item {
            FilledTonalButton(
                onClick = onBrightness,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                icon = { DrawableIcon(R.drawable.ic_sun) },
                label = { Text("Brightness") },
                secondaryLabel = { Text(if (state?.on == false) "Off" else state?.dimming?.let { "$it%" } ?: "–") },
            )
        }
        if (bulb.hasTemperature) item {
            FilledTonalButton(
                onClick = onTemperature,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                icon = { DrawableIcon(R.drawable.ic_thermo) },
                label = { Text("Temperature") },
                secondaryLabel = { Text(state?.temp?.let { "${it}K" } ?: "–") },
            )
        }
        // Colour only exists on RGB bulbs (tunable-white bulbs ignore r/g/b).
        if (bulb.hasColor) item {
            FilledTonalButton(
                onClick = onColors,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                icon = { DrawableIcon(R.drawable.ic_palette) },
                label = { Text("Color") },
                secondaryLabel = { Text(Presets.colorName(state) ?: "–") },
            )
        }
        item {
            FilledTonalButton(
                onClick = onScenes,
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                icon = { Icon(Icons.Rounded.Star, contentDescription = null) },
                label = { Text("Scene") },
                secondaryLabel = { Text(Presets.sceneName(state?.sceneId) ?: "–") },
            )
        }
        item {
            ListSubHeader(Modifier.transformedHeight(this, spec), transformation = SurfaceTransformation(spec)) {
                Text("Device · ${bulb.ip}")
            }
        }
        item {
            // Destructive action: error-tinted, and confirmed in an AlertDialog.
            FilledTonalButton(
                onClick = { confirmRemove = true },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                colors = ButtonDefaults.filledTonalButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                    iconColor = MaterialTheme.colorScheme.error,
                ),
                icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                label = { Text("Remove light") },
            )
        }
    }

    AlertDialog(
        visible = confirmRemove,
        onDismissRequest = { confirmRemove = false },
        icon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
        title = { Text("Remove ${bulb.name}?") },
        text = { Text("You can find it again with Find lights.") },
        confirmButton = {
            AlertDialogDefaults.ConfirmButton(onClick = {
                confirmRemove = false
                onRemove()
            })
        },
    )
}

// ── Colours & scenes (single choice → RadioButton) ────────────

@Composable
fun ColorScreen(current: PilotState?, onPick: (Int, Int, Int) -> Unit) {
    WearList("Color") { spec ->
        items(Presets.colors) { c ->
            RadioButton(
                selected = current?.on == true && current.r == c.r && current.g == c.g && current.b == c.b,
                onSelect = { onPick(c.r, c.g, c.b) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                icon = { Dot(Color(c.r, c.g, c.b), 22) },
                label = { Text(c.name) },
            )
        }
    }
}

@Composable
fun SceneScreen(kind: com.wizwear.control.net.BulbKind?, current: Int?, onPick: (Int) -> Unit) {
    WearList("Scene") { spec ->
        // Only the scenes this bulb type can actually play.
        items(Presets.scenesFor(kind), key = { it.id }) { s ->
            RadioButton(
                selected = s.id == current,
                onSelect = { onPick(s.id) },
                modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                transformation = SurfaceTransformation(spec),
                label = { Text(s.name) },
            )
        }
    }
}

/** Off reads as 0% so the brightness screen starts at "Off". */
val PilotState?.brightnessOrDefault: Int get() = when {
    this == null -> 70
    !on -> 0
    else -> dimming ?: 100
}
val PilotState?.tempOrDefault: Int get() = this?.temp ?: 4000
val tempPresets = Presets.temps.map { it.name to it.kelvin }
