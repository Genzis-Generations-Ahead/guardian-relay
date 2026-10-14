package com.guardianmesh.relay.trigger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.data.AppSettings
import com.guardianmesh.relay.diag.AppLog

/**
 * The one-action SOS listener: a foreground service that watches for the three panic
 * gestures and nothing else.
 *
 *     TriggerService.start(context, settings)   // (re)arm with the user's choices
 *     TriggerService.stop(context)
 *     TriggerService.statusLine()               // for the Settings screen
 *
 * Foreground, because all three detectors need live code: the screen on/off broadcasts
 * can't be declared in a manifest, the accelerometer listener dies with the process, and
 * the media session has to stay registered. The notification is the honest price of
 * that, and Android requires it.
 *
 * FGS type is `specialUse` — the Android 14 category for exactly this, with the
 * justification declared as a `<property>` in AndroidManifest.xml.
 *
 * ## Which gesture does what
 *  - **power button ×3** — screen-toggle counting (see [PowerButtonDetector])
 *  - **shake** — 3 jolts in 1.5 s (see [ShakeDetector])
 *  - **volume up+down** — media-session remote volume while the screen is off
 *    (see [VolumeKeyDetector])
 *
 * A [COOLDOWN_MS] lockout after any trigger stops one frantic burst of movement from
 * firing three times; `SosDispatcher` would merge them into one event anyway, but the
 * log stays readable and the radios stay quiet.
 */
class TriggerService : Service() {

    companion object {
        private const val TAG = "TRIGGER-SVC"
        private const val NOTIF_ID = 4104
        private const val CHANNEL = "guardian_triggers"
        private const val COOLDOWN_MS = 10_000L

        private const val EXTRA_POWER = "power"
        private const val EXTRA_SHAKE = "shake"
        private const val EXTRA_VOLUME = "volume"
        private const val EXTRA_SENSITIVITY = "sensitivity"
        private const val EXTRA_DETERRENT = "deterrent"

        @Volatile private var instance: TriggerService? = null

        val isRunning: Boolean get() = instance != null

        /**
         * Start or re-arm. Safe to call on every settings change — the service reads the
         * new flags in onStartCommand and rebuilds only what changed.
         * With all three gestures off it stops instead, so there's no pointless notification.
         */
        fun start(ctx: Context, settings: AppSettings) {
            if (!settings.powerButtonTriggerEnabled &&
                !settings.shakeTriggerEnabled &&
                !settings.volumeKeyTriggerEnabled
            ) {
                stop(ctx)
                return
            }
            val intent = Intent(ctx, TriggerService::class.java)
                .putExtra(EXTRA_POWER, settings.powerButtonTriggerEnabled)
                .putExtra(EXTRA_SHAKE, settings.shakeTriggerEnabled)
                .putExtra(EXTRA_VOLUME, settings.volumeKeyTriggerEnabled)
                .putExtra(EXTRA_SENSITIVITY, settings.shakeSensitivity)
                .putExtra(EXTRA_DETERRENT, settings.deterrentWithTrigger)
            try {
                ctx.startForegroundService(intent)
            } catch (t: Throwable) {
                // API 31+: can't start an FGS from the background. The user will re-arm
                // next time they open the app, and BootReceiver covers a restart.
                AppLog.e(TAG, "Could not start the trigger listener (app in background?)", t)
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, TriggerService::class.java))
        }

        /** One line for the Settings screen. */
        fun statusLine(): String {
            val s = instance ?: return "Panic gestures: off"
            val armed = buildList {
                if (s.powerEnabled) add("power ×3")
                if (s.shakeEnabled) add("shake")
                if (s.volumeEnabled) add("volume up+down${if (s.volume?.isArmed == true) "" else " (screen off)"}")
            }
            return if (armed.isEmpty()) "Panic gestures: off" else "Panic gestures: ${armed.joinToString(", ")}"
        }

