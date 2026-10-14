"""Backend tests: `python -m unittest test_server -v` (from backend/)."""

import unittest
from datetime import datetime
from unittest import mock

import server
from server import LOCAL_TZ, app, is_open

GWALIOR = (26.2183, 78.1828)


class SosFlowTest(unittest.TestCase):
    def setUp(self):
        server.events.clear()
        self.c = app.test_client()

    def test_direct_then_relay_then_live_merge_into_one_event(self):
        ev = {"eventId": "beef-68e77800", "signalType": "SOS", "severity": 2, "sourceDeviceShortId": 0xBEEF,
              "originalEpochSeconds": 1760000000, "lat": GWALIOR[0], "lng": GWALIOR[1], "accuracyM": 12,
              "fixEpochMs": 1760000000000}
        self.assertEqual(self.c.post("/sos", json=ev).status_code, 200)
        relay = {"signalType": "SOS", "severity": 2, "sourceDeviceShortId": 0xBEEF, "originalEpochSeconds": 1760000000,
                 "relayRssi": -61, "relayedBy": "1a2b", "hops": 1, "lat": 26.21831, "lng": 78.18281,
                 "fixEpochMs": 1760000005000}
        r = self.c.post("/relay", json=relay)  # no eventId: derived from shortId+epoch
        self.assertEqual(r.get_json()["eventId"], "beef-68e77800")
        live = {"event": ev, "points": [{"lat": 26.219, "lng": 78.183, "fixEpochMs": 1760000010000},
                                        {"lat": 26.220, "lng": 78.184, "fixEpochMs": 1760000020000}]}
        self.assertEqual(self.c.post("/sos/beef-68e77800/location", json=live).status_code, 200)

        e = self.c.get("/sos/beef-68e77800").get_json()
        self.assertEqual(len(server.events), 1)
        self.assertEqual(e["vias"], ["ble-relay", "direct-http"])
        self.assertEqual(e["trailPoints"], 4)
        self.assertAlmostEqual(e["lastLocation"]["lat"], 26.220)
        self.assertIn("maps.google.com", e["mapsLink"])

    def test_location_push_creates_event_that_only_went_out_by_sms(self):
        body = {"event": {"signalType": "SOS", "severity": 2, "sourceDeviceShortId": 1, "originalEpochSeconds": 5},
                "points": [{"lat": 1.0, "lng": 2.0, "fixEpochMs": 5000}]}
        r = self.c.post("/sos/0001-00000005/location", json=body)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["trailPoints"], 1)

    def test_duplicate_points_ignored_and_bad_points_dropped(self):
        body = {"event": {"sourceDeviceShortId": 1, "originalEpochSeconds": 5},
                "points": [{"lat": 1, "lng": 2, "fixEpochMs": 1}, {"lat": 1, "lng": 2, "fixEpochMs": 1},
                           {"lat": 999, "lng": 2, "fixEpochMs": 2}, "junk"]}
        r = self.c.post("/sos/0001-00000005/location", json=body).get_json()
        self.assertEqual(r["added"], 1)

    def test_v1_relay_payload_still_accepted(self):
        old = {"signalType": "SOS", "severity": 2, "sourceDeviceShortId": 4660, "originalEpochSeconds": 1760000000,
               "relayRssi": -70, "relayedBy": "PhoneB-demo"}
        r = self.c.post("/relay", json=old)
        self.assertEqual(r.status_code, 200)
        self.assertEqual(r.get_json()["eventId"], "1234-68e77800")

    def test_resolve_blocks_further_location(self):
        self.c.post("/sos", json={"eventId": "x-1", "severity": 2})
        self.assertEqual(self.c.post("/sos/x-1/resolve").status_code, 200)
        r = self.c.post("/sos/x-1/location", json={"points": [{"lat": 1, "lng": 1}]})
        self.assertEqual(r.status_code, 409)

    def test_severity_only_escalates(self):
        self.c.post("/sos", json={"eventId": "s-1", "severity": 1})
        self.c.post("/sos", json={"eventId": "s-1", "severity": 2})
        self.c.post("/sos", json={"eventId": "s-1", "severity": 0})
        self.assertEqual(self.c.get("/sos/s-1").get_json()["severity"], 2)

    def test_bad_requests(self):
        self.assertEqual(self.c.post("/sos", data="nope").status_code, 400)
        self.assertEqual(self.c.post("/sos", json={"foo": 1}).status_code, 400)
        self.assertEqual(self.c.get("/sos/nope").status_code, 404)

    def test_dashboard_and_health_render(self):
        self.c.post("/sos", json={"eventId": "d-1", "severity": 2, "lat": 1, "lng": 2})
        self.c.post("/sos", json={"eventId": "d-2", "severity": 1})
        html = self.c.get("/").get_data(as_text=True)
        self.assertIn("d-1", html)
        self.assertIn("no location yet", html)
        self.assertEqual(self.c.get("/health").status_code, 200)


