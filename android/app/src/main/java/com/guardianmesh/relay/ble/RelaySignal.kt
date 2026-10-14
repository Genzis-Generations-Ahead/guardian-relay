package com.guardianmesh.relay.ble

import com.guardianmesh.relay.location.GeoFix
import java.util.Locale

/**
 * The payload that travels over the air inside the BLE advertisement's
 * service-data field (UUID 0xFDA1).
 *
 * v2 layout — 19 bytes, big-endian:
 *
 *   [0]      version (high nibble, =2) | signalType (low nibble: 1=SOS, 2=FOLLOWING_ALERT)
 *   [1]      bits 0-1 severity (0 low, 1 med, 2 high) | bit 2 hasLocation | bits 3-7 reserved
 *   [2]      ttl — hops this packet may still be re-broadcast by phones WITHOUT internet
 *   [3]      seq — bumped every time the sender re-advertises with a newer location
 *   [4..5]   deviceShortId (16-bit)
 *   [6..9]   epochSeconds of the SOS start (unsigned 32-bit) — together with deviceShortId
 *            this is the event's identity everywhere (BLE, SMS, backend)
 *   [10..12] latitude, 24-bit fixed point   (see GeoCodec)
 *   [13..15] longitude, 24-bit fixed point
 *   [16]     accuracy in metres, 0..254 (255 = unknown or worse than 254 m)
 *   [17..18] fix time as seconds AFTER epochSeconds (unsigned 16-bit, caps at ~18 h)
 *
 * Air budget (legacy, non-connectable advert → Android adds no Flags field):
 *   16-bit service UUID list   4 bytes
 *   service data header+UUID   4 bytes
 *   this payload              19 bytes
 *   total                     27 of 31  ✓ (still fits, 30/31, even if a ROM adds Flags)
 *
 * v1 (the original 8-byte format: type, severity, shortId, epoch) is still decoded,
 * so an old APK on one of the phones doesn't break the demo.
 */
data class RelaySignal(
    val signalType: Int,
    val severity: Int,
    val deviceShortId: Int,
    val epochSeconds: Long,
    val ttl: Int = DEFAULT_TTL,
    val seq: Int = 0,
    val fix: GeoFix? = null,
    val version: Int = 2
) {
    /** Same id format the backend and SosEvent use, so all delivery paths merge into one event. */
    val eventId: String get() = eventIdOf(deviceShortId, epochSeconds)

    val typeName: String get() = if (signalType == TYPE_SOS) "SOS" else "FOLLOWING_ALERT"

    fun withTtl(newTtl: Int) = copy(ttl = newTtl.coerceIn(0, 255))

    fun toBytes(): ByteArray {
        val out = ByteArray(SIZE_V2)
        out[0] = ((2 shl 4) or (signalType and 0x0F)).toByte()
        out[1] = ((severity and 0x03) or (if (fix != null) 0x04 else 0)).toByte()
        out[2] = ttl.coerceIn(0, 255).toByte()
        out[3] = (seq and 0xFF).toByte()
        out[4] = (deviceShortId ushr 8).toByte()
        out[5] = deviceShortId.toByte()
        val e = epochSeconds and 0xFFFFFFFFL
        out[6] = (e ushr 24).toByte()
        out[7] = (e ushr 16).toByte()
        out[8] = (e ushr 8).toByte()
        out[9] = e.toByte()
        if (fix != null) {
            GeoCodec.put24(out, 10, GeoCodec.encodeLat(fix.lat))
            GeoCodec.put24(out, 13, GeoCodec.encodeLng(fix.lng))
            val acc = fix.accuracyM
            out[16] = (if (acc == null || acc > 254f) 255 else acc.toInt().coerceAtLeast(0)).toByte()
            val offset = (fix.timeMs / 1000 - epochSeconds).coerceIn(0L, 0xFFFFL)
            out[17] = (offset ushr 8).toByte()
            out[18] = offset.toByte()
        } else {
            out[16] = 0xFF.toByte()
        }
        return out
    }

    fun describe(): String {
        val loc = fix?.let {
            String.format(Locale.US, "%.5f,%.5f ±%sm", it.lat, it.lng, it.accuracyM?.toInt()?.toString() ?: "?")
        } ?: "no location"
        return "$typeName sev=$severity id=${eventId} seq=$seq ttl=$ttl $loc"
    }

    companion object {
        const val TYPE_SOS = 1
        const val TYPE_FOLLOWING_ALERT = 2
        const val SIZE_V1 = 8
        const val SIZE_V2 = 19

        /** How many no-internet phones may re-broadcast a packet. 3 hops ≈ 3 × 30–80 m outdoors. */
        const val DEFAULT_TTL = 3

        fun eventIdOf(deviceShortId: Int, epochSeconds: Long): String =
            String.format(Locale.US, "%04x-%08x", deviceShortId and 0xFFFF, epochSeconds and 0xFFFFFFFFL)

        fun fromBytes(bytes: ByteArray): RelaySignal? {
            if (bytes.isEmpty()) return null
            val version = (bytes[0].toInt() and 0xF0) ushr 4
            return when {
                version == 2 && bytes.size >= SIZE_V2 -> decodeV2(bytes)
                version == 0 && bytes.size >= SIZE_V1 -> decodeV1(bytes)
                else -> null
            }
        }

        private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

        private fun decodeV2(b: ByteArray): RelaySignal {
            val type = u8(b, 0) and 0x0F
            val flags = u8(b, 1)
            val shortId = (u8(b, 4) shl 8) or u8(b, 5)
            val epoch = (u8(b, 6).toLong() shl 24) or (u8(b, 7).toLong() shl 16) or
                (u8(b, 8).toLong() shl 8) or u8(b, 9).toLong()
            val fix = if (flags and 0x04 != 0) {
                val acc = u8(b, 16)
                val offset = (u8(b, 17) shl 8) or u8(b, 18)
                GeoFix(
                    lat = GeoCodec.decodeLat(GeoCodec.get24(b, 10)),
                    lng = GeoCodec.decodeLng(GeoCodec.get24(b, 13)),
                    accuracyM = if (acc == 255) null else acc.toFloat(),
                    timeMs = (epoch + offset) * 1000,
                    provider = "ble"
                )
            } else null
            return RelaySignal(type, flags and 0x03, shortId, epoch, u8(b, 2), u8(b, 3), fix, 2)
        }

        private fun decodeV1(b: ByteArray): RelaySignal {
            val shortId = (u8(b, 2) shl 8) or u8(b, 3)
            val epoch = (u8(b, 4).toLong() shl 24) or (u8(b, 5).toLong() shl 16) or
                (u8(b, 6).toLong() shl 8) or u8(b, 7).toLong()
            return RelaySignal(u8(b, 0), u8(b, 1), shortId, epoch, ttl = 0, seq = 0, fix = null, version = 1)
        }

        /** Seq is one byte and wraps; "newer" = ahead by 1..127 modulo 256. */
        fun isNewerSeq(candidate: Int, last: Int): Boolean {
            val d = (candidate - last) and 0xFF
            return d in 1..127
        }

        /** Deterministic short id for this device, readable in logs. */
        fun localDeviceShortId(androidId: String?): Int = ((androidId ?: "unknown-device").hashCode() and 0xFFFF)
    }
}
