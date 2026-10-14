package com.guardianmesh.relay

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.guardianmesh.relay.ble.RelayService
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.diag.Diagnostics
import com.guardianmesh.relay.diag.Permissions
import com.guardianmesh.relay.location.GeoFix
import com.guardianmesh.relay.location.LocationProvider
import com.guardianmesh.relay.net.BackendClient
import com.guardianmesh.relay.safeexit.SafeExit
import com.guardianmesh.relay.sos.SosDispatcher
import com.guardianmesh.relay.sos.SosEvent

/**
 * Debug/demo console for testing on two real phones. Everything it does goes
 * through the same public API Tanush's UI will use — this screen holds no logic.
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "UI"
        const val REQ_PERMS = 1
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var tvStatus: TextView
    private lateinit var etBackend: EditText
    private lateinit var etContacts: EditText
    private lateinit var etName: EditText
    private lateinit var cbBleAlways: CheckBox

    @Volatile private var logDirty = true
    private var sosState: SosDispatcher.State? = null
    private val logListener: (AppLog.Line) -> Unit = { logDirty = true }
    private val sosListener: (SosDispatcher.State) -> Unit = { sosState = it }
    private val locationProvider by lazy { LocationProvider(this) }

    private val uiTicker = object : Runnable {
        override fun run() {
            if (logDirty) { logDirty = false; renderLog() }
            renderStatus()
            main.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Guardian.ensureInit(this)
        setContentView(R.layout.activity_main)

        tvLog = findViewById(R.id.tvLog)
        svLog = findViewById(R.id.svLog)
        tvStatus = findViewById(R.id.tvStatus)
        etBackend = findViewById(R.id.etBackendUrl)
        etContacts = findViewById(R.id.etContacts)
        etName = findViewById(R.id.etName)
        cbBleAlways = findViewById(R.id.cbBleAlways)

        val cfg = Guardian.config
        etBackend.setText(cfg.backendBaseUrl)
        etContacts.setText(cfg.trustedContacts.joinToString(", "))
        etName.setText(cfg.senderName)
        cbBleAlways.isChecked = cfg.bleAlways
        findViewById<TextView>(R.id.tvDevice).text =
            "This phone: id ${"%04x".format(Guardian.deviceShortId)} · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · ${Diagnostics.romName()}"

        click(R.id.btnSave) { saveSettings() }

        // Sender
        click(R.id.btnTriggerSos) {
            saveSettings()
            SosDispatcher.send(SosEvent.create(note = "manual trigger from debug console")) { outcome ->
                toast(outcome.summary())
            }
        }
        click(R.id.btnStopSos) { SosDispatcher.stop() }
        click(R.id.btnSimHigh) { simulateClassifier(high = true) }
        click(R.id.btnSimMedium) { simulateClassifier(high = false) }
        click(R.id.btnSafeExit) { withLocation { fix -> fix?.let { SafeExit.start(it) { r -> AppLog.i(TAG, "SafeExit → $r") } } } }

        // Relay
        click(R.id.btnStartRelay) { saveSettings(); RelayService.stop(this); RelayService.start(this) }
        click(R.id.btnStopRelay) { RelayService.stop(this) }
        click(R.id.btnRawScan) {
            AppLog.i(TAG, "RAW mode: counts EVERY BLE device. If 'devices' climbs but 'SOS pkts' stays 0 → sender isn't advertising. If both stay 0 → this phone's scanning is blocked (permission / location toggle / BT).")
            RelayService.stop(this); RelayService.start(this, raw = true)
        }

        // Diagnostics
        click(R.id.btnDiagnostics) { Diagnostics.runAndLog(this) }
        click(R.id.btnPermissions) { requestMissingPermissions(force = true) }
        click(R.id.btnAppSettings) { Diagnostics.openAppSettings(this) }
        click(R.id.btnEnableBt) { Diagnostics.requestEnableBluetooth(this) }
        click(R.id.btnFixBattery) { Diagnostics.requestBatteryExemption(this) }
        click(R.id.btnFixOem) { Diagnostics.openOemBackgroundSettings(this) }
        click(R.id.btnLocationSettings) { Diagnostics.openLocationSettings(this) }
        click(R.id.btnCopyLog) {
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("guardian-log", AppLog.dump()))
            toast("Log copied — paste it into chat")
        }
        click(R.id.btnClearLog) { AppLog.clear(); logDirty = true }

        requestMissingPermissions(force = false)
    }

    override fun onResume() {
        super.onResume()
        AppLog.addListener(logListener)
        SosDispatcher.addListener(sosListener)
        logDirty = true
        main.post(uiTicker)
    }

    override fun onPause() {
        AppLog.removeListener(logListener)
        SosDispatcher.removeListener(sosListener)
        main.removeCallbacks(uiTicker)
        super.onPause()
    }

    // ───────────────────────── actions ─────────────────────────

    private fun saveSettings() {
        val cfg = Guardian.config
        cfg.backendBaseUrl = etBackend.text.toString()
        cfg.trustedContacts = etContacts.text.toString().split(',')
        cfg.senderName = etName.text.toString()
        cfg.bleAlways = cbBleAlways.isChecked
        etBackend.setText(cfg.backendBaseUrl)
        AppLog.d(TAG, "Settings saved: backend='${cfg.backendBaseUrl}', ${cfg.trustedContacts.size} contact(s), bleAlways=${cfg.bleAlways}")
    }

    /** Shows the classifier → routing wiring: HIGH → SosDispatcher, MEDIUM/LOW → SafeExit. */
    private fun simulateClassifier(high: Boolean) {
        saveSettings()
        val input = if (high) FollowingClassifier.FollowingInput(12, 2, 2.0, true, 2, true, false)
        else FollowingClassifier.FollowingInput(6, 1, 6.0, false, 5, false, true)
        val result = FollowingClassifier.classify(input)
        AppLog.i(TAG, "Classifier → ${result.severity} → ${result.route}  (${result.reasons.joinToString("; ")})")
        withLocation(required = result.route == FollowingClassifier.Route.SAFE_EXIT) { fix ->
            when (result.route) {
                FollowingClassifier.Route.SOS -> SosDispatcher.send(SosEvent.fromClassification(result, fix)) { toast(it.summary()) }
                FollowingClassifier.Route.SAFE_EXIT -> fix?.let { SafeExit.start(it) }
            }
        }
    }

    /** Gets a fix off the main thread (cached ≤10 min, else waits ≤5 s), then calls back on main. */
    private fun withLocation(required: Boolean = true, block: (GeoFix?) -> Unit) {
        Thread {
            val fix = locationProvider.lastKnown(10 * 60_000L) ?: locationProvider.currentFix(5_000)
            if (fix == null && required) {
                AppLog.e(TAG, "No location fix — permission granted? Location toggle on? Try near a window.")
                return@Thread
            }
            main.post { block(fix) }
        }.start()
    }

    // ───────────────────────── permissions ─────────────────────────

    private fun requestMissingPermissions(force: Boolean) {
        val missing = Permissions.missing(this)
        if (missing.isEmpty()) {
            if (force) AppLog.ok(TAG, "All permissions already granted")
            return
        }
        AppLog.i(TAG, "Requesting: ${missing.joinToString { Permissions.shortName(it) }}")
        requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val denied = mutableListOf<String>()
        permissions.forEachIndexed { i, p ->
            val ok = grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED
            if (ok) AppLog.ok(TAG, "granted ${Permissions.shortName(p)}") else { denied += p; AppLog.w(TAG, "DENIED ${Permissions.shortName(p)}") }
        }
        if (Permissions.granted(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) && !Permissions.hasFineLocation(this)) {
            AppLog.w(TAG, "User picked APPROXIMATE location — SOS location will be ~2 km off. Ask for 'Precise'.")
        }
        val blocked = Permissions.permanentlyDenied(this, denied)
        if (blocked.isNotEmpty()) {
            AppLog.e(TAG, "Android will no longer show the dialog for: ${blocked.joinToString { Permissions.shortName(it) }} → tap 'App settings' → Permissions")
        }
    }

    // ───────────────────────── rendering ─────────────────────────

    private fun renderStatus() {
        val s = sosState
        val sos = when {
            s == null || !s.active -> "SOS: idle"
            else -> buildString {
                append("SOS ACTIVE ${s.event!!.eventId} · delivered via ${s.deliveredVia ?: "NOTHING YET"}")
                if (s.bleBroadcasting) append(" · BLE on")
                s.event.fix?.let { append(" · ${it.latLngString()} ±${it.accuracyM?.toInt() ?: "?"}m") }
                if (s.consecutivePushFailures > 0) append(" · offline ${s.consecutivePushFailures * 10}s, ${s.bufferedPoints} buffered")
            }
        }
        val adv = Guardian.advertiser.activeKeys()
        val net = BackendClient.networkLabel(this)
        tvStatus.text = "$sos\nAdverts: ${if (adv.isEmpty()) "none" else adv.joinToString()} · Network: $net\n${RelayService.stats()}"
    }

    private fun renderLog() {
        val lines = AppLog.snapshot().takeLast(250)
        val sb = SpannableStringBuilder()
        for (l in lines) {
            val start = sb.length
            sb.append(l.format()).append('\n')
            val color = when (l.level) {
                AppLog.Level.ERROR -> Color.rgb(0xFF, 0x6E, 0x6E)
                AppLog.Level.WARN -> Color.rgb(0xFF, 0xC1, 0x07)
                AppLog.Level.OK -> Color.rgb(0x00, 0xE6, 0x76)
                AppLog.Level.DEBUG -> Color.rgb(0x9E, 0x9E, 0x9E)
                AppLog.Level.INFO -> Color.rgb(0xE0, 0xE0, 0xE0)
            }
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Only auto-scroll if the user is already at the bottom (so you can scroll up and read).
        val atBottom = svLog.getChildAt(0)?.let { it.bottom - (svLog.height + svLog.scrollY) < 80 } ?: true
        tvLog.text = sb
        if (atBottom) svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun click(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener {
            try { action() } catch (t: Throwable) { AppLog.e(TAG, "Button action crashed", t) }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