        /** HomeActivity forwards volume keys here so the combo also works with the app open. */
        fun onActivityVolumeKey(keyCode: Int): Boolean =
            instance?.let { if (it.volumeEnabled) it.volume?.onKeyEvent(keyCode) ?: false else false } ?: false
    }

    private var power: PowerButtonDetector? = null
    private var shake: ShakeDetector? = null
    private var volume: VolumeKeyDetector? = null

    @Volatile private var powerEnabled = false
    @Volatile private var shakeEnabled = false
    @Volatile private var volumeEnabled = false
    @Volatile private var deterrentWithTrigger = false

    private var lastTriggerAt = 0L

    override fun onCreate() {
        super.onCreate()
        Guardian.ensureInit(this)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        powerEnabled = intent?.getBooleanExtra(EXTRA_POWER, true) ?: powerEnabled
        shakeEnabled = intent?.getBooleanExtra(EXTRA_SHAKE, true) ?: shakeEnabled
        volumeEnabled = intent?.getBooleanExtra(EXTRA_VOLUME, true) ?: volumeEnabled
        deterrentWithTrigger = intent?.getBooleanExtra(EXTRA_DETERRENT, false) ?: deterrentWithTrigger
        val sensitivity = intent?.getIntExtra(EXTRA_SENSITIVITY, 2) ?: 2

        // The power detector also reports plain screen on/off, which is what arms and
        // disarms the volume detector — so it runs whenever either gesture is enabled.
        if (powerEnabled || volumeEnabled) {
            if (power == null) {
                power = PowerButtonDetector(
                    context = this,
                    onTriplePress = { if (powerEnabled) fire(SosTrigger.Source.POWER_BUTTON) },
                    onScreenState = { on -> onScreenState(on) }
                ).also { it.start() }
            }
        } else {
            power?.stop(); power = null
        }

        if (shakeEnabled) {
            val d = shake ?: ShakeDetector(this) { fire(SosTrigger.Source.SHAKE) }.also { shake = it }
            d.sensitivity = sensitivity
            d.start()
        } else {
            shake?.stop(); shake = null
        }

        if (volumeEnabled) {
            if (volume == null) volume = VolumeKeyDetector(this) { fire(SosTrigger.Source.VOLUME_KEYS) }
            // Arm straight away if the screen is already off (e.g. re-armed after a reboot).
            if (!isScreenOn()) volume?.arm()
        } else {
            volume?.disarm(); volume = null
        }

        AppLog.ok(TAG, statusLine())
        updateNotification()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        power?.stop(); power = null
        shake?.stop(); shake = null
        volume?.disarm(); volume = null
        AppLog.i(TAG, "Trigger listener stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ───────────────────────────── internals ─────────────────────────────

    private fun onScreenState(screenOn: Boolean) {
        if (!volumeEnabled) return
        // Only hold the volume keys while the screen is off — see VolumeKeyDetector's note.
        if (screenOn) volume?.disarm() else volume?.arm()
    }

    private fun isScreenOn(): Boolean =
        runCatching { getSystemService(PowerManager::class.java).isInteractive }.getOrDefault(true)

    private fun fire(source: SosTrigger.Source) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < COOLDOWN_MS) {
            AppLog.d(TAG, "${source.label} ignored — ${(COOLDOWN_MS - (now - lastTriggerAt)) / 1000}s of cooldown left")
            return
        }
        lastTriggerAt = now
        SosTrigger.fire(
            context = this,
            source = source,
            withDeterrent = deterrentWithTrigger,
            note = "Raised by ${source.label} without unlocking the phone"
        )
        updateNotification()
    }

    // ───────────────────────────── notification ─────────────────────────────

    private fun promoteToForeground() {
        val n = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "startForeground(specialUse) refused — gestures only work while the app is open", t)
        }
    }

    private fun updateNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Panic gestures", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Guardian is watching for the power-button, shake or volume-key SOS gesture"
                setShowBadge(false)
            }
        )
        val pi = SosTrigger.openAppIntent(this)?.let {
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("Guardian is watching")
            .setContentText(statusLine())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (pi != null) setContentIntent(pi) }
            .build()
    }
}
