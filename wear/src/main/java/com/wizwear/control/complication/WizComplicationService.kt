package com.wizwear.control.complication

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.util.Log
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.wizwear.control.R
import com.wizwear.control.WizApp
import com.wizwear.control.data.BulbStatus

/**
 * Watch-face complication: shows the last known state and toggles the
 * light(s) when tapped. Battery-friendly by design — it never polls
 * (UPDATE_PERIOD_SECONDS = 0) and never turns Wi-Fi on just to render;
 * it is refreshed whenever the app, tile or a tap changes the state.
 */
class WizComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, on = true, dimming = 70, offline = false)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val repo = (application as WizApp).repository
        val first = repo.bulbs.value.firstOrNull()
        val status = first?.let { repo.status.value[it.mac] }
        val state = (status as? BulbStatus.Online)?.state
        return build(
            request.complicationType,
            on = state?.on == true,
            dimming = state?.dimming,
            offline = first == null || status == BulbStatus.Offline,
        )
    }

    private fun build(type: ComplicationType, on: Boolean, dimming: Int?, offline: Boolean): ComplicationData? {
        val label = when {
            offline -> "—"
            on -> dimming?.let { "$it%" } ?: "On"
            else -> "Off"
        }
        val desc = PlainComplicationText.Builder(
            when {
                offline -> "WiZ light unavailable"
                on -> "Light on $label. Tap to turn off"
                else -> "Light off. Tap to turn on"
            },
        ).build()
        val icon = MonochromaticImage.Builder(
            Icon.createWithResource(this, if (on) R.drawable.ic_bulb else R.drawable.ic_bulb_off),
        ).build()
        val tap = toggleIntent(this)

        return when (type) {
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(PlainComplicationText.Builder(label).build(), desc)
                    .setMonochromaticImage(icon)
                    .setTapAction(tap)
                    .build()

            ComplicationType.RANGED_VALUE ->
                RangedValueComplicationData.Builder(
                    value = if (on) (dimming ?: 100).toFloat() else 0f,
                    min = 0f,
                    max = 100f,
                    contentDescription = desc,
                )
                    .setMonochromaticImage(icon)
                    .setText(PlainComplicationText.Builder(label).build())
                    .setTapAction(tap)
                    .build()

            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(icon, desc)
                    .setTapAction(tap)
                    .build()

            else -> null
        }
    }

    companion object {
        private const val TAG = "WizComplication"

        fun toggleIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ToggleReceiver::class.java).setAction(ToggleReceiver.ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun requestUpdate(context: Context) {
            runCatching {
                ComplicationDataSourceUpdateRequester.create(
                    context.applicationContext,
                    ComponentName(context.applicationContext, WizComplicationService::class.java),
                ).requestUpdateAll()
            }.onFailure { Log.w(TAG, "Complication update request failed", it) }
        }
    }
}
