package com.guardianmesh.relay.diag

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.location.LocationProvider
import com.guardianmesh.relay.net.BackendClient

/**
 * "Why doesn't it work on this phone?" — one tap runs every check that has bitten
 * BLE apps on Android 12+ / MIUI / One UI, writes the results to the on-screen log,
 * and offers the Settings screen that fixes each problem.
 */
object Diagnostics {

    private const val TAG = "DIAG"

    enum class Status(val icon: String) { OK("✅"), WARN("⚠️"), FAIL("❌"), INFO("ℹ️") }
    data class Check(val name: String, val status: Status, val detail: String)

    // ───────────────────────── OEM detection ─────────────────────────

    @SuppressLint("PrivateApi")
    private fun sysProp(key: String): String? = try {
        val c = Class.forName("android.os.SystemProperties")
        (c.getMethod("get", String::class.java).invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) { null }

    val manufacturer: String get() = Build.MANUFACTURER.lowercase()
    val isXiaomi get() = manufacturer in setOf("xiaomi", "redmi", "poco")
    val isSamsung get() = manufacturer == "samsung"

    fun romName(): String {
        sysProp("ro.mi.os.version.name")?.let { return "HyperOS $it" }
        sysProp("ro.miui.ui.version.name")?.let { return "MIUI $it" }
        if (isSamsung) {
            sysProp("ro.build.version.oneui")?.toIntOrNull()?.let { v ->
                return "One UI ${v / 10000}.${(v / 100) % 100}"
            }
            return "One UI"
        }
        sysProp("ro.build.version.opporom")?.let { return "ColorOS $it" }
        return "stock-ish"
    }

    // ───────────────────────── checks ─────────────────────────

    @SuppressLint("MissingPermission")
    fun run(ctx: Context): List<Check> {
        val out = mutableListOf<Check>()
        fun add(n: String, s: Status, d: String) = out.add(Check(n, s, d))

        add("Device", Status.INFO, "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${romName()}")

        val pm = ctx.packageManager
        val bt: BluetoothAdapter? = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (!pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) || bt == null) {
            add("BLE hardware", Status.FAIL, "No Bluetooth LE on this device")
        } else {
            add("Bluetooth", if (bt.isEnabled) Status.OK else Status.FAIL, if (bt.isEnabled) "ON" else "OFF — turn it on (airplane mode turns it off on most phones)")
            if (bt.isEnabled) {
                val adv = bt.isMultipleAdvertisementSupported
                add("Can advertise (peripheral mode)", if (adv) Status.OK else Status.FAIL,
                    if (adv) "yes" else "NO — this phone can only be Phone B (relay), not the SOS sender")
                add("Hardware scan filters", Status.INFO,
                    if (bt.isOffloadedFilteringSupported) "offloaded to chip (works with screen off)" else "software filtering (fine, slightly more battery)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    add("BLE 5 extended adverts", Status.INFO,
                        if (bt.isLeExtendedAdvertisingSupported) "supported, max ${bt.leMaximumAdvertisingDataLength}B (we use legacy 31B for compatibility)" else "not supported (fine — we use legacy 31B adverts)")
                }
            }
        }

        // Permissions
        val sdk = Build.VERSION.SDK_INT
        for (p in Permissions.bluetooth()) {
            val g = Permissions.granted(ctx, p)
            add("Permission ${Permissions.shortName(p)}", if (g) Status.OK else Status.FAIL, if (g) "granted" else "DENIED — BLE won't work")
        }
        val fine = Permissions.hasFineLocation(ctx)
        val coarse = Permissions.granted(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        add("Location permission",
            when { fine -> Status.OK; coarse -> Status.WARN; else -> Status.FAIL },
            when { fine -> "precise"; coarse -> "APPROXIMATE only — SOS location will be 1-3 km off. Settings → Permissions → Location → Use precise location"; else -> "DENIED — SOS will go out without location" })
        val sms = Permissions.hasSms(ctx)
        add("Permission SEND_SMS", if (sms) Status.OK else Status.WARN, if (sms) "granted" else "denied — SMS fallback disabled")
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            val n = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            add("Notifications", if (n) Status.OK else Status.WARN, if (n) "enabled" else "disabled — services still run, but the user can't see the SOS-active notification")
        }

        // Location toggle
        val locOn = LocationProvider(ctx).isLocationEnabled()
        add("Location toggle", when {
            locOn -> Status.OK
            sdk <= Build.VERSION_CODES.R -> Status.FAIL
            isXiaomi -> Status.WARN
            else -> Status.WARN
        }, when {
            locOn -> "ON"
            sdk <= Build.VERSION_CODES.R -> "OFF — on Android ≤11 BLE scanning returns NOTHING without it"
            isXiaomi -> "OFF — no SOS location, and several MIUI builds return no BLE scan results with it off"
            else -> "OFF — SOS will have no location"
        })

