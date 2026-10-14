package com.guardianmesh.relay.trigger

import android.content.Context
import android.content.Intent
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.Notifications
import com.guardianmesh.relay.deterrent.DeterrentActivity
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.location.LocationProvider
import com.guardianmesh.relay.sos.SosDispatcher
import com.guardianmesh.relay.sos.SosEvent

/**
 * Every way an SOS can start funnels through here: the big button, a hardware gesture,
 * the duress PIN, and the "I'm being followed" screen. One place to reason about, and
 * one place that knows about the Android 12+ background-start rules.
 *
 * It does not reimplement delivery — `SosDispatcher.send()` still runs the
 * HTTP → SMS → BLE chain, the offline buffer and the 10 s location pushes.
 *
 * ## What works from the background, and what doesn't
 * A hardware gesture can fire with the screen off and the app not running. In that state
 * Android will not let the app start an activity, and `LocationService` (a `location`
 * foreground service) usually can't start either — Android 12+ only allows that from the
 * foreground. So from the background this:
 *   - sends the SOS with the last known fix (`LocationProvider.lastKnown()`, which needs
 *     no service), so the alert and the location still get out; and
 *   - posts a high-importance notification, tapping which opens the SOS screen, and the
 *     SOS screen starts live tracking as soon as it is visible.
 * The alert is never silently dropped — only the live trail waits for the screen.
 */
object SosTrigger {

    private const val TAG = "TRIGGER"
    const val NOTIF_ID = 4103

    /** Where the SOS came from. Goes into the event's `source` field and the backend record. */
    enum class Source(val wire: String, val label: String) {
        BUTTON("manual", "SOS button"),
        POWER_BUTTON("power-button-3x", "power button ×3"),
        SHAKE("shake", "shake"),
        VOLUME_KEYS("volume-keys", "volume up+down"),
        DURESS_PIN("duress-pin", "duress PIN"),
        CLASSIFIER("classifier", "followed-detection"),
        DEBUG_CONSOLE("debug-console", "debug console")
    }

    /**
     * Raise an SOS.
     *
     * @param silent no notification, no deterrent, nothing on screen — for the duress PIN,
     *               where anything visible defeats the point.
     * @param withDeterrent also launch Public Deterrent Mode (ignored when [silent]).
     */
    fun fire(
        context: Context,
        source: Source,
        silent: Boolean = false,
        withDeterrent: Boolean = false,
        note: String? = null,
        severity: SosEvent.Severity = SosEvent.Severity.HIGH
    ) {
        Guardian.ensureInit(context)
        val ctx = context.applicationContext

        if (SosDispatcher.isActive) {
            AppLog.i(TAG, "SOS already active — ${source.label} re-runs delivery with the newest fix")
        } else {
            AppLog.w(TAG, "SOS raised by ${source.label}${if (silent) " (silent)" else ""}")
        }

        // lastKnown() reads the cached system fix; unlike LocationService it needs no
        // foreground service, so it works from the background. SosDispatcher will still
        // wait briefly for a fresher fix and attach later ones as they arrive.
        val fix = runCatching { LocationProvider(ctx).lastKnown() }.getOrNull()

        val event = SosEvent.create(
            severity = severity,
            fix = fix,
            note = note,
            source = source.wire
        )

        SosDispatcher.send(event) { outcome ->
            AppLog.i(TAG, outcome.summary())
            if (!silent) notifyOutcome(ctx, outcome.deliveredVia)
        }

        if (!silent) {
            notifyOutcome(ctx, null)
            if (withDeterrent) DeterrentActivity.start(ctx, fromBackground = true)
        }
    }

    /** "I'm safe". Stops tracking, BLE and the alarm, and tells the backend. */
    fun stop(context: Context) {
        SosDispatcher.stop()
        DeterrentActivity.stop(context)
        runCatching {
            context.getSystemService(android.app.NotificationManager::class.java).cancel(NOTIF_ID)
        }
        AppLog.ok(TAG, "SOS stopped")
    }

    /**
     * The only feedback a user gets when the gesture fired with the screen off. Tapping it
     * opens the app, which is also what lets live tracking start.
     */
    private fun notifyOutcome(ctx: Context, deliveredVia: String?) {
        val text = when (deliveredVia) {
            null -> "Sending your location and alerting your contacts…"
            "HTTP" -> "Sent to the Guardian server with your location."
            "SMS" -> "No internet — your trusted contacts were texted your location."
            "BLE" -> "No internet or signal — broadcasting over Guardian Mesh to nearby phones."
            else -> "Alert sent via $deliveredVia."
        }
        runCatching {
            Notifications.update(
                ctx, NOTIF_ID,
                Notifications.build(ctx, Notifications.CH_SOS, "SOS active — tap to open", text)
            )
        }.onFailure { AppLog.d(TAG, "Could not post the SOS notification: ${it.message}") }
    }

    /** Used by the deterrent screen's notification action and by the lock screen. */
    fun openAppIntent(ctx: Context): Intent? =
        ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
}
