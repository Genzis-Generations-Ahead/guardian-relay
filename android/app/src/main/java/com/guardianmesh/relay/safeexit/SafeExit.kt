package com.guardianmesh.relay.safeexit

import android.location.Location
import android.os.Handler
import android.os.Looper
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.location.GeoFix
import com.guardianmesh.relay.net.BackendClient
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Safe Exit Planner. From the UI:
 *
 *     SafeExit.start(location) { result -> … }     // android.location.Location
 *     SafeExit.start(GeoFix(lat, lng))
 *
 * 1. GET {backend}/safe-places/nearby?lat=&lng=&radius=
 * 2. rank with SafePlaceScorer (distance + open-now; closed places never win)
 * 3. open Google Maps walking navigation to the winner
 * Offline / backend down / nothing nearby → opens Maps searching "police station" around her,
 * so the button always does *something* useful.
 */
object SafeExit {
    private const val TAG = "SAFE-EXIT"
    var searchRadiusM = 2_000

    sealed class Result {
        data class Navigating(val best: SafePlaceScorer.Scored, val alternatives: List<SafePlaceScorer.Scored>) : Result()
        data class FallbackSearch(val reason: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "safe-exit").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    fun start(location: Location, onResult: ((Result) -> Unit)? = null) = start(GeoFix.fromAndroid(location), onResult)

    fun start(origin: GeoFix, onResult: ((Result) -> Unit)? = null) {
        val ctx = Guardian.context
        AppLog.i(TAG, "Planning safe exit from ${origin.latLngString()}")
        worker.execute {
            val base = Guardian.config.backendBaseUrl
            val reason: String = when {
                base.isBlank() -> "no backend URL"
                !BackendClient.hasNetwork(ctx) -> "offline"
                else -> {
                    val url = String.format(Locale.US, "%s/safe-places/nearby?lat=%.6f&lng=%.6f&radius=%d", base, origin.lat, origin.lng, searchRadiusM)
                    val r = BackendClient.get(url)
                    val places = if (r.ok) r.json()?.let { SafePlacesParser.parse(it) } else null
                    when {
                        !r.ok -> "backend error: ${r.describe()}"
                        places == null -> "unparseable response"
                        places.isEmpty() -> "no safe places within ${searchRadiusM} m"
                        else -> {
                            val ranked = SafePlaceScorer.rank(places, origin)
                            val best = ranked.first()
                            AppLog.ok(TAG, "Best: ${best.describe()}")
                            ranked.drop(1).take(3).forEach { AppLog.d(TAG, "  alt: ${it.describe()}") }
                            if (best.place.openNow == false) AppLog.w(TAG, "Every nearby place is closed — routing to the nearest anyway")
                            main.post {
                                val opened = MapsLauncher.walkTo(best.place.lat, best.place.lng)
                                val res = if (opened) Result.Navigating(best, ranked.drop(1).take(3))
                                else Result.Failed("No app can open map directions")
                                onResult?.invoke(res)
                            }
                            return@execute
                        }
                    }
                }
            }
            AppLog.w(TAG, "Falling back to Maps search for police stations ($reason)")
            main.post {
                val opened = MapsLauncher.searchNearby(origin.lat, origin.lng)
                onResult?.invoke(if (opened) Result.FallbackSearch(reason) else Result.Failed("$reason; and no Maps app/browser"))
            }
        }
    }
}
