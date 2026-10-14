package com.guardianmesh.relay.ble

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.Notifications
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.diag.Permissions

/**
 * Foreground service (type=connectedDevice) that makes this phone a relay / mesh node:
 * scans for SOS adverts and hands them to MeshRelay. Running as a foreground service
 * is what keeps scanning alive with the screen off and the app in the background on
 * MIUI / One UI (plus the battery exemptions — see Diagnostics).
 *
 *     RelayService.start(context)            // normal
 *     RelayService.start(context, raw=true)  // debug: unfiltered scan, counts every BLE device
 *     RelayService.stop(context)
 *     RelayService.stats()                   // live counters for the UI
 */
class RelayService : Service() {

    companion object {
        private const val TAG = "RELAY-SVC"
        private const val NOTIF_ID = 4102
        private const val EXTRA_RAW = "raw"

        @Volatile private var instance: RelayService? = null

        val isRunning get() = instance != null

        fun start(ctx: Context, raw: Boolean = false) {
            if (!Permissions.hasScan(ctx)) {
                // A connectedDevice FGS without BT permission throws on Android 14 → crash. Scanning can't work anyway.
                AppLog.e(TAG, "Can't start relay: BLUETOOTH_SCAN (location on Android ≤11) not granted — tap Permissions")
                return
            }
            try {
                ctx.startForegroundService(Intent(ctx, RelayService::class.java).putExtra(EXTRA_RAW, raw))
            } catch (t: Throwable) {
                AppLog.e(TAG, "Could not start RelayService", t)
            }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, RelayService::class.java)) }

        fun stats(): String {
            val s = instance ?: return "Relay: off"
            val c = s.mesh.counters()
            return s.scanner.stats().oneLine() +
                "\nRelay: ${c.heard} event(s) heard, ${c.forwarded} forwarded to backend, ${c.rebroadcasting} being re-broadcast"
        }
    }

    private lateinit var scanner: BleScannerManager
    private lateinit var mesh: MeshRelay
    private val main = Handler(Looper.getMainLooper())

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1)) {
                BluetoothAdapter.STATE_OFF -> AppLog.w(TAG, "Bluetooth turned OFF — relay paused")
                BluetoothAdapter.STATE_ON -> {
                    AppLog.i(TAG, "Bluetooth back ON — restarting scan")
                    main.postDelayed({ scanner.onBluetoothReEnabled() }, 1_000) // stack needs a moment
                }
            }
        }
    }

    private val notifRefresh = object : Runnable {
        override fun run() {
            val c = mesh.counters()
            Notifications.update(this@RelayService, NOTIF_ID, notification("Heard ${c.heard} · forwarded ${c.forwarded} · re-broadcasting ${c.rebroadcasting}"))
            main.postDelayed(this, 15_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Guardian.ensureInit(this)
        mesh = MeshRelay(this, Guardian.advertiser, { Guardian.config.backendBaseUrl }, Guardian.deviceShortId)
        scanner = BleScannerManager(this) { signal, rssi -> mesh.onSignal(signal, rssi) }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED) // system broadcasts still arrive
        } else {
            registerReceiver(btReceiver, filter)
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("Listening for nearby SOS signals")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (t: Throwable) {
            AppLog.e(TAG, "startForeground(connectedDevice) refused — scanning only while app is open", t)
        }
        val raw = intent?.getBooleanExtra(EXTRA_RAW, false) ?: false
        scanner.start(raw)
        main.removeCallbacks(notifRefresh)
        main.postDelayed(notifRefresh, 15_000)
        AppLog.ok(TAG, "Relay node running (backend: ${Guardian.config.backendBaseUrl.ifBlank { "NOT SET — will only re-broadcast" }})")
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        main.removeCallbacks(notifRefresh)
        scanner.stop()
        mesh.shutdown()
        runCatching { unregisterReceiver(btReceiver) }
        super.onDestroy()
    }

    private fun notification(text: String) =
        Notifications.build(this, Notifications.CH_RELAY, "Guardian relay active", text)

    override fun onBind(intent: Intent?): IBinder? = null
}
