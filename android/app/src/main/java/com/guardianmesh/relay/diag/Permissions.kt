package com.guardianmesh.relay.diag

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Which runtime permissions each feature needs, per Android version.
 *
 * Android 12+ (API 31) split Bluetooth out of location:
 *   BLUETOOTH_SCAN       — scanning. Declared with neverForLocation, so scanning does
 *                          NOT need location permission or the Location toggle (in theory —
 *                          several Xiaomi builds still return nothing with Location off).
 *   BLUETOOTH_ADVERTISE  — advertising.
 *   BLUETOOTH_CONNECT    — adapter name/enable prompt; not needed for connectionless
 *                          adverts, but ACTION_REQUEST_ENABLE requires it.
 * Android ≤ 11: scanning needs ACCESS_FINE_LOCATION *and* the Location toggle ON.
 *
 * Location for the SOS payload/live tracking needs FINE (+COARSE, which Android 12
 * insists is requested together; the user may then pick "Approximate" only).
 */
object Permissions {

    private val sdk get() = Build.VERSION.SDK_INT

    fun bluetooth(): List<String> =
        if (sdk >= Build.VERSION_CODES.S) listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT
        ) else listOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun location(): List<String> =
        listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun sms(): List<String> = listOf(Manifest.permission.SEND_SMS)

    fun notifications(): List<String> =
        if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    /** Everything the demo app asks for on first launch. */
    fun all(): List<String> = (bluetooth() + location() + sms() + notifications()).distinct()

    fun granted(ctx: Context, p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun missing(ctx: Context, perms: List<String> = all()) = perms.filterNot { granted(ctx, it) }

    fun hasAdvertise(ctx: Context) =
        if (sdk >= Build.VERSION_CODES.S) granted(ctx, Manifest.permission.BLUETOOTH_ADVERTISE) else true

    fun hasScan(ctx: Context) =
        if (sdk >= Build.VERSION_CODES.S) granted(ctx, Manifest.permission.BLUETOOTH_SCAN)
        else granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasAnyLocation(ctx: Context) =
        granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION) || granted(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)

    fun hasFineLocation(ctx: Context) = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)

    fun hasSms(ctx: Context) = granted(ctx, Manifest.permission.SEND_SMS)

    /**
     * After a denial: if Android won't show the rationale any more, the dialog is
     * suppressed for good ("Don't ask again", or two denials on Android 11+) and the
     * only way back is the app's Settings page.
     */
    fun permanentlyDenied(activity: Activity, perms: List<String>): List<String> =
        perms.filter { !granted(activity, it) && !activity.shouldShowRequestPermissionRationale(it) }

    fun shortName(p: String) = p.substringAfterLast('.')
}
