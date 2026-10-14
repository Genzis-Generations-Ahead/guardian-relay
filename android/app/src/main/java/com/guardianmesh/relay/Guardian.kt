package com.guardianmesh.relay

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import com.guardianmesh.relay.ble.BleAdvertiserManager
import com.guardianmesh.relay.ble.RelaySignal
import com.guardianmesh.relay.diag.AppLog
import java.lang.ref.WeakReference

/**
 * Single entry point that wires the modules together. Call once:
 *
 *     class MyApp : Application() { override fun onCreate() { super.onCreate(); Guardian.init(this) } }
 *
 * Then from any screen:
 *     SosDispatcher.send(SosEvent.create(fix = myFix))
 *     SafeExit.start(location)
 *     RelayService.start(context)          // make this phone a relay/mesh node
 */
object Guardian {

    @Volatile private var appCtx: Context? = null

    val context: Context
        get() = appCtx ?: error("Guardian.init(context) was not called — add it to your Application.onCreate()")

    val isInitialized get() = appCtx != null

    lateinit var config: GuardianConfig
        private set

    /** Shared by the SOS sender and the mesh relay so they respect one advert-slot budget. */
    val advertiser: BleAdvertiserManager by lazy { BleAdvertiserManager(context) }

    val deviceShortId: Int by lazy {
        RelaySignal.localDeviceShortId(Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID))
    }

    private var topActivity: WeakReference<Activity>? = null

    /** The resumed activity, if any — used to launch Maps without background-start restrictions. */
    val currentActivity: Activity? get() = topActivity?.get()

    @Synchronized
    fun init(context: Context) {
        if (appCtx != null) return
        val app = context.applicationContext
        appCtx = app
        config = GuardianConfig(app)
        (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a: Activity) { topActivity = WeakReference(a) }
            override fun onActivityPaused(a: Activity) { if (topActivity?.get() === a) topActivity = null }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        AppLog.i("CORE", "Guardian initialised — device id ${"%04x".format(deviceShortId)}")
    }

    /** Services can be restarted by the system before any Activity runs. */
    fun ensureInit(context: Context) { if (appCtx == null) init(context) }
}

/** Backed by SharedPreferences so the relay/location services see the same values after a restart. */
class GuardianConfig(context: Context) {
    private val prefs = context.getSharedPreferences("guardian_config", Context.MODE_PRIVATE)

    /** e.g. http://192.168.1.5:5000 — a trailing "/relay" (old app format) is stripped. */
    var backendBaseUrl: String
        get() = prefs.getString("backend", "") ?: ""
        set(v) { prefs.edit().putString("backend", normalizeBaseUrl(v)).apply() }

    /** E.164-ish numbers, e.g. +919876543210. */
    var trustedContacts: List<String>
        get() = (prefs.getString("contacts", "") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) { prefs.edit().putString("contacts", v.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(",")).apply() }

    /** Name shown in the SMS ("SOS from <name>"). */
    var senderName: String
        get() = prefs.getString("name", "") ?: ""
        set(v) { prefs.edit().putString("name", v.trim()).apply() }

    /** Also broadcast over BLE even when HTTP/SMS succeeded. Costs nothing but battery; off = strict chain. */
    var bleAlways: Boolean
        get() = prefs.getBoolean("bleAlways", false)
        set(v) { prefs.edit().putBoolean("bleAlways", v).apply() }

    /** While SOS is active and the internet is down, re-send an SMS with the newest location at most this often. 0 = never. */
    var smsUpdateIntervalMs: Long
        get() = prefs.getLong("smsEvery", 5 * 60_000L)
        set(v) { prefs.edit().putLong("smsEvery", v).apply() }

    companion object {
        fun normalizeBaseUrl(raw: String): String {
            var s = raw.trim().trimEnd('/')
            if (s.endsWith("/relay")) s = s.removeSuffix("/relay")
            if (s.isNotEmpty() && !s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
            return s
        }
    }
}
