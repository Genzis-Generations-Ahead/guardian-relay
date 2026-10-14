package com.guardianmesh.relay.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.location.GeoFix
import com.guardianmesh.relay.net.BackendClient
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * What a phone does with an SOS packet it hears over BLE:
 *
 *   has internet  → POST /relay to the backend (with the sender's compressed location)
 *   no internet   → re-broadcast it with TTL−1 so the next phone can carry it on (mesh hop)
 *   TTL == 0      → nothing more it can do; logged
 *
 * De-dupes on (eventId, seq): the same advert arrives ~10×/s while in range, and
 * rebroadcast copies bounce back. A newer seq = sender has a fresher location → forward again.
 */
class MeshRelay(
    context: Context,
    private val advertiser: BleAdvertiserManager,
    private val baseUrl: () -> String,
    private val ownShortId: Int
) {
    companion object {
        private const val TAG = "MESH"
        private const val RETRY_FAILED_FORWARD_MS = 10_000L
        private const val REBROADCAST_FOR_MS = 2 * 60_000L
        private const val MAX_EVENT_AGE_S = 6 * 3600L
    }

    data class Counters(val heard: Int, val forwarded: Int, val rebroadcasting: Int)

    private class Seen(var lastSeq: Int, var lastAttemptAt: Long, var forwardedOk: Boolean, var rebroadcasting: Boolean)

    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "mesh-relay").apply { isDaemon = true } }
    private val seen = object : LinkedHashMap<String, Seen>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Seen>?) = size > 256
    }
    private val stopRebroadcast = HashMap<String, Runnable>()

    fun counters(): Counters = synchronized(seen) {
        Counters(seen.size, seen.values.count { it.forwardedOk }, seen.values.count { it.rebroadcasting })
    }

    /** Called for every decoded packet (main thread, many times per second). */
    fun onSignal(signal: RelaySignal, rssi: Int) {
        if (signal.deviceShortId == ownShortId) return // our own SOS echoing back via another relay
        val nowS = System.currentTimeMillis() / 1000
        if (signal.version >= 2 && nowS - signal.epochSeconds > MAX_EVENT_AGE_S) return

        val now = System.currentTimeMillis()
        val id = signal.eventId
        val decision: String = synchronized(seen) {
            val s = seen[id]
            when {
                s == null -> { seen[id] = Seen(signal.seq, now, false, false); "NEW" }
                RelaySignal.isNewerSeq(signal.seq, s.lastSeq) -> { s.lastSeq = signal.seq; s.lastAttemptAt = now; "UPDATE" }
                !s.forwardedOk && now - s.lastAttemptAt >= RETRY_FAILED_FORWARD_MS -> { s.lastAttemptAt = now; "RETRY" }
                else -> null
            }
        } ?: return

        when (decision) {
            "NEW" -> AppLog.ok(TAG, "Heard SOS over BLE (RSSI $rssi, hops so far ${RelaySignal.DEFAULT_TTL - signal.ttl}): ${signal.describe()}")
            "UPDATE" -> AppLog.i(TAG, "Updated location from ${signal.eventId} (seq ${signal.seq})")
        }
        worker.execute { forwardOrRebroadcast(signal, rssi, decision) }
    }

    private fun forwardOrRebroadcast(signal: RelaySignal, rssi: Int, decision: String) {
        val base = baseUrl()
        val id = signal.eventId
        if (base.isNotBlank() && BackendClient.hasNetwork(ctx)) {
            val r = BackendClient.postJson("$base/relay", toJson(signal, rssi))
            if (r.ok) {
                AppLog.ok(TAG, "Forwarded $id to backend (${r.describe()})")
                markForwarded(id)
                return
            }
            AppLog.w(TAG, "Forward of $id failed: ${r.describe()}")
        }
        // No (working) internet → act as a mesh hop.
        if (signal.ttl > 0) {
            val key = "relay:$id"
            // A periodic RETRY with the same packet just re-checks internet; don't restart a running advert.
            if (decision == "RETRY" && advertiser.isActive(key)) return
            advertiser.start(key, signal.withTtl(signal.ttl - 1)) { ok, msg ->
                if (ok) AppLog.i(TAG, "No internet → re-broadcasting $id with TTL ${signal.ttl - 1}")
                else AppLog.e(TAG, "Re-broadcast of $id failed: $msg")
            }
            synchronized(seen) { seen[id]?.rebroadcasting = true }
            main.post {
                stopRebroadcast.remove(id)?.let { main.removeCallbacks(it) }
                val r = Runnable {
                    advertiser.stop(key)
                    synchronized(seen) { seen[id]?.rebroadcasting = false }
                    stopRebroadcast.remove(id)
                }
                stopRebroadcast[id] = r
                main.postDelayed(r, REBROADCAST_FOR_MS)
            }
        } else {
            AppLog.w(TAG, "No internet and TTL exhausted for $id — can't carry it further")
        }
    }

    private fun markForwarded(id: String) {
        val wasRebroadcasting = synchronized(seen) {
            seen[id]?.let { s -> s.forwardedOk = true; s.rebroadcasting.also { s.rebroadcasting = false } } ?: false
        }
        if (wasRebroadcasting) {
            advertiser.stop("relay:$id")
            main.post { stopRebroadcast.remove(id)?.let { main.removeCallbacks(it) } }
        }
    }

    fun shutdown() {
        main.post {
            stopRebroadcast.keys.toList().forEach { advertiser.stop("relay:$it") }
            stopRebroadcast.values.forEach { main.removeCallbacks(it) }
            stopRebroadcast.clear()
        }
    }

    private fun toJson(s: RelaySignal, rssi: Int) = JSONObject().apply {
        put("eventId", s.eventId)
        put("signalType", s.typeName)
        put("severity", s.severity)
        put("sourceDeviceShortId", s.deviceShortId)
        put("originalEpochSeconds", s.epochSeconds)
        put("relayRssi", rssi)
        put("relayedBy", "%04x".format(ownShortId))
        put("hops", (RelaySignal.DEFAULT_TTL - s.ttl).coerceAtLeast(0) + 1)
        put("seq", s.seq)
        put("payloadVersion", s.version)
        put("via", "ble-relay")
        s.fix?.let { f: GeoFix ->
            put("lat", f.lat); put("lng", f.lng)
            f.accuracyM?.let { put("accuracyM", it.toDouble()) }
            put("fixEpochMs", f.timeMs)
        }
    }
}
