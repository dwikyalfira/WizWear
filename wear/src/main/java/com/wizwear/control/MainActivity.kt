package com.wizwear.control

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ConfirmationDialogDefaults
import androidx.wear.compose.material3.FailureConfirmationDialog
import androidx.wear.compose.material3.SuccessConfirmationDialog
import androidx.wear.compose.material3.confirmationDialogCurvedText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.wizwear.control.data.BulbStatus
import com.wizwear.control.ui.AdjustScreen
import com.wizwear.control.ui.BulbScreen
import com.wizwear.control.ui.ColorScreen
import com.wizwear.control.ui.HomeScreen
import com.wizwear.control.ui.SceneScreen
import com.wizwear.control.ui.WizTheme
import com.wizwear.control.ui.WizViewModel
import com.wizwear.control.ui.brightnessOrDefault
import com.wizwear.control.ui.BrightnessGradient
import com.wizwear.control.ui.TemperatureGradient
import com.wizwear.control.ui.brightnessCaption
import com.wizwear.control.ui.temperatureCaption
import com.wizwear.control.ui.tempOrDefault
import com.wizwear.control.ui.tempPresets
import com.wizwear.control.data.WizRepository

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        (application as WizApp).repository.setForeground(true)
    }

    override fun onStop() {
        (application as WizApp).repository.setForeground(false)
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repo = (application as WizApp).repository
        setContent {
            val vm: WizViewModel = viewModel(factory = WizViewModel.factory(repo))
            WizTheme {
                AppScaffold {
                    Box(Modifier.fillMaxSize()) {
                        NavHost(vm)
                        MessageOverlay(vm)
                    }
                }
            }
        }
    }
}

@Composable
private fun NavHost(vm: WizViewModel) {
    val nav = rememberSwipeDismissableNavController()
    val bulbs by vm.bulbs.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()

    fun stateOf(mac: String) = (status[mac] as? BulbStatus.Online)?.state

    SwipeDismissableNavHost(navController = nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                bulbs = bulbs,
                status = status,
                busy = busy,
                onOpen = { nav.navigate("bulb/$it") },
                onToggle = vm::toggle,
                onAll = vm::allPower,
                onDiscover = vm::discover,
                onRefresh = vm::refresh,
            )
        }
        composable("bulb/{mac}") { entry ->
            val mac = entry.arguments?.getString("mac").orEmpty()
            val bulb = bulbs.firstOrNull { it.mac == mac }
            if (bulb == null) {
                LaunchedEffect(Unit) { nav.popBackStack() }
                return@composable
            }
            BulbScreen(
                bulb = bulb,
                status = status[mac],
                onPower = { vm.setPower(mac, it) },
                onBrightness = { nav.navigate("bright/$mac") },
                onTemperature = { nav.navigate("temp/$mac") },
                onColors = { nav.navigate("color/$mac") },
                onScenes = { nav.navigate("scene/$mac") },
                onRemove = {
                    vm.remove(mac)
                    nav.popBackStack()
                },
            )
        }
        composable("bright/{mac}") { entry ->
            val mac = entry.arguments?.getString("mac").orEmpty()
            AdjustScreen(
                title = "Brightness",
                initial = stateOf(mac).brightnessOrDefault,
                // 0 = off; 1–9% don't exist on WiZ, so 0 ↔ 10% directly.
                min = 0,
                max = 100,
                step = 5,
                iconRes = R.drawable.ic_sun,
                gradient = BrightnessGradient,
                minNonZero = WizRepository.MIN_DIM,
                valueLabel = { if (it == 0) "Off" else "$it%" },
                caption = ::brightnessCaption,
                presets = listOf("Off" to 0, "25" to 25, "50" to 50, "100" to 100),
                showPresetLabels = true,
                onChange = { vm.setBrightnessDebounced(mac, it) },
            )
        }
        composable("temp/{mac}") { entry ->
            val mac = entry.arguments?.getString("mac").orEmpty()
            AdjustScreen(
                title = "Temperature",
                initial = stateOf(mac).tempOrDefault,
                // The bulb's own CCT range (2700–6500 K on tunable white).
                min = bulbs.firstOrNull { it.mac == mac }?.minKelvin ?: WizRepository.MIN_TEMP,
                max = bulbs.firstOrNull { it.mac == mac }?.maxKelvin ?: WizRepository.MAX_TEMP,
                step = 100,
                iconRes = R.drawable.ic_thermo,
                gradient = TemperatureGradient,
                valueLabel = { "${it}K" },
                caption = ::temperatureCaption,
                presets = tempPresets,
                onChange = { vm.setTemperatureDebounced(mac, it) },
            )
        }
        composable("color/{mac}") { entry ->
            val mac = entry.arguments?.getString("mac").orEmpty()
            ColorScreen(current = stateOf(mac), onPick = { r, g, b -> vm.setColor(mac, r, g, b) })
        }
        composable("scene/{mac}") { entry ->
            val mac = entry.arguments?.getString("mac").orEmpty()
            SceneScreen(kind = bulbs.firstOrNull { it.mac == mac }?.kind, current = stateOf(mac)?.sceneId, onPick = { vm.setScene(mac, it) })
        }
    }
}

/** M3 confirmation dialogs (curved text) instead of a home-made toast. */
@Composable
private fun MessageOverlay(vm: WizViewModel) {
    val message by vm.message.collectAsStateWithLifecycle()
    val m = message
    // Keep the last text so it doesn't blank out during the exit animation.
    var lastText by remember { mutableStateOf("") }
    if (m != null) lastText = m.text
    val style = ConfirmationDialogDefaults.curvedTextStyle
    SuccessConfirmationDialog(
        visible = m?.success == true,
        onDismissRequest = vm::clearMessage,
        curvedText = { confirmationDialogCurvedText(lastText, style) },
    )
    FailureConfirmationDialog(
        visible = m?.success == false,
        onDismissRequest = vm::clearMessage,
        curvedText = { confirmationDialogCurvedText(lastText, style) },
    )
}
