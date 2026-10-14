package com.guardianmesh.relay.location

import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A location fix, without depending on android.location.Location so the SOS
 * payload, scoring and fallback logic stay unit-testable on a plain JVM.
 * Convert from Android's type with [fromAndroid].
 */
data class GeoFix(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val timeMs: Long = System.currentTimeMillis(),
    val provider: String? = null
) {
    init {
        require(lat in -90.0..90.0) { "lat out of range: $lat" }
        require(lng in -180.0..180.0) { "lng out of range: $lng" }
    }

    fun ageMs(nowMs: Long = System.currentTimeMillis()): Long = nowMs - timeMs

    /** 5 decimals ≈ 1.1 m — plenty, and keeps SMS short. */
    fun latLngString(): String = String.format(Locale.US, "%.5f,%.5f", lat, lng)

    fun mapsLink(): String = "https://maps.google.com/?q=${latLngString()}"

    fun distanceTo(other: GeoFix): Double = haversineMeters(lat, lng, other.lat, other.lng)

    companion object {
        fun fromAndroid(l: android.location.Location): GeoFix = GeoFix(
            lat = l.latitude,
            lng = l.longitude,
            accuracyM = if (l.hasAccuracy()) l.accuracy else null,
            timeMs = l.time,
            provider = l.provider
        )

        fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
            return 2 * r * asin(sqrt(a.coerceIn(0.0, 1.0)))
        }
    }
}
