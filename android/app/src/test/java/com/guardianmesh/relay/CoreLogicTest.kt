package com.guardianmesh.relay

import com.guardianmesh.relay.ble.GeoCodec
import com.guardianmesh.relay.ble.RelaySignal
import com.guardianmesh.relay.location.GeoFix
import com.guardianmesh.relay.safeexit.SafePlace
import com.guardianmesh.relay.safeexit.SafePlaceScorer
import com.guardianmesh.relay.safeexit.SafePlacesParser
import com.guardianmesh.relay.sos.DeliveryResult
import com.guardianmesh.relay.sos.FallbackChain
import com.guardianmesh.relay.sos.SosChannel
import com.guardianmesh.relay.sos.SosEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/** Pure-JVM tests: `./gradlew :app:testDebugUnitTest` — no phone needed. */
class CoreLogicTest {

    private val gwalior = GeoFix(26.21830, 78.18280, 12f, 1_760_000_100_000L, "gps")

    // ───────────── BLE payload ─────────────

    @Test fun payloadRoundTripWithLocation() {
        val s = RelaySignal(RelaySignal.TYPE_SOS, 2, 0xBEEF, 1_760_000_000L, ttl = 3, seq = 7, fix = gwalior)
        val bytes = s.toBytes()
        assertEquals(19, bytes.size)
        val d = RelaySignal.fromBytes(bytes)!!
        assertEquals(s.signalType, d.signalType)
        assertEquals(2, d.severity)
        assertEquals(0xBEEF, d.deviceShortId)
        assertEquals(1_760_000_000L, d.epochSeconds)
        assertEquals(3, d.ttl)
        assertEquals(7, d.seq)
        assertEquals(s.eventId, d.eventId)
        assertEquals("beef-68e77800", d.eventId)
        val f = d.fix!!
        assertTrue("lat err", GeoFix.haversineMeters(gwalior.lat, gwalior.lng, f.lat, gwalior.lng) < 1.3)
        assertTrue("lng err", GeoFix.haversineMeters(gwalior.lat, gwalior.lng, gwalior.lat, f.lng) < 2.5)
        assertEquals(12f, f.accuracyM!!, 0.01f)
        assertEquals(1_760_000_100_000L, f.timeMs) // fix 100 s after SOS start
    }

    @Test fun payloadWithoutLocation() {
        val d = RelaySignal.fromBytes(RelaySignal(2, 1, 1, 42L).toBytes())!!
        assertNull(d.fix)
        assertEquals(RelaySignal.TYPE_FOLLOWING_ALERT, d.signalType)
    }

    @Test fun unknownAccuracyAndHugeAccuracy() {
        val noAcc = RelaySignal.fromBytes(RelaySignal(1, 2, 1, 1L, fix = gwalior.copy(accuracyM = null)).toBytes())!!
        assertNull(noAcc.fix!!.accuracyM)
        val huge = RelaySignal.fromBytes(RelaySignal(1, 2, 1, 1L, fix = gwalior.copy(accuracyM = 900f)).toBytes())!!
        assertNull(huge.fix!!.accuracyM)
    }

    @Test fun decodesOldV1Payload() {
        val v1 = byteArrayOf(1, 2, 0x12, 0x34, 0x68, 0xE7.toByte(), 0x78, 0x00)
        val d = RelaySignal.fromBytes(v1)!!
        assertEquals(1, d.version)
        assertEquals(0x1234, d.deviceShortId)
        assertEquals(1_760_000_000L, d.epochSeconds)
        assertEquals(0, d.ttl)
        assertNull(d.fix)
    }

