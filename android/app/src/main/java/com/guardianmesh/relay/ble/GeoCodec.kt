package com.guardianmesh.relay.ble

import kotlin.math.roundToLong

/**
 * Packs lat/lng into 3 bytes each (24-bit fixed point) instead of 2 × 8-byte doubles.
 *
 *   lat: 180° / (2^24 − 1) ≈ 0.0000107° ≈ 1.2 m worst-case step
 *   lng: 360° / (2^24 − 1) ≈ 0.0000215° ≈ 2.4 m at the equator, ≈ 2.1 m in Gwalior
 *
 * Phone GPS is ±5–20 m on a good day, so the quantization error is noise.
 * 16 bytes → 6 bytes is what lets location fit next to the rest of the SOS
 * payload in a 31-byte legacy BLE advertisement.
 */
object GeoCodec {
    private const val MAX24 = 0xFFFFFF

    fun encodeLat(lat: Double): Int = (((lat.coerceIn(-90.0, 90.0) + 90.0) / 180.0) * MAX24).roundToLong().toInt()
    fun encodeLng(lng: Double): Int = (((lng.coerceIn(-180.0, 180.0) + 180.0) / 360.0) * MAX24).roundToLong().toInt()

    fun decodeLat(v: Int): Double = (v and MAX24).toDouble() / MAX24 * 180.0 - 90.0
    fun decodeLng(v: Int): Double = (v and MAX24).toDouble() / MAX24 * 360.0 - 180.0

    fun put24(out: ByteArray, offset: Int, v: Int) {
        out[offset] = (v ushr 16).toByte()
        out[offset + 1] = (v ushr 8).toByte()
        out[offset + 2] = v.toByte()
    }

    fun get24(src: ByteArray, offset: Int): Int =
        ((src[offset].toInt() and 0xFF) shl 16) or
            ((src[offset + 1].toInt() and 0xFF) shl 8) or
            (src[offset + 2].toInt() and 0xFF)
}
