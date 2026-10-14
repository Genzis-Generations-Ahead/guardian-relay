package com.guardianmesh.relay.sos

import com.guardianmesh.relay.FollowingClassifier
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.ble.RelaySignal
import com.guardianmesh.relay.location.GeoFix
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One SOS incident. Identity = (deviceShortId, epochSeconds) → [eventId], the same
 * id the BLE packet and backend use, so a direct HTTP post, a relayed BLE copy and
 * later location pushes all land on one record on the server.
 */
data class SosEvent(
    val deviceShortId: Int,
    val epochSeconds: Long,
    val type: Type = Type.SOS,
    val severity: Severity = Severity.HIGH,
    val fix: GeoFix? = null,
    val note: String? = null,
    val source: String = "manual"
) {
    enum class Type(val wire: Int) { SOS(RelaySignal.TYPE_SOS), FOLLOWING_ALERT(RelaySignal.TYPE_FOLLOWING_ALERT) }
    enum class Severity(val wire: Int) { LOW(0), MEDIUM(1), HIGH(2) }

    val eventId: String get() = RelaySignal.eventIdOf(deviceShortId, epochSeconds)

    fun withFix(newFix: GeoFix?) = copy(fix = newFix)

    fun toSignal(seq: Int = 0, ttl: Int = RelaySignal.DEFAULT_TTL) =
        RelaySignal(type.wire, severity.wire, deviceShortId, epochSeconds, ttl, seq, fix)

    /** ≤160 GSM-7 chars so it's a single SMS part with a fix; no emoji (would force UCS-2 → 70 chars). */
    fun smsText(senderName: String, update: Boolean = false): String {
        val who = senderName.ifBlank { "a Guardian user" }
        val head = if (update) "SOS UPDATE from $who" else "SOS from $who - may need help."
        val f = fix ?: return "$head Location unavailable. Ref $eventId"
        val time = SimpleDateFormat("HH:mm", Locale.US).format(Date(f.timeMs))
        val acc = f.accuracyM?.let { " +-${it.toInt()}m" } ?: ""
        return "$head ${f.mapsLink()}$acc at $time. Ref $eventId"
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("eventId", eventId)
        put("signalType", type.name)
        put("severity", severity.wire)
        put("sourceDeviceShortId", deviceShortId)
        put("originalEpochSeconds", epochSeconds)
        put("source", source)
        note?.let { put("note", it) }
        fix?.let { putFix(this, it) }
    }

    companion object {
        /** Builds an event for THIS phone. Requires Guardian.init(). */
        fun create(
            type: Type = Type.SOS,
            severity: Severity = Severity.HIGH,
            fix: GeoFix? = null,
            note: String? = null,
            source: String = "manual"
        ) = SosEvent(Guardian.deviceShortId, System.currentTimeMillis() / 1000, type, severity, fix, note, source)

        fun create(location: android.location.Location?, severity: Severity = Severity.HIGH, note: String? = null) =
            create(severity = severity, fix = location?.let { GeoFix.fromAndroid(it) }, note = note)

        /** Glue for FollowingClassifier: HIGH → SOS event. MEDIUM/LOW should go to SafeExit instead. */
        fun fromClassification(result: FollowingClassifier.ClassificationResult, fix: GeoFix?) = create(
            type = if (result.route == FollowingClassifier.Route.SOS) Type.SOS else Type.FOLLOWING_ALERT,
            severity = Severity.valueOf(result.severity.name),
            fix = fix,
            note = result.reasons.take(3).joinToString("; "),
            source = "classifier"
        )

        fun putFix(o: JSONObject, f: GeoFix) {
            o.put("lat", f.lat)
            o.put("lng", f.lng)
            f.accuracyM?.let { o.put("accuracyM", it.toDouble()) }
            o.put("fixEpochMs", f.timeMs)
            f.provider?.let { o.put("provider", it) }
        }

        fun fromJson(o: JSONObject): SosEvent = SosEvent(
            deviceShortId = o.getInt("sourceDeviceShortId"),
            epochSeconds = o.getLong("originalEpochSeconds"),
            type = Type.valueOf(o.optString("signalType", "SOS")),
            severity = Severity.values().firstOrNull { it.wire == o.optInt("severity", 2) } ?: Severity.HIGH,
            fix = if (o.has("lat") && o.has("lng")) GeoFix(
                o.getDouble("lat"), o.getDouble("lng"),
                if (o.has("accuracyM")) o.getDouble("accuracyM").toFloat() else null,
                o.optLong("fixEpochMs", System.currentTimeMillis()),
                o.optString("provider", "").ifEmpty { null }
            ) else null,
            note = o.optString("note", "").ifEmpty { null },
            source = o.optString("source", "manual")
        )
    }
}
