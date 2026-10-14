package com.guardianmesh.relay.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.diag.Permissions

/**
 * Scans for 0xFDA1 adverts and reports every decoded packet (no de-dupe here —
 * MeshRelay decides what's new). Also keeps live counters so you can see on screen
 * whether pickup is actually reliable instead of guessing.
 *
 * Android traps this handles:
 *  - >5 startScan() calls in 30 s → Android silently returns NO results (only a
 *    logcat line). We rate-limit our own restarts.
 *  - A scan running >30 min is silently downgraded to opportunistic → we restart
 *    every 25 min.
 *  - Unfiltered scans are paused while the screen is off (Android 8.1+). Normal
 *    mode uses a filter; "raw" mode (debug) is unfiltered, so keep the screen on.
 */
class BleScannerManager(
    context: Context,
    private val onSignal: (RelaySignal, Int) -> Unit
) {
    companion object {
        private const val TAG = "BLE-SCAN"
        private const val THROTTLE_WINDOW_MS = 30_000L
        private const val THROTTLE_MAX_STARTS = 4 // Android's limit is 5; stay under it
        private const val RESTART_EVERY_MS = 25 * 60_000L
        private const val SILENCE_WARN_MS = 15_000L

        /** Android counts scan starts per APP, so this must survive scanner/service re-creation (main thread only). */
        private val startTimes = ArrayDeque<Long>()

        fun errorName(code: Int): String = when (code) {
            ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED"
            ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED ->
                "APPLICATION_REGISTRATION_FAILED (too many scanners / BT stack stuck → toggle Bluetooth)"
            ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR"
            ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED"
            5 -> "OUT_OF_HARDWARE_RESOURCES"
            6 -> "SCANNING_TOO_FREQUENTLY (>5 starts in 30 s)"
            else -> "UNKNOWN($code)"
        }
    }

    data class Stats(
        val running: Boolean,
        val rawMode: Boolean,
        val matchedPackets: Long,      // decoded 0xFDA1 packets
        val rawAdverts: Long,          // any advert from any device (raw mode only)
        val rawDevices: Int,           // distinct MACs seen (raw mode only)
        val lastHitAgoMs: Long?,       // since last 0xFDA1 packet
        val lastRssi: Int?,
        val hitsLast10s: Int,
        val restarts: Int,
        val lastError: String?
    ) {
        fun oneLine(): String {
            if (!running) return "Scanner: stopped" + (lastError?.let { " — last error: $it" } ?: "")
            val ago = lastHitAgoMs?.let { "%.1fs ago".format(it / 1000.0) } ?: "never"
            val raw = if (rawMode) " | RAW: $rawAdverts adverts / $rawDevices devices" else ""
            return "Scanner ON: $matchedPackets SOS pkts, ${hitsLast10s}/10s, last $ago, RSSI ${lastRssi ?: "-"}$raw"
        }
    }

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val adapter get() = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    // All state below is only mutated on the main thread (scan callbacks arrive there).
    private var callback: ScanCallback? = null
    private var wantRunning = false
    private var rawMode = false
    private val recentHits = ArrayDeque<Long>()
    private val rawMacs = HashSet<String>()
    @Volatile private var matched = 0L
    @Volatile private var rawCount = 0L
    @Volatile private var lastHitAt = 0L
    @Volatile private var lastRssi: Int? = null
    @Volatile private var restarts = 0
    @Volatile private var lastError: String? = null
    private var silenceWarned = false
    private var missingDataWarned = false

    private val restartRunnable = Runnable {
        AppLog.i(TAG, "25-min restart (Android downgrades scans older than 30 min)")
        restarts++
        restartInternal()
    }
    private val silenceCheck = object : Runnable {
        override fun run() {
            if (!wantRunning) return
            val now = SystemClock.elapsedRealtime()
            if (lastHitAt > 0 && now - lastHitAt > SILENCE_WARN_MS && !silenceWarned) {
                silenceWarned = true
                AppLog.w(TAG, "No SOS adverts for ${(now - lastHitAt) / 1000}s — sender stopped, out of range, or its advertising was killed in background")
            }
            main.postDelayed(this, 5_000)
        }
    }

    fun start(raw: Boolean = false) = main.post {
        wantRunning = true
        rawMode = raw
        if (raw) { rawMacs.clear(); rawCount = 0 }
        restartInternal()
        main.removeCallbacks(silenceCheck)
        main.post(silenceCheck)
    }

    fun stop() = main.post {
        wantRunning = false
        main.removeCallbacks(restartRunnable)
        main.removeCallbacks(silenceCheck)
        stopInternal()
        AppLog.i(TAG, "Scanner stopped")
    }

    /** Call when Bluetooth turns back on — scans die silently when BT goes off. */
    fun onBluetoothReEnabled() = main.post { if (wantRunning) { restarts++; restartInternal() } }

    fun isRunning() = callback != null

    fun stats(): Stats {
        val now = SystemClock.elapsedRealtime()
        val hits10 = synchronized(recentHits) {
            while (recentHits.isNotEmpty() && now - recentHits.first() > 10_000) recentHits.removeFirst()
            recentHits.size
        }
        return Stats(
            running = callback != null,
            rawMode = rawMode,
            matchedPackets = matched,
            rawAdverts = rawCount,
            rawDevices = synchronized(rawMacs) { rawMacs.size },
            lastHitAgoMs = if (lastHitAt == 0L) null else now - lastHitAt,
            lastRssi = lastRssi,
            hitsLast10s = hits10,
            restarts = restarts,
            lastError = lastError
        )
    }

    private fun restartInternal() {
        stopInternal()
        // Self-throttle so Android never silently blacklists us.
        val now = SystemClock.elapsedRealtime()
        while (startTimes.isNotEmpty() && now - startTimes.first() > THROTTLE_WINDOW_MS) startTimes.removeFirst()
        if (startTimes.size >= THROTTLE_MAX_STARTS) {
            val waitMs = THROTTLE_WINDOW_MS - (now - startTimes.first()) + 500
            AppLog.w(TAG, "Too many scan starts in 30 s — delaying start by ${waitMs / 1000}s (otherwise Android silently returns nothing)")
            main.postDelayed({ if (wantRunning && callback == null) restartInternal() }, waitMs)
            return
        }
        startTimes.addLast(now)
        startInternal()
    }

    @SuppressLint("MissingPermission")
    private fun startInternal() {
        if (!Permissions.hasScan(appContext)) { setError("BLUETOOTH_SCAN (or location on ≤ Android 11) not granted"); return }
        val bt = adapter ?: run { setError("No Bluetooth adapter"); return }
        if (!bt.isEnabled) { setError("Bluetooth is OFF — will restart when it comes back"); return }
        val scanner = bt.bluetoothLeScanner ?: run { setError("bluetoothLeScanner == null"); return }

        val filters: List<ScanFilter>? = if (rawMode) null else listOf(
            ScanFilter.Builder().setServiceUuid(BleAdvertiserManager.PARCEL_UUID).build()
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)      // report even weak/rare hits
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)                                     // no batching — deliver immediately
            .build()

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)
            override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { handle(it) }
            override fun onScanFailed(errorCode: Int) {
                callback = null
                setError("Scan FAILED: ${errorName(errorCode)}")
            }
        }
        try {
            scanner.startScan(filters, settings, cb)
            callback = cb
            lastError = null
            silenceWarned = false
            main.removeCallbacks(restartRunnable)
            main.postDelayed(restartRunnable, RESTART_EVERY_MS)
            AppLog.ok(
                TAG, if (rawMode) "RAW scan started (no filter — keep screen ON; counts every BLE device nearby)"
                else "Scan started (filter: service UUID 0xFDA1, LOW_LATENCY, AGGRESSIVE match)"
            )
        } catch (se: SecurityException) {
            setError("SecurityException starting scan: ${se.message}")
        } catch (t: Throwable) {
            setError("startScan threw ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        val cb = callback ?: return
        callback = null
        try { adapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: Throwable) { /* BT already off */ }
    }

    private fun setError(msg: String) {
        lastError = msg
        AppLog.e(TAG, msg)
    }

    private fun handle(result: ScanResult) {
        if (rawMode) {
            rawCount++
            synchronized(rawMacs) {
                if (rawMacs.add(result.device?.address ?: "?") && rawMacs.size % 10 == 0) {
                    AppLog.d(TAG, "RAW: ${rawMacs.size} distinct BLE devices so far — radio is scanning fine")
                }
            }
        }
        val record = result.scanRecord ?: return
        val data = record.getServiceData(BleAdvertiserManager.PARCEL_UUID)
        if (data == null) {
            if (!rawMode && !missingDataWarned) {
                missingDataWarned = true
                AppLog.w(TAG, "Matched UUID 0xFDA1 but no service data — sender on an old build, or advert truncated")
            }
            return
        }
        val signal = RelaySignal.fromBytes(data)
        if (signal == null) {
            AppLog.w(TAG, "Undecodable 0xFDA1 payload (${data.size}B): ${data.joinToString("") { "%02x".format(it) }}")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (silenceWarned) AppLog.ok(TAG, "Adverts are back (RSSI ${result.rssi})")
        silenceWarned = false
        matched++
        lastHitAt = now
        lastRssi = result.rssi
        synchronized(recentHits) {
            recentHits.addLast(now)
            while (recentHits.size > 1000) recentHits.removeFirst()
        }
        onSignal(signal, result.rssi)
    }
}
