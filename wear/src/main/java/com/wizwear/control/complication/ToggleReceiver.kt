package com.wizwear.control.complication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import com.wizwear.control.WizApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Complication tap: toggle all saved bulbs. The UI state flips immediately
 * (optimistic), Wi-Fi comes up only for this one command and is released a
 * few seconds later.
 */
class ToggleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TOGGLE) return
        // Never let feedback kill the toggle itself.
        runCatching {
            context.getSystemService(Vibrator::class.java)
                ?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
        }

        val pending = goAsync()
        val repo = (context.applicationContext as WizApp).repository
        scope.launch {
            try {
                val outcome = withTimeoutOrNull(20_000) { repo.toggleAll() }
                Log.i(TAG, "complication toggle: $outcome")
            } finally {
                WizComplicationService.requestUpdate(context)
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.wizwear.control.TOGGLE"
        private const val TAG = "WizToggle"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
