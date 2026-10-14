package com.guardianmesh.relay.net.api

import com.google.gson.annotations.SerializedName
import com.guardianmesh.relay.safeexit.SafePlace

/**
 * Gson models for the Flask backend. The field names here were taken from live
 * responses, not guessed: the SOS endpoints use camelCase and the safe-places
 * endpoint uses snake_case, which is why only the latter needs @SerializedName.
 */

// ───────────────────────────── /sos, /events ─────────────────────────────

data class LocationPointDto(
    val lat: Double,
    val lng: Double,
    val accuracyM: Float? = null,
    val fixEpochMs: Long = 0,
    val provider: String? = null
)

data class DeliveryDto(
    val via: String? = null,
    val at: String? = null
)

/** What `GET /events` and `GET /sos/<id>` return. */
data class SosEventDto(
    val eventId: String,
    val status: String? = null,              // "active" | "resolved"
    val severity: Int? = null,               // 0 low, 1 medium, 2 high
    val signalType: String? = null,          // "SOS" | "FOLLOWING_ALERT"
    val note: String? = null,
    val sourceDeviceShortId: Int? = null,
    val originalEpochSeconds: Long? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val trailPoints: Int = 0,
    val mapsLink: String? = null,
    val lastFixAgeS: Long? = null,
    val lastLocation: LocationPointDto? = null,
    val vias: List<String>? = null,
    val deliveries: List<DeliveryDto>? = null,
    /** Only present on GET /sos/<id>. */
    val trail: List<LocationPointDto>? = null
) {
    val isActive: Boolean get() = status != "resolved"
    val relayed: Boolean get() = vias?.any { it == "ble-relay" } == true
}

/** `{"status":"ok","eventId":"…"}` from the POST endpoints. */
data class AckDto(
    val status: String? = null,
    val eventId: String? = null,
    val added: Int? = null,
    val trailPoints: Int? = null,
    val error: String? = null
)

data class HealthDto(
    val status: String? = null,
    val events: Int = 0,
    val time: String? = null
)

// ───────────────────────────── /safe-places ─────────────────────────────

data class SafePlaceDto(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    @SerializedName("distance_m") val distanceM: Double? = null,
    @SerializedName("open_now") val openNow: Boolean? = null,
    @SerializedName("hours_text") val hoursText: String? = null,
    val source: String? = null
) {
    /** Hand off to the existing, unit-tested SafePlaceScorer rather than re-ranking here. */
    fun toSafePlace(): SafePlace = SafePlace(
        id = id ?: "place",
        name = name ?: "Safe place",
        type = type ?: "unknown",
        lat = lat,
        lng = lng,
        openNow = openNow,
        hoursText = hoursText
    )

    /** Backend-generated demo data is labelled "(demo)"; the UI says so out loud. */
    val isSynthetic: Boolean get() = source == "synthetic"
}

data class SafePlacesResponse(
    val count: Int = 0,
    val synthetic: Boolean = false,
    @SerializedName("radius_m") val radiusM: Int = 0,
    @SerializedName("evaluated_at") val evaluatedAt: String? = null,
    val places: List<SafePlaceDto> = emptyList()
)
