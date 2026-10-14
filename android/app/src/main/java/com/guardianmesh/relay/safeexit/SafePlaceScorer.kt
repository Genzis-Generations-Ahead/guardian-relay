package com.guardianmesh.relay.safeexit

import com.guardianmesh.relay.location.GeoFix
import java.util.Locale
import kotlin.math.exp

/** A candidate refuge returned by GET /safe-places/nearby. */
data class SafePlace(
    val id: String,
    val name: String,
    val type: String,          // police, hospital, pharmacy, metro, cafe, …
    val lat: Double,
    val lng: Double,
    val openNow: Boolean?,     // null = backend doesn't know the hours
    val hoursText: String? = null
)

/**
 * Picks where to walk. Pure function of (places, origin) so it's unit-tested.
 *
 *   score = 0.6 · distanceScore + 0.4 · openScore
 *   distanceScore = e^(−d / 750 m)        100 m → 0.88, 500 m → 0.51, 1 km → 0.26, 2 km → 0.07
 *   openScore     = open 1.0 · unknown 0.4 · closed 0
 *
 * Hard rule on top: a place known to be CLOSED never beats one that is open or
 * unknown — a locked door is not a refuge, however close. Closed places only come
 * back (ranked by distance) if nothing else exists.
 *
 * Distance is straight-line (haversine), computed here rather than trusted from the
 * server; walking distance is typically 1.2–1.4× that, which doesn't change the ranking much.
 */
object SafePlaceScorer {
    const val DISTANCE_SCALE_M = 750.0
    const val W_DISTANCE = 0.6
    const val W_OPEN = 0.4
    const val OPEN_UNKNOWN_SCORE = 0.4

    data class Scored(val place: SafePlace, val distanceM: Double, val score: Double) {
        fun describe(): String = String.format(
            Locale.US, "%s (%s) %.0f m, %s, score %.2f",
            place.name, place.type, distanceM,
            when (place.openNow) { true -> "open"; false -> "CLOSED"; null -> "hours unknown" }, score
        )
    }

    fun score(distanceM: Double, openNow: Boolean?): Double {
        val d = exp(-distanceM.coerceAtLeast(0.0) / DISTANCE_SCALE_M)
        val o = when (openNow) { true -> 1.0; null -> OPEN_UNKNOWN_SCORE; false -> 0.0 }
        return W_DISTANCE * d + W_OPEN * o
    }

    fun rank(places: List<SafePlace>, origin: GeoFix): List<Scored> =
        places.map { p ->
            val d = GeoFix.haversineMeters(origin.lat, origin.lng, p.lat, p.lng)
            Scored(p, d, score(d, p.openNow))
        }.sortedWith(compareBy<Scored> { it.place.openNow == false }.thenByDescending { it.score }.thenBy { it.distanceM })

    fun pickBest(places: List<SafePlace>, origin: GeoFix): Scored? = rank(places, origin).firstOrNull()
}