class SafePlacesTest(unittest.TestCase):
    def setUp(self):
        self.c = app.test_client()
        server.real_places = []

    def test_requires_coords(self):
        self.assertEqual(self.c.get("/safe-places/nearby").status_code, 400)
        self.assertEqual(self.c.get("/safe-places/nearby?lat=200&lng=1").status_code, 400)

    def test_synthetic_places_sorted_labelled_and_stable(self):
        url = f"/safe-places/nearby?lat={GWALIOR[0]}&lng={GWALIOR[1]}&radius=2000"
        a = self.c.get(url).get_json()
        b = self.c.get(f"/safe-places/nearby?lat={GWALIOR[0] + 0.0004}&lng={GWALIOR[1]}&radius=2000").get_json()
        self.assertTrue(a["synthetic"])
        self.assertGreater(a["count"], 0)
        d = [p["distance_m"] for p in a["places"]]
        self.assertEqual(d, sorted(d))
        self.assertTrue(all(p["distance_m"] <= 2000 for p in a["places"]))
        self.assertTrue(all("(demo)" in p["name"] for p in a["places"]))
        self.assertEqual({p["id"] for p in a["places"]} & {p["id"] for p in b["places"]} != set(), True)
        for p in a["places"]:
            self.assertIn(p["open_now"], (True, False, None))

    def test_curated_place_served_and_synthetic_off(self):
        with mock.patch.object(server, "DEMO_SYNTHETIC", False):
            r = self.c.post("/safe-places", json={"name": "Test PS", "type": "police", "lat": 26.219, "lng": 78.183, "hours": "24/7"})
            self.assertEqual(r.status_code, 201)
            j = self.c.get(f"/safe-places/nearby?lat={GWALIOR[0]}&lng={GWALIOR[1]}").get_json()
            self.assertFalse(j["synthetic"])
            self.assertEqual([p["name"] for p in j["places"]], ["Test PS"])
            self.assertTrue(j["places"][0]["open_now"])


class OpeningHoursTest(unittest.TestCase):
    @staticmethod
    def at(day, hh, mm=0):  # 2026-10-12 is a Monday
        return datetime(2026, 10, 12 + day, hh, mm, tzinfo=LOCAL_TZ)

    def test_basic(self):
        h = {"daily": [["09:00", "21:00"]]}
        self.assertTrue(is_open(h, self.at(0, 9)))
        self.assertTrue(is_open(h, self.at(0, 20, 59)))
        self.assertFalse(is_open(h, self.at(0, 21)))
        self.assertFalse(is_open(h, self.at(0, 8, 59)))

    def test_24_7_and_unknown(self):
        self.assertTrue(is_open("24/7", self.at(3, 3)))
        self.assertIsNone(is_open(None, self.at(0, 12)))

    def test_overnight_range_crosses_midnight(self):
        h = {"mon": [["19:00", "01:00"]]}
        self.assertTrue(is_open(h, self.at(0, 23)))      # Monday 23:00
        self.assertTrue(is_open(h, self.at(1, 0, 30)))   # Tuesday 00:30 — Monday's range still running
        self.assertFalse(is_open(h, self.at(1, 1, 30)))  # Tuesday 01:30
        self.assertFalse(is_open(h, self.at(0, 0, 30)))  # Monday 00:30 — Sunday had no range

    def test_weekday_only(self):
        h = {"mon": [["10:00", "16:00"]]}
        self.assertTrue(is_open(h, self.at(0, 11)))
        self.assertFalse(is_open(h, self.at(1, 11)))


if __name__ == "__main__":
    unittest.main()
