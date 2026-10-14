package com.guardianmesh.relay.net.api

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The backend as the UI layer sees it.
 *
 * Deliberately read-mostly. Raising an SOS still goes through `SosDispatcher`, because
 * that is the path with the HTTP → SMS → BLE fallback chain, the offline buffer and the
 * unit tests. Retrofit here is for the screens: is the backend up, what does the server
 * know about my active event, what is nearby.
 *
 * [postSos] exists for one narrow case — a UI-initiated retry when the chain already
 * gave up — and is not on the normal SOS path.
 */
interface GuardianApi {

    @GET("health")
    suspend fun health(): HealthDto

    @GET("events")
    suspend fun events(): List<SosEventDto>

    @GET("sos/{eventId}")
    suspend fun event(@Path("eventId") eventId: String): SosEventDto

    @POST("sos/{eventId}/resolve")
    suspend fun resolve(@Path("eventId") eventId: String): AckDto

    @GET("safe-places/nearby")
    suspend fun safePlacesNearby(
        @Query("lat") lat: Double,
        @Query("lng") lng: Double,
        @Query("radius") radiusM: Int = ApiConfig.SAFE_PLACES_RADIUS_M,
        @Query("limit") limit: Int = 20
    ): SafePlacesResponse

    @POST("sos")
    suspend fun postSos(@Body event: Map<String, @JvmSuppressWildcards Any?>): AckDto
}
