package com.guardianmesh.relay.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.diag.Permissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Framework LocationManager only (no Google Play services dependency), listening
 * to fused (Android 12+), GPS and network providers at once and keeping the best fix.
 * Works on GMS-less phones too; network provider gives a fast rough fix indoors
 * while GPS warms up.
 */
class LocationProvider(context: Context) {

    companion object { private const val TAG = "LOC" }

    private val ctx = context.applicationContext
    private val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val listeners = mutableListOf<LocationListener>()
    @Volatile var best: GeoFix? = null
        private set

    fun isLocationEnabled(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    private fun providers(): List<String> {
        val all = lm.getProviders(true)
        val order = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        return order.filter { it in all }
    }

    /** Freshest acceptable cached fix, no waiting. */
    @SuppressLint("MissingPermission")
    fun lastKnown(maxAgeMs: Long = 2 * 60_000L): GeoFix? {
        if (!Permissions.hasAnyLocation(ctx)) return null
        val now = System.currentTimeMillis()
        val candidates = (providers() + LocationManager.PASSIVE_PROVIDER).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (_: Throwable) { null }
        }.map { GeoFix.fromAndroid(it) }.filter { now - it.timeMs <= maxAgeMs }
        return candidates.fold(null as GeoFix?) { acc, f -> if (acc == null || isBetter(f, acc)) f else acc }
    }

    /**
     * Blocks up to [timeoutMs] for a fresh fix from any provider. MUST NOT be called
     * on the main thread (listener callbacks are delivered there).
     */
    @SuppressLint("MissingPermission")
    fun currentFix(timeoutMs: Long): GeoFix? {
        check(Looper.myLooper() != Looper.getMainLooper()) { "currentFix() blocks — call it off the main thread" }
        if (!Permissions.hasAnyLocation(ctx) || !isLocationEnabled()) return null
        val got = AtomicReference<GeoFix?>(null)
        val latch = CountDownLatch(1)
        val tmp = mutableListOf<LocationListener>()
        for (p in providers()) {
            val l = listener { fix -> if (got.compareAndSet(null, fix)) latch.countDown() }
            try { lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()); tmp += l } catch (_: Throwable) {}
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        tmp.forEach { runCatching { lm.removeUpdates(it) } }
        return got.get()
    }

    /** Continuous updates from all providers; [onFix] gets each fix that improves on [best] (main thread). */
    @SuppressLint("MissingPermission")
    fun start(intervalMs: Long, onFix: (GeoFix) -> Unit) {
        stop()
        if (!Permissions.hasAnyLocation(ctx)) { AppLog.e(TAG, "No location permission — live tracking disabled"); return }
        if (!isLocationEnabled()) AppLog.w(TAG, "Location toggle is OFF — no fixes until the user turns it on")
        if (!Permissions.hasFineLocation(ctx)) AppLog.w(TAG, "Only APPROXIMATE location granted — fixes will be ~1-3 km off")
        best = lastKnown()
        for (p in providers()) {
            val l = listener { fix ->
                val cur = best
                if (cur == null || isBetter(fix, cur)) { best = fix; onFix(fix) }
            }
            try {
                lm.requestLocationUpdates(p, intervalMs, 0f, l, Looper.getMainLooper())
                listeners += l
                AppLog.d(TAG, "listening on provider '$p' every ${intervalMs / 1000}s")
            } catch (t: Throwable) {
                AppLog.w(TAG, "provider '$p' refused: ${t.message}")
            }
        }
        if (listeners.isEmpty()) AppLog.e(TAG, "No location provider available")
    }

    fun stop() {
        listeners.forEach { runCatching { lm.removeUpdates(it) } }
        listeners.clear()
    }

    /**
     * Implements every LocationListener method explicitly: on Android ≤ 10 the
     * interface has no default methods, and a missing onStatusChanged override
     * crashes with AbstractMethodError the first time a provider changes state.
     */
    private fun listener(cb: (GeoFix) -> Unit) = object : LocationListener {
        override fun onLocationChanged(location: Location) = cb(GeoFix.fromAndroid(location))
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    /** Newer wins unless it's much less accurate than a still-recent fix. */
    private fun isBetter(new: GeoFix, cur: GeoFix): Boolean {
        val dt = new.timeMs - cur.timeMs
        if (dt > 30_000) return true
        if (dt < -30_000) return false
        val na = new.accuracyM ?: 9999f
        val ca = cur.accuracyM ?: 9999f
        return when {
            na <= ca -> true
            dt > 0 && na <= ca * 2 -> true
            else -> false
        }
    }
}