        // Background / battery — the MIUI & Samsung killers
        val power = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val ignoring = power.isIgnoringBatteryOptimizations(ctx.packageName)
        add("Battery optimisation", if (ignoring) Status.OK else Status.WARN,
            if (ignoring) "app is exempt" else "app is optimised — Android may stop advertising/scanning in background. Use 'Fix: battery'")
        if (power.isPowerSaveMode) add("Battery saver", Status.WARN, "ON — scans get throttled; turn it off for the demo")
        if (sdk >= Build.VERSION_CODES.P) {
            val restricted = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isBackgroundRestricted
            add("Background restriction", if (restricted) Status.FAIL else Status.OK,
                if (restricted) "RESTRICTED by user/OEM — background BLE will be killed. App info → Battery → Unrestricted" else "not restricted")
        }
        if (isXiaomi) {
            add("MIUI/HyperOS", Status.WARN,
                "Needs 3 manual settings: (1) Autostart ON, (2) Battery saver → No restrictions, (3) lock the app in Recents (swipe down on its card). 'Fix: OEM' opens (1).")
        }
        if (isSamsung) {
            add("One UI", Status.WARN,
                "Settings → Battery → Background usage limits → Never sleeping apps → add this app. Also App info → Battery → Unrestricted. 'Fix: OEM' tries to open it.")
        }

        // Network / backend
        val hasNet = BackendClient.hasNetwork(ctx)
        add("Network", if (hasNet) Status.OK else Status.INFO, if (hasNet) BackendClient.networkLabel(ctx) else "offline (expected on Phone A)")
        if (Guardian.isInitialized) {
            val base = Guardian.config.backendBaseUrl
            if (base.isBlank()) add("Backend", Status.WARN, "URL not set")
            else if (hasNet) {
                val r = BackendClient.get("$base/health")
                add("Backend $base", if (r.ok) Status.OK else Status.FAIL, if (r.ok) "reachable" else "NOT reachable: ${r.describe()} — same Wi-Fi? firewall on port 5000?")
            }
            val contacts = Guardian.config.trustedContacts
            add("Trusted contacts", if (contacts.isEmpty()) Status.WARN else Status.OK,
                if (contacts.isEmpty()) "none — SMS fallback has nobody to text" else "${contacts.size} configured")
        }
        return out
    }

    /** Runs [run] (off the main thread — it does a network call) and writes the results to AppLog. */
    fun runAndLog(ctx: Context) {
        Thread {
            AppLog.i(TAG, "──── diagnostics ────")
            run(ctx).forEach { c ->
                val level = when (c.status) {
                    Status.OK -> AppLog.Level.OK
                    Status.WARN -> AppLog.Level.WARN
                    Status.FAIL -> AppLog.Level.ERROR
                    Status.INFO -> AppLog.Level.INFO
                }
                AppLog.add(level, TAG, "${c.status.icon} ${c.name}: ${c.detail}")
            }
            AppLog.i(TAG, "──── end diagnostics ────")
        }.start()
    }

    // ───────────────────────── fix-it intents ─────────────────────────

    fun openAppSettings(a: Activity) = tryStart(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}")))

    fun openLocationSettings(a: Activity) = tryStart(a, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))

    @SuppressLint("BatteryLife", "MissingPermission")
    fun requestBatteryExemption(a: Activity) {
        val ok = tryStart(a, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}")))
        if (!ok) tryStart(a, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    @SuppressLint("MissingPermission")
    fun requestEnableBluetooth(a: Activity) {
        if (!Permissions.granted(a, android.Manifest.permission.BLUETOOTH_CONNECT) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            AppLog.w(TAG, "BLUETOOTH_CONNECT needed to show the enable-Bluetooth prompt — turn BT on from quick settings instead")
            return
        }
        tryStart(a, Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    /** MIUI autostart / Samsung sleeping-apps screens. Component names differ across ROM versions, so try several. */
    fun openOemBackgroundSettings(a: Activity) {
        val candidates = when {
            isXiaomi -> listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.miui.securitycenter", "com.miui.powercenter.PowerSettings")
            )
            isSamsung -> listOf(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
                ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
            )
            else -> emptyList()
        }
        for (c in candidates) {
            if (tryStart(a, Intent().setComponent(c), quiet = true)) {
                AppLog.i(TAG, "Opened ${c.className.substringAfterLast('.')} — find 'Guardian Relay Demo' and allow it")
                return
            }
        }
        AppLog.w(TAG, "No OEM-specific screen found on this ROM — opening App info instead (Battery → Unrestricted)")
        openAppSettings(a)
    }

    private fun tryStart(a: Activity, i: Intent, quiet: Boolean = false): Boolean = try {
        a.startActivity(i); true
    } catch (t: Throwable) {
        if (!quiet) AppLog.w(TAG, "Couldn't open ${i.action ?: i.component}: ${t.javaClass.simpleName}")
        false
    }
}
