package com.guardianmesh.relay.safeexit

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.diag.AppLog
import java.util.Locale

/** Opens Google Maps (or anything that handles the URL) with walking directions. Main thread only. */
object MapsLauncher {
    private const val TAG = "MAPS"
    private const val MAPS_PKG = "com.google.android.apps.maps"

    /** Turn-by-turn walking navigation straight away; falls back to the universal Maps URL (browser if no Maps app). */
    fun walkTo(lat: Double, lng: Double): Boolean {
        val ll = String.format(Locale.US, "%.6f,%.6f", lat, lng)
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$ll&mode=w")).setPackage(MAPS_PKG)
        if (launch(nav)) return true
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$ll&travelmode=walking"))
        return launch(web)
    }

    /** Offline / no-backend fallback: let Maps itself search around her (works with Maps' offline areas). */
    fun searchNearby(lat: Double, lng: Double, query: String = "police station"): Boolean {
        val q = Uri.encode(query)
        val ll = String.format(Locale.US, "%.6f,%.6f", lat, lng)
        if (launch(Intent(Intent.ACTION_VIEW, Uri.parse("geo:$ll?q=$q")).setPackage(MAPS_PKG))) return true
        return launch(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/$q/@$ll,16z")))
    }

    private fun launch(i: Intent): Boolean {
        val activity = Guardian.currentActivity
        val ctx: Context = activity ?: Guardian.context
        if (activity == null) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            ctx.startActivity(i)
            AppLog.ok(TAG, "Opened ${i.data}")
            true
        } catch (t: Throwable) {
            AppLog.d(TAG, "Couldn't open ${i.data}: ${t.javaClass.simpleName}")
            false
        }
    }
}
