package com.guardianmesh.relay.location

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.Notifications
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.diag.Permissions
import com.guardianmesh.relay.sos.SosDispatcher
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service (type=location) that runs ONLY while an SOS is active.
 * Every 10 s it hands the best current fix to SosDispatcher, which pushes it to
 * the backend / refreshes the BLE advert / buffers it while offline.
 *
 * Being a foreground service is also what keeps the process — and therefore the
 * BLE advertisement — alive when the screen is off on MIUI / One UI.
 *
 * Started by SosDispatcher.send(); you don't need to start it yourself.
 * Must be started while the app is in the foreground (Android 12+ rule for
 * location FGS); SosDispatcher.send() from a button tap satisfies that.
 */
class LocationService : Service() {

    companion object {
        private const val TAG = "LOC-SVC"
        const val INTERVAL_MS = 10_000L
        private const val NOTIF_ID = 4101

        private const val EXTRA_FG = "fg"

        fun start(ctx: Context) {
            try {
                if (Permissions.hasAnyLocation(ctx)) {
                    ctx.startForegroundService(Intent(ctx, LocationService::class.java).putExtra(EXTRA_FG, true))
                } else {
                    // Android 14 throws if a type=location FGS starts without location permission, and a
                    // startForegroundService() that never reaches startForeground() crashes the app.
                    // Run as a plain service instead: ticks (HTTP retry / BLE escalation) still run while the app is open.
                    AppLog.w(TAG, "No location permission → no live location, and tracking may stop in background")
                    ctx.startService(Intent(ctx, LocationService::class.java).putExtra(EXTRA_FG, false))
                }
            } catch (t: Throwable) {
                // ForegroundServiceStartNotAllowedException (API 31+) if called from background.
                AppLog.e(TAG, "Could not start LocationService (app in background?)", t)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, LocationService::class.java))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var provider: LocationProvider
    private var ticks = 0
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val ticker = object : Runnable {
        override fun run() {
            ticks++
            val fix = provider.best
            SosDispatcher.onLocationTick(fix)
            val txt = if (fix == null) "Waiting for GPS fix… (Location toggle on?)"
            else "Sharing location every 10 s · last fix ${timeFmt.format(Date(fix.timeMs))} ±${fix.accuracyM?.toInt() ?: "?"}m"
            Notifications.update(this@LocationService, NOTIF_ID, notification(txt))
            if (fix != null && fix.ageMs() > 60_000 && ticks % 6 == 0) {
                AppLog.w(TAG, "Newest fix is ${fix.ageMs() / 1000}s old — indoors / GPS blocked? (still sending last known)")
            }
            main.postDelayed(this, INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Guardian.ensureInit(this)
        provider = LocationProvider(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null || intent.getBooleanExtra(EXTRA_FG, true)) goForeground()
        if (intent == null) {
            // System restarted us after killing the process (START_STICKY).
            if (!SosDispatcher.restore(this)) { stopSelf(); return START_NOT_STICKY }
        }
        if (ticks == 0) {
            provider.start(INTERVAL_MS) { fix ->
                AppLog.d(TAG, "fix ${fix.latLngString()} ±${fix.accuracyM?.toInt() ?: "?"}m via ${fix.provider}")
            }
            main.removeCallbacks(ticker)
            main.postDelayed(ticker, INTERVAL_MS)
            AppLog.ok(TAG, "Live location sharing started (every ${INTERVAL_MS / 1000}s)")
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = notification("Starting location sharing…")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            // Android 14: SecurityException if location permission isn't granted at this moment.
            AppLog.e(TAG, "startForeground(location) refused — tracking only works while app is open", t)
        }
    }

    private fun notification(text: String) =
        Notifications.build(this, Notifications.CH_SOS, "SOS active — sharing your location", text)

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        provider.stop()
        AppLog.i(TAG, "Live location sharing stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
