package com.guardianmesh.relay.net.api

/**
 * The single source of truth for where the backend lives.
 *
 * Change [BASE_URL] and the whole UI layer follows. Retrofit insists on a trailing
 * slash, so keep it.
 *
 * Why there is also a runtime override: the demo backend runs on a laptop whose LAN
 * IP changes at every venue, and the two-phone test needs Phone B pointed at it
 * without a rebuild. So Settings can override this value, it is stored in Room, and
 * [ApiProvider] applies it per request. With nothing saved, this constant is what
 * gets used.
 *
 *  - Android emulator → the host machine's localhost is 10.0.2.2
 *  - Real phone on the same Wi-Fi → "http://192.168.1.5:5000/" (your laptop's IP)
 *  - Deployed → "https://api.yourdomain.com/", and drop usesCleartextTraffic
 *    from AndroidManifest.xml
 */
object ApiConfig {

    const val BASE_URL = "http://10.0.2.2:5000/"

    /** Nothing in the app blocks longer than this; the SOS core has its own, shorter timeouts. */
    const val CONNECT_TIMEOUT_S = 5L
    const val READ_TIMEOUT_S = 8L

    /** Default radius for the Safe Exit lookup, in metres. */
    const val SAFE_PLACES_RADIUS_M = 2_000

    /**
     * Accepts what a person actually types ("192.168.1.5:5000", "http://host/relay")
     * and returns something Retrofit can use. Mirrors
     * `GuardianConfig.normalizeBaseUrl`, plus the trailing slash Retrofit requires.
     */
    fun normalize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty()) return BASE_URL
        if (s.endsWith("/")) s = s.trimEnd('/')
        if (s.endsWith("/relay")) s = s.removeSuffix("/relay")
        if (!s.startsWith("http://") && !s.startsWith("https://")) s = "http://$s"
        return "$s/"
    }
}