    @Test fun rejectsGarbage() {
        assertNull(RelaySignal.fromBytes(byteArrayOf()))
        assertNull(RelaySignal.fromBytes(byteArrayOf(0x20, 1, 2))) // v2 header, truncated
        assertNull(RelaySignal.fromBytes(byteArrayOf(0x70, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test fun fitsLegacyAdvertisement() {
        val payload = RelaySignal(1, 2, 1, 1L, fix = gwalior).toBytes().size
        val uuidList16 = 2 + 2
        val serviceData16 = 2 + 2 + payload
        val flagsIfAdded = 3
        assertTrue(uuidList16 + serviceData16 <= 31)
        assertTrue("still fits even if a ROM adds Flags", uuidList16 + serviceData16 + flagsIfAdded <= 31)
    }

    @Test fun geoCodecWorstCaseErrorOverTheGlobe() {
        val rnd = Random(1)
        repeat(20_000) {
            val lat = rnd.nextDouble() * 180 - 90
            val lng = rnd.nextDouble() * 360 - 180
            assertTrue(abs(GeoCodec.decodeLat(GeoCodec.encodeLat(lat)) - lat) <= 180.0 / 0xFFFFFF / 2 + 1e-12)
            assertTrue(abs(GeoCodec.decodeLng(GeoCodec.encodeLng(lng)) - lng) <= 360.0 / 0xFFFFFF / 2 + 1e-12)
        }
        // Extremes survive.
        assertEquals(-90.0, GeoCodec.decodeLat(GeoCodec.encodeLat(-90.0)), 1e-9)
        assertEquals(180.0, GeoCodec.decodeLng(GeoCodec.encodeLng(180.0)), 1e-9)
    }

    @Test fun seqWrapAround() {
        assertTrue(RelaySignal.isNewerSeq(1, 0))
        assertTrue(RelaySignal.isNewerSeq(0, 255))
        assertTrue(RelaySignal.isNewerSeq(3, 250))
        assertFalse(RelaySignal.isNewerSeq(5, 5))
        assertFalse(RelaySignal.isNewerSeq(4, 5))
        assertFalse(RelaySignal.isNewerSeq(250, 3))
    }

    // ───────────── Safe exit scoring ─────────────

    private fun place(id: String, metersNorth: Double, open: Boolean?) =
        SafePlace(id, id, "test", gwalior.lat + metersNorth / 111_195.0, gwalior.lng, open)

    @Test fun openBeatsCloserClosed() {
        val best = SafePlaceScorer.pickBest(listOf(place("closed-50m", 50.0, false), place("open-1500m", 1500.0, true)), gwalior)!!
        assertEquals("open-1500m", best.place.id)
    }

    @Test fun nearerOpenBeatsFartherOpen() {
        val best = SafePlaceScorer.pickBest(listOf(place("open-900", 900.0, true), place("open-300", 300.0, true)), gwalior)!!
        assertEquals("open-300", best.place.id)
        assertEquals(300.0, best.distanceM, 2.0)
    }

    @Test fun openNearbyBeatsUnknownSlightlyCloser() {
        val best = SafePlaceScorer.pickBest(listOf(place("unknown-200", 200.0, null), place("open-350", 350.0, true)), gwalior)!!
        assertEquals("open-350", best.place.id)
    }

    @Test fun unknownCloseBeatsOpenFar() {
        val best = SafePlaceScorer.pickBest(listOf(place("unknown-150", 150.0, null), place("open-2000", 2000.0, true)), gwalior)!!
        assertEquals("unknown-150", best.place.id)
    }

    @Test fun allClosedFallsBackToNearest() {
        val ranked = SafePlaceScorer.rank(listOf(place("c-800", 800.0, false), place("c-100", 100.0, false)), gwalior)
        assertEquals("c-100", ranked.first().place.id)
    }

    @Test fun emptyListGivesNull() = assertNull(SafePlaceScorer.pickBest(emptyList(), gwalior))

    @Test fun parsesBackendResponseAndSkipsBadEntries() {
        val json = JSONObject(
            """{"places":[
                {"id":"p1","name":"Padav Police Station","type":"police","lat":26.21,"lng":78.17,"open_now":true,"hours_text":"24/7"},
                {"id":"p2","name":"No coords"},
                {"id":"p3","name":"Clinic","type":"hospital","lat":26.22,"lng":78.19,"open_now":null}
            ]}"""
        )
        val places = SafePlacesParser.parse(json)
        assertEquals(listOf("p1", "p3"), places.map { it.id })
        assertEquals(true, places[0].openNow)
        assertNull(places[1].openNow)
    }

    // ───────────── Fallback chain ─────────────

    private class Fake(
        override val name: String,
        val unavailable: String? = null,
        val succeed: Boolean = true,
        val explode: Boolean = false
    ) : SosChannel {
        var calls = 0
        override fun unavailableReason() = unavailable
        override fun deliver(event: SosEvent): DeliveryResult {
            calls++
            if (explode) throw IllegalStateException("boom")
            return if (succeed) DeliveryResult.ok("ok") else DeliveryResult.fail("nope")
        }
    }

    private val event = SosEvent(0x1234, 1_760_000_000L, fix = gwalior)

    @Test fun internetWorksSoNothingElseRuns() {
        val http = Fake("HTTP"); val sms = Fake("SMS"); val ble = Fake("BLE")
        val out = FallbackChain(listOf(http, sms, ble)).run(event)
        assertEquals("HTTP", out.deliveredVia)
        assertEquals(0, sms.calls); assertEquals(0, ble.calls)
    }

    @Test fun offlineFallsToSms() {
        val http = Fake("HTTP", unavailable = "no network"); val sms = Fake("SMS"); val ble = Fake("BLE")
        val out = FallbackChain(listOf(http, sms, ble)).run(event)
        assertEquals("SMS", out.deliveredVia)
        assertEquals(0, http.calls); assertEquals(0, ble.calls)
        assertTrue(out.attempts[0].skipped)
    }

    @Test fun noSignalFallsToBle() {
        val http = Fake("HTTP", succeed = false); val sms = Fake("SMS", succeed = false); val ble = Fake("BLE")
        val out = FallbackChain(listOf(http, sms, ble)).run(event)
        assertEquals("BLE", out.deliveredVia)
        assertEquals(3, out.attempts.size)
    }

    @Test fun crashingChannelDoesNotStopChain() {
        val out = FallbackChain(listOf(Fake("HTTP", explode = true), Fake("SMS"))).run(event)
        assertEquals("SMS", out.deliveredVia)
        assertTrue(out.attempts[0].result!!.detail.contains("boom"))
    }

    @Test fun everythingFails() {
        val out = FallbackChain(listOf(Fake("HTTP", unavailable = "x"), Fake("SMS", unavailable = "y"), Fake("BLE", succeed = false))).run(event)
        assertFalse(out.delivered)
        assertNull(out.deliveredVia)
    }

    @Test fun bleAlwaysRunsWhenAskedEvenAfterSuccess() {
        val ble = Fake("BLE")
        val out = FallbackChain(listOf(Fake("HTTP"), Fake("SMS"), ble)).run(event, alsoRun = setOf("BLE"))
        assertEquals("HTTP", out.deliveredVia)
        assertEquals(1, ble.calls)
    }

    // ───────────── SOS event ─────────────

    @Test fun smsIsSingleSegmentAndHasMapsLink() {
        val txt = event.smsText("Aaryansh Rathore")
        assertTrue(txt, txt.length <= 160)
        assertTrue(txt.contains("https://maps.google.com/?q=26.21830,78.18280"))
        assertTrue(txt.contains(event.eventId))
        assertTrue("GSM-7 only", txt.all { it.code < 128 })
        assertTrue(event.copy(fix = null).smsText("").contains("Location unavailable"))
    }

    @Test fun eventIdMatchesBlePacketId() {
        assertEquals(event.eventId, event.toSignal().eventId)
        assertEquals(event.eventId, RelaySignal.fromBytes(event.toSignal().toBytes())!!.eventId)
    }

    @Test fun eventJsonRoundTrip() {
        val back = SosEvent.fromJson(event.toJson())
        assertEquals(event.eventId, back.eventId)
        assertEquals(event.fix!!.lat, back.fix!!.lat, 1e-9)
        assertEquals(event.severity, back.severity)
        assertNotNull(back.fix.accuracyM)
    }

    @Test fun backendUrlNormalisation() {
        assertEquals("http://192.168.1.5:5000", GuardianConfig.normalizeBaseUrl("http://192.168.1.5:5000/relay"))
        assertEquals("http://192.168.1.5:5000", GuardianConfig.normalizeBaseUrl(" 192.168.1.5:5000/ "))
        assertEquals("https://x.ngrok.app", GuardianConfig.normalizeBaseUrl("https://x.ngrok.app/"))
        assertEquals("", GuardianConfig.normalizeBaseUrl("  "))
    }

    @Test fun classifierHighRoutesToSosEventFields() {
        val r = FollowingClassifier.classify(FollowingClassifier.FollowingInput(12, 2, 2.0, true, 2, true, false))
        assertEquals(FollowingClassifier.Route.SOS, r.route)
        assertEquals(SosEvent.Severity.HIGH, SosEvent.Severity.valueOf(r.severity.name))
    }
}
