package com.wizwear.control.tile

import android.content.Context
import android.util.Log
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders
import androidx.wear.protolayout.LayoutElementBuilders.Column
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.LayoutElementBuilders.Spacer
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders
import androidx.wear.protolayout.TimelineBuilders
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import com.wizwear.control.MainActivity
import com.wizwear.control.R
import com.wizwear.control.WizApp
import com.wizwear.control.data.BulbStatus
import com.wizwear.control.data.Outcome
import com.wizwear.control.data.WizRepository
import com.wizwear.control.ui.WizPalette
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.DimensionBuilders.weight
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.material3.ButtonDefaults.filledVariantButtonColors
import androidx.wear.protolayout.material3.ColorScheme
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.LayoutColor
import androidx.wear.protolayout.types.LayoutString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Quick-control tile: power, brightness −/+ and warm/cool presets. Every tap
 * is a LoadAction, so the command runs here and the tile re-renders with the
 * new state. With several bulbs saved, the tile drives all of them together.
 */
class WizTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onTileRequest(requestParams: RequestBuilders.TileRequest): ListenableFuture<TileBuilders.Tile> =
        scope.future {
            val repo = (application as WizApp).repository
            val clicked = requestParams.currentState.lastClickableId
            val outcome = withTimeoutOrNull(ACTION_TIMEOUT_MS) {
                when (clicked) {
                    ID_TOGGLE -> repo.toggleAll()
                    ID_DIM, ID_BRIGHT -> {
                        // Off counts as 0%: "−" at 10% turns the light off,
                        // "+" while off turns it on at 10%.
                        val s = repo.bulbs.value.firstOrNull()?.let { repo.stateOf(it.mac) }
                        val cur = if (s?.on == true) s.dimming ?: 100 else 0
                        val next = when {
                            clicked == ID_DIM && cur <= WizRepository.MIN_DIM -> 0
                            clicked == ID_DIM -> (cur - 20).coerceAtLeast(WizRepository.MIN_DIM)
                            cur == 0 -> WizRepository.MIN_DIM
                            else -> cur + 20
                        }
                        if (clicked == ID_DIM && cur == 0) Outcome.OK // already off
                        else repo.forAll { setBrightness(it, next) }
                    }
                    ID_WARM -> repo.forAll { setTemperature(it, 2700) }
                    ID_COOL -> repo.forAll { setTemperature(it, 6500) }
                    // Just viewing the tile: reuse state under a minute old
                    // instead of waking Wi-Fi every time it is swiped to.
                    else -> repo.refreshIfStale()
                }
            }
            Log.i(TAG, "tile action=$clicked outcome=$outcome")
            TileBuilders.Tile.Builder()
                .setResourcesVersion(RESOURCES_VERSION)
                .setTileTimeline(TimelineBuilders.Timeline.fromLayoutElement(layout(repo, outcome, requestParams.deviceConfiguration)))
                .build()
        }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<ResourceBuilders.Resources> = scope.future {
        ResourceBuilders.Resources.Builder()
            .setVersion(RESOURCES_VERSION)
            .addIdToImageMapping(RES_POWER, image(R.drawable.ic_power))
            .addIdToImageMapping(RES_MINUS, image(R.drawable.ic_minus))
            .addIdToImageMapping(RES_PLUS, image(R.drawable.ic_plus))
            .build()
    }

    private fun image(resId: Int) = ResourceBuilders.ImageResource.Builder()
        .setAndroidResourceByResId(
            ResourceBuilders.AndroidImageResourceByResId.Builder().setResourceId(resId).build(),
        )
        .build()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ── Layout (ProtoLayout Material 3) ───────────────────────

    /** Same M3 palette as the app, used when the watch has no dynamic theme. */
    private val tileScheme = with(WizPalette) {
        ColorScheme(
            primary = LayoutColor(primary),
            primaryDim = LayoutColor(primaryDim),
            primaryContainer = LayoutColor(primaryContainer),
            onPrimary = LayoutColor(onPrimary),
            onPrimaryContainer = LayoutColor(onPrimaryContainer),
            secondary = LayoutColor(secondary),
            secondaryDim = LayoutColor(secondaryDim),
            secondaryContainer = LayoutColor(secondaryContainer),
            onSecondary = LayoutColor(onSecondary),
            onSecondaryContainer = LayoutColor(onSecondaryContainer),
            tertiary = LayoutColor(tertiary),
            tertiaryDim = LayoutColor(tertiaryDim),
            tertiaryContainer = LayoutColor(tertiaryContainer),
            onTertiary = LayoutColor(onTertiary),
            onTertiaryContainer = LayoutColor(onTertiaryContainer),
            surfaceContainerLow = LayoutColor(surfaceContainerLow),
            surfaceContainer = LayoutColor(surfaceContainer),
            surfaceContainerHigh = LayoutColor(surfaceContainerHigh),
            onSurface = LayoutColor(onSurface),
            onSurfaceVariant = LayoutColor(onSurfaceVariant),
            outline = LayoutColor(outline),
            outlineVariant = LayoutColor(outlineVariant),
            background = LayoutColor(background),
            onBackground = LayoutColor(onBackground),
            error = LayoutColor(error),
            errorDim = LayoutColor(errorDim),
            errorContainer = LayoutColor(errorContainer),
            onError = LayoutColor(onError),
            onErrorContainer = LayoutColor(onErrorContainer),
        )
    }

    /**
     * M3 tile: primaryLayout with a title, the controls in the main slot
     * (button groups: [−][power][+] and [Warm][Cool]); tapping elsewhere
     * opens the app.
     */
    private fun layout(repo: WizRepository, outcome: Outcome?, device: DeviceParameters): LayoutElement {
        val bulbs = repo.bulbs.value
        val first = bulbs.firstOrNull()
        val status = first?.let { repo.status.value[it.mac] }
        val state = (status as? BulbStatus.Online)?.state
        val on = state?.on == true
        val failed = outcome == Outcome.NO_WIFI || outcome == Outcome.OFFLINE || status == BulbStatus.Offline

        val title = when {
            first == null -> "WiZ Lights"
            bulbs.size > 1 -> "All lights (${bulbs.size})"
            else -> first.name
        }
        val subtitle = when {
            first == null -> "Open the app to find lights"
            outcome == Outcome.NO_WIFI -> "Wi-Fi not connected"
            failed -> "Light offline"
            state == null -> "Tap to check"
            !on -> "Off"
            else -> "On · ${state.dimming ?: 100}%" + (state.temp?.let { " · ${it}K" } ?: "")
        }

        return materialScope(this, device, allowDynamicTheme = true, defaultColorScheme = tileScheme) {
            primaryLayout(
                titleSlot = { text(LayoutString(title)) },
                mainSlot = {
                    if (first == null) {
                        text(LayoutString(subtitle), typography = Typography.BODY_MEDIUM, maxLines = 2)
                    } else {
                        Column.Builder()
                            .setWidth(expand())
                            .setHorizontalAlignment(LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER)
                            .addContent(
                                text(
                                    LayoutString(subtitle),
                                    typography = Typography.LABEL_MEDIUM,
                                    color = if (failed) colorScheme.error else colorScheme.onSurfaceVariant,
                                ),
                            )
                            .addContent(Spacer.Builder().setHeight(dp(6f)).build())
                            .addContent(
                                buttonGroup(height = dp(50f)) {
                                    buttonGroupItem {
                                        iconButton(
                                            onClick = load(ID_DIM),
                                            iconContent = { icon(RES_MINUS) },
                                            modifier = LayoutModifier.contentDescription("Dim"),
                                            width = expand(),
                                            height = expand(),
                                            colors = filledTonalButtonColors(),
                                        )
                                    }
                                    buttonGroupItem {
                                        iconButton(
                                            onClick = load(ID_TOGGLE),
                                            iconContent = { icon(RES_POWER) },
                                            modifier = LayoutModifier.contentDescription(if (on) "Turn off" else "Turn on"),
                                            width = weight(1.4f),
                                            height = expand(),
                                            // On = filled primary, off = tonal, so the state reads at a glance.
                                            colors = if (on) filledButtonColors() else filledTonalButtonColors(),
                                        )
                                    }
                                    buttonGroupItem {
                                        iconButton(
                                            onClick = load(ID_BRIGHT),
                                            iconContent = { icon(RES_PLUS) },
                                            modifier = LayoutModifier.contentDescription("Brighten"),
                                            width = expand(),
                                            height = expand(),
                                            colors = filledTonalButtonColors(),
                                        )
                                    }
                                },
                            )
                            .addContent(Spacer.Builder().setHeight(dp(4f)).build())
                            .addContent(
                                buttonGroup(height = dp(34f)) {
                                    buttonGroupItem {
                                        textButton(
                                            onClick = load(ID_WARM),
                                            labelContent = { text(LayoutString("Warm"), typography = Typography.LABEL_MEDIUM) },
                                            width = expand(),
                                            height = expand(),
                                            colors = filledVariantButtonColors(),
                                        )
                                    }
                                    buttonGroupItem {
                                        textButton(
                                            onClick = load(ID_COOL),
                                            labelContent = { text(LayoutString("Cool"), typography = Typography.LABEL_MEDIUM) },
                                            width = expand(),
                                            height = expand(),
                                            colors = ButtonColors(
                                                containerColor = colorScheme.tertiaryContainer,
                                                labelColor = colorScheme.onTertiaryContainer,
                                            ),
                                        )
                                    }
                                },
                            )
                            .build()
                    }
                },
                // No EdgeButton: it pushed the Warm/Cool row off-screen. Tapping
                // anywhere outside the buttons opens the app instead.
                onClick = openApp(),
            )
        }
    }

    private fun load(id: String) = Clickable.Builder()
        .setId(id)
        .setOnClick(ActionBuilders.LoadAction.Builder().build())
        .build()

    private fun openApp() = Clickable.Builder()
        .setId(ID_OPEN)
        .setOnClick(
            ActionBuilders.LaunchAction.Builder()
                .setAndroidActivity(
                    ActionBuilders.AndroidActivity.Builder()
                        .setPackageName(packageName)
                        .setClassName(MainActivity::class.java.name)
                        .build(),
                )
                .build(),
        )
        .build()

    companion object {
        private const val TAG = "WizTile"
        private const val RESOURCES_VERSION = "3"
        private const val RES_POWER = "power"
        private const val RES_MINUS = "minus"
        private const val RES_PLUS = "plus"
        private const val ACTION_TIMEOUT_MS = 12_000L

        const val ID_TOGGLE = "toggle"
        const val ID_DIM = "dim"
        const val ID_BRIGHT = "bright"
        const val ID_WARM = "warm"
        const val ID_COOL = "cool"
        const val ID_OPEN = "open"


        fun requestUpdate(context: Context) {
            runCatching { getUpdater(context).requestUpdate(WizTileService::class.java) }
                .onFailure { Log.w(TAG, "Tile update request failed", it) }
        }
    }
}
