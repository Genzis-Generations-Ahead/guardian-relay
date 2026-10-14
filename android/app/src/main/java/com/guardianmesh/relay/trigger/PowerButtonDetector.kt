package com.guardianmesh.relay.trigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.guardianmesh.relay.diag.AppLog

/**
 * "Press the power button three times" trigger.
 *
 * An app cannot intercept KEYCODE_POWER — the system swallows it long before any app
 * sees it, and that is by design. What an app *can* see is the effect: every power
 * press toggles the screen, and ACTION_SCREEN_ON / ACTION_SCREEN_OFF are broadcast
 * each time. Count [REQUIRED_PRESSES] of those toggles inside [WINDOW_MS] and you have
 * detected a triple press, without needing root or an accessibility service.
 *
 * Two consequences worth knowing:
 *  - These two broadcasts cannot be declared in the manifest. They only arrive at a
 *    receiver registered from running code, which is exactly why [TriggerService] is a
 *    foreground service.
 *  - Many Android builds map five power presses to the system's own Emergency SOS. That
 *    runs in parallel and is a good thing; three presses stay ours.
 */
class PowerButtonDetector(
    private val context: Context,
    private val onTriplePress: () -> Unit,
    /** Also told about plain screen on/off, which the volume detector uses to arm itself. */
    private val onScreenState: (on: Boolean) -> Unit = {}
) {

    private val presses = ArrayDeque<Long>()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            val screenOn = when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> true
                Intent.ACTION_SCREEN_OFF -> false
                else -> return
            }
            onScreenState(screenOn)
            record()
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Android 14 wants an export flag on dynamic receivers. These are protected system
        // broadcasts, so NOT_EXPORTED still receives them — same pattern as RelayService.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        registered = true
        presses.clear()
        AppLog.i(TAG, "Power-button trigger armed (press $REQUIRED_PRESSES× within ${WINDOW_MS / 1000}s)")
    }

    fun stop() {
        if (!registered) return
        registered = false
        runCatching { context.unregisterReceiver(receiver) }
        presses.clear()
        AppLog.i(TAG, "Power-button trigger disarmed")
    }

    private fun record() {
        val now = System.currentTimeMillis()
        presses.addLast(now)
        while (presses.isNotEmpty() && now - presses.first() > WINDOW_MS) presses.removeFirst()
        AppLog.d(TAG, "screen toggled (${presses.size}/$REQUIRED_PRESSES in window)")
        if (presses.size >= REQUIRED_PRESSES) {
            presses.clear()
            AppLog.w(TAG, "Power button pressed $REQUIRED_PRESSES× — firing SOS")
            onTriplePress()
        }
    }

    private companion object {
        const val TAG = "POWER-KEY"
        const val REQUIRED_PRESSES = 3
        const val WINDOW_MS = 3_000L
    }
}
