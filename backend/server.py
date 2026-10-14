"""
Guardian Relay — demo backend.

Receives SOS events three ways and merges them into ONE record per eventId
("<deviceShortId>-<epochSeconds>", the same id the phone, BLE packet and SMS use):

    POST /sos                       victim's phone, direct over internet
    POST /relay                     a relay phone that heard the SOS over BLE
    POST /sos/<eventId>/location    live location every 10 s while SOS is active
    POST /sos/<eventId>/resolve     victim tapped "I'm safe"

Safe Exit:
    GET  /safe-places/nearby?lat=&lng=&radius=2000
    POST /safe-places               add a real, curated place (see safe_places.example.json)

Views:
    GET  /            live dashboard (auto-refresh 2 s)
    GET  /events      JSON
    GET  /sos/<id>    one event incl. location trail
    GET  /health

Run:
    pip install -r requirements.txt
    python server.py                      # 0.0.0.0:5000
On the phones set the backend to http://<this machine's LAN IP>:5000 (same Wi-Fi), or an ngrok URL.

Safe places: real entries come from safe_places.json (if present) + POST /safe-places.
For demos anywhere, DEMO_SYNTHETIC_PLACES=1 (default) also generates clearly-labelled
"(demo)" places around the requester when fewer than 3 real ones are in range.
Set DEMO_SYNTHETIC_PLACES=0 to serve only real data.
"""

from __future__ import annotations

import json
import math
import os
import random
import threading
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

from flask import Flask, jsonify, render_template_string, request

app = Flask(__name__)

LOCAL_TZ = ZoneInfo(os.environ.get("GUARDIAN_TZ", "Asia/Kolkata"))
DEMO_SYNTHETIC = os.environ.get("DEMO_SYNTHETIC_PLACES", "1") == "1"
MAX_TRAIL = 500
MAX_EVENTS = 200

_lock = threading.Lock()
events: "OrderedDict[str, dict]" = OrderedDict()  # newest last


# ───────────────────────────── helpers ─────────────────────────────

def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%d %H:%M:%S UTC")


def haversine_m(lat1: float, lng1: float, lat2: float, lng2: float) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lng2 - lng1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(min(1.0, a)))


def parse_latlng(lat, lng):
    try:
        lat, lng = float(lat), float(lng)
    except (TypeError, ValueError):
        return None
    if not (-90 <= lat <= 90 and -180 <= lng <= 180):
        return None
    return lat, lng


def event_id_from(payload: dict) -> str | None:
    if payload.get("eventId"):
        return str(payload["eventId"])
    try:
        return f"{int(payload['sourceDeviceShortId']) & 0xFFFF:04x}-{int(payload['originalEpochSeconds']) & 0xFFFFFFFF:08x}"
    except (KeyError, TypeError, ValueError):
        return None


def point_from(d: dict) -> dict | None:
    ll = parse_latlng(d.get("lat"), d.get("lng"))
    if ll is None:
        return None
    p = {"lat": ll[0], "lng": ll[1]}
    if d.get("accuracyM") is not None:
        p["accuracyM"] = round(float(d["accuracyM"]), 1)
    p["fixEpochMs"] = int(d.get("fixEpochMs") or now_utc().timestamp() * 1000)
    if d.get("provider"):
        p["provider"] = d["provider"]
    return p


def upsert_event(payload: dict, via: str, extra: dict | None = None) -> tuple[dict | None, str | None]:
    """Create or update an event from any delivery path. Caller holds _lock."""
    eid = event_id_from(payload)
    if eid is None:
        return None, "missing eventId (or sourceDeviceShortId + originalEpochSeconds)"
    ev = events.get(eid)
    t = now_utc()
    if ev is None:
        ev = {
            "eventId": eid,
            "signalType": payload.get("signalType", "SOS"),
            "severity": payload.get("severity", 2),
            "sourceDeviceShortId": payload.get("sourceDeviceShortId"),
            "originalEpochSeconds": payload.get("originalEpochSeconds"),
            "note": payload.get("note"),
            "status": "active",
            "createdAt": iso(t),
            "trail": [],
            "deliveries": [],
        }
        events[eid] = ev
        while len(events) > MAX_EVENTS:
            events.popitem(last=False)
    else:
        events.move_to_end(eid)
    # Severity can only escalate (a classifier MEDIUM followed by a manual HIGH → HIGH).
    try:
        ev["severity"] = max(int(ev.get("severity") or 0), int(payload.get("severity") or 0))
    except (TypeError, ValueError):
        pass
    ev["updatedAt"] = iso(t)
    ev["updatedAtEpoch"] = t.timestamp()
    delivery = {"via": via, "at": iso(t)}
    for k in ("relayedBy", "relayRssi", "hops", "seq", "payloadVersion"):
        if k in payload:
            delivery[k] = payload[k]
    if extra:
        delivery.update(extra)
    ev["deliveries"] = (ev["deliveries"] + [delivery])[-30:]
    ev["vias"] = sorted({d["via"] for d in ev["deliveries"]})
    p = point_from(payload)
    if p:
        add_points(ev, [p])
    return ev, None


def add_points(ev: dict, points: list[dict]) -> int:
    seen = {p["fixEpochMs"] for p in ev["trail"]}
    added = 0
    for p in points:
        if p and p["fixEpochMs"] not in seen:
            ev["trail"].append(p)
            seen.add(p["fixEpochMs"])
            added += 1
    ev["trail"].sort(key=lambda p: p["fixEpochMs"])
    ev["trail"] = ev["trail"][-MAX_TRAIL:]
    if ev["trail"]:
        ev["lastLocation"] = ev["trail"][-1]
    return added


def public_event(ev: dict, trail: bool = False) -> dict:
    out = {k: v for k, v in ev.items() if k != "trail"}
    out["trailPoints"] = len(ev["trail"])
    ll = ev.get("lastLocation")
    if ll:
        out["mapsLink"] = f"https://maps.google.com/?q={ll['lat']:.5f},{ll['lng']:.5f}"
        out["lastFixAgeS"] = max(0, int(now_utc().timestamp() - ll["fixEpochMs"] / 1000))
    if trail:
        out["trail"] = ev["trail"]
    return out


# ───────────────────────────── SOS endpoints ─────────────────────────────

@app.post("/sos")
def sos_direct():
    payload = request.get_json(force=True, silent=True)
    if not isinstance(payload, dict):
        return jsonify({"error": "expected JSON object"}), 400
    with _lock:
        ev, err = upsert_event(payload, payload.get("via") or "direct-http")
    if err:
        return jsonify({"error": err}), 400
    print(f"[SOS direct] {ev['eventId']} sev={ev['severity']} loc={ev.get('lastLocation')}")
    return jsonify({"status": "ok", "eventId": ev["eventId"]}), 200


@app.post("/relay")
def relay():
    """Phone that heard the SOS over BLE. Backwards compatible with the v1 app payload."""
    payload = request.get_json(force=True, silent=True)
    if not isinstance(payload, dict):
        return jsonify({"error": "expected JSON body"}), 400
    with _lock:
        ev, err = upsert_event(payload, "ble-relay")
        count = len(events)
    if err:
        return jsonify({"error": err}), 400
    print(f"[RELAY] {ev['eventId']} via {payload.get('relayedBy')} rssi={payload.get('relayRssi')} hops={payload.get('hops')} loc={ev.get('lastLocation')}")
    return jsonify({"status": "ok", "eventId": ev["eventId"], "storedCount": count}), 200


@app.post("/sos/<event_id>/location")
def sos_location(event_id: str):
    """{"event": {...SosEvent json...}, "points": [{lat,lng,accuracyM,fixEpochMs}, ...]} — upserts."""
    body = request.get_json(force=True, silent=True)
    if not isinstance(body, dict):
        return jsonify({"error": "expected JSON object"}), 400
    ev_payload = dict(body.get("event") or {})
    ev_payload["eventId"] = event_id
    raw_points = body.get("points") or []
    if not isinstance(raw_points, list):
        return jsonify({"error": "points must be a list"}), 400
    points = [p for p in (point_from(x) for x in raw_points if isinstance(x, dict)) if p]
    with _lock:
        ev = events.get(event_id)
        if ev is None:
            # First thing the server hears about this SOS (it went out by SMS/BLE while offline).
            ev, err = upsert_event({k: v for k, v in ev_payload.items() if k not in ("lat", "lng")}, "direct-http")
            if err:
                return jsonify({"error": err}), 400
        if ev["status"] == "resolved":
            return jsonify({"status": "resolved", "eventId": event_id}), 409
        added = add_points(ev, points)
        t = now_utc()
        ev["updatedAt"], ev["updatedAtEpoch"] = iso(t), t.timestamp()
        ev["lastLivePushAt"] = iso(t)
    return jsonify({"status": "ok", "eventId": event_id, "added": added, "trailPoints": len(ev["trail"])}), 200


@app.post("/sos/<event_id>/resolve")
def sos_resolve(event_id: str):
    with _lock:
        ev = events.get(event_id)
        if ev is None:
            return jsonify({"error": "unknown event"}), 404
        ev["status"] = "resolved"
        ev["resolvedAt"] = iso(now_utc())
    return jsonify({"status": "ok", "eventId": event_id}), 200


@app.get("/sos/<event_id>")
def sos_get(event_id: str):
    with _lock:
        ev = events.get(event_id)
        return (jsonify(public_event(ev, trail=True)), 200) if ev else (jsonify({"error": "unknown event"}), 404)


@app.get("/events")
def events_json():
    with _lock:
        return jsonify([public_event(e) for e in reversed(list(events.values()))][:50])


@app.get("/health")
def health():
    return jsonify({"status": "ok", "events": len(events), "time": iso(now_utc())})


# ───────────────────────────── Safe places ─────────────────────────────

DAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def _mins(hhmm: str) -> int:
    h, m = hhmm.split(":")
    return int(h) * 60 + int(m)


def is_open(hours, at: datetime) -> bool | None:
    """hours: "24/7" | {"mon": [["09:00","21:00"]], ...} | {"daily": [[...]]} | None (unknown).
    Ranges may cross midnight, e.g. ["20:00","02:00"]."""
    if hours is None:
        return None
    if hours == "24/7":
        return True
    if not isinstance(hours, dict):
        return None
    local = at.astimezone(LOCAL_TZ)
    minute = local.hour * 60 + local.minute
    today, yesterday = DAYS[local.weekday()], DAYS[(local.weekday() - 1) % 7]

    def ranges(day):
        return hours.get(day, hours.get("daily", [])) or []

    for start, end in ranges(today):
        s, e = _mins(start), _mins(end)
        if s <= e and s <= minute < e:
            return True
        if s > e and minute >= s:  # opened today, closes after midnight
            return True
    for start, end in ranges(yesterday):
        s, e = _mins(start), _mins(end)
        if s > e and minute < e:  # yesterday's overnight range still running
            return True
    return False


def hours_text(hours) -> str | None:
    if hours is None:
        return None
    if hours == "24/7":
        return "Open 24 hours"
    if isinstance(hours, dict) and "daily" in hours:
        return "Daily " + ", ".join(f"{a}–{b}" for a, b in hours["daily"])
    return "See schedule"


def _load_real_places() -> list[dict]:
    path = Path(__file__).with_name("safe_places.json")
    if not path.exists():
        return []
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return [p for p in data if parse_latlng(p.get("lat"), p.get("lng"))]
    except Exception as e:  # noqa: BLE001
        print(f"[safe-places] could not read {path}: {e}")
        return []


real_places: list[dict] = _load_real_places()

SYNTH_TEMPLATES = [
    ("police", "Police Post", "24/7"),
    ("hospital", "Hospital Emergency", "24/7"),
    ("fuel", "Petrol Pump", "24/7"),
    ("pharmacy", "Pharmacy", {"daily": [["08:00", "23:00"]]}),
    ("transit", "Bus Stand", {"daily": [["05:00", "23:30"]]}),
    ("cafe", "Cafe", {"daily": [["08:00", "22:00"]]}),
    ("mall", "Shopping Mall", {"daily": [["10:00", "22:00"]]}),
    ("store", "24h Convenience Store", "24/7"),
    ("bank", "Bank Branch", {"mon": [["10:00", "16:00"]], "tue": [["10:00", "16:00"]], "wed": [["10:00", "16:00"]],
                             "thu": [["10:00", "16:00"]], "fri": [["10:00", "16:00"]]}),
    ("restaurant", "Restaurant", {"daily": [["12:00", "15:30"], ["19:00", "01:00"]]}),
]


def synthetic_places(lat: float, lng: float) -> list[dict]:
    """Deterministic per ~1 km grid cell, so places don't jump around as she walks.
    Clearly labelled '(demo)' — never presented as real."""
    out = []
    for dlat in (-0.01, 0.0, 0.01):
        for dlng in (-0.01, 0.0, 0.01):
            cell_lat = round(lat / 0.01) * 0.01 + dlat
            cell_lng = round(lng / 0.01) * 0.01 + dlng
            rnd = random.Random(f"{cell_lat:.2f},{cell_lng:.2f}")
            for i in range(rnd.randint(1, 3)):
                kind, label, hours = rnd.choice(SYNTH_TEMPLATES)
                out.append({
                    "id": f"demo-{cell_lat:.2f}-{cell_lng:.2f}-{i}",
                    "name": f"{label} (demo)",
                    "type": kind,
                    "lat": cell_lat + rnd.uniform(-0.005, 0.005),
                    "lng": cell_lng + rnd.uniform(-0.005, 0.005),
                    "hours": hours,
                    "source": "synthetic",
                })
    return out


@app.get("/safe-places/nearby")
def safe_places_nearby():
    ll = parse_latlng(request.args.get("lat"), request.args.get("lng"))
    if ll is None:
        return jsonify({"error": "lat and lng query params required, e.g. ?lat=26.2183&lng=78.1828"}), 400
    lat, lng = ll
    try:
        radius = max(100, min(10_000, int(request.args.get("radius", 2000))))
        limit = max(1, min(50, int(request.args.get("limit", 20))))
    except ValueError:
        return jsonify({"error": "radius/limit must be integers"}), 400

    at = now_utc()
    candidates = [dict(p, source=p.get("source", "curated")) for p in real_places]
    real_in_range = [p for p in candidates if haversine_m(lat, lng, p["lat"], p["lng"]) <= radius]
    used_synthetic = DEMO_SYNTHETIC and len(real_in_range) < 3
    if used_synthetic:
        candidates += synthetic_places(lat, lng)

    results = []
    for p in candidates:
        d = haversine_m(lat, lng, p["lat"], p["lng"])
        if d > radius:
            continue
        hours = p.get("hours")
        results.append({
            "id": p.get("id"),
            "name": p.get("name"),
            "type": p.get("type", "unknown"),
            "lat": round(p["lat"], 6),
            "lng": round(p["lng"], 6),
            "distance_m": round(d),
            "open_now": is_open(hours, at),
            "hours_text": p.get("hours_text") or hours_text(hours),
            "source": p.get("source"),
        })
    results.sort(key=lambda r: r["distance_m"])
    return jsonify({
        "origin": {"lat": lat, "lng": lng},
        "radius_m": radius,
        "count": len(results[:limit]),
        "synthetic": used_synthetic,
        "evaluated_at": at.astimezone(LOCAL_TZ).isoformat(timespec="minutes"),
        "places": results[:limit],
    })


@app.post("/safe-places")
def safe_places_add():
    p = request.get_json(force=True, silent=True)
    if not isinstance(p, dict) or not p.get("name") or parse_latlng(p.get("lat"), p.get("lng")) is None:
        return jsonify({"error": "need name, lat, lng (and optionally type, hours)"}), 400
    p.setdefault("id", f"curated-{len(real_places) + 1}")
    p["lat"], p["lng"] = float(p["lat"]), float(p["lng"])
    p["source"] = "curated"
    real_places.append(p)
    return jsonify({"status": "ok", "id": p["id"], "total": len(real_places)}), 201


# ───────────────────────────── dashboard ─────────────────────────────

DASHBOARD_HTML = """
<!doctype html>
<html>
<head>
  <title>Guardian Relay — Live Feed</title>
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta http-equiv="refresh" content="2">
  <style>
    body { font-family: system-ui, sans-serif; background: #0b0b0b; color: #e6e6e6; padding: 16px; max-width: 900px; margin: auto; }
    h1 { color: #ff5252; margin-bottom: 4px; }
    .event { border-left: 5px solid #ff5252; background: #1a1a1a; padding: 12px 16px; margin: 10px 0; border-radius: 6px; }
    .event.sev1 { border-left-color: #ffb300; } .event.sev0 { border-left-color: #66bb6a; }
    .event.resolved { opacity: .5; border-left-color: #757575; }
    .meta { color: #9e9e9e; font-size: 13px; margin-top: 4px; line-height: 1.5; }
    .badge { display: inline-block; font-size: 11px; padding: 1px 7px; border-radius: 9px; margin-right: 4px; background: #333; }
    .b-direct-http { background: #1565c0; } .b-ble-relay { background: #6a1b9a; } .b-active { background: #c62828; }
    a { color: #80cbc4; }
    .empty { color: #757575; }
  </style>
</head>
<body>
  <h1>Guardian Relay — Live Feed</h1>
  <div class="meta">Auto-refreshes every 2 s · {{ count }} event(s) · server time {{ now }}</div>
  {% for e in events %}
    <div class="event sev{{ e.severity }} {{ e.status }}">
      <strong>{{ e.signalType }}</strong> · severity {{ e.severity }} · <code>{{ e.eventId }}</code>
      <span class="badge b-{{ e.status }}">{{ e.status }}</span>
      {% for v in e.vias %}<span class="badge b-{{ v }}">{{ v }}</span>{% endfor %}
      <div class="meta">
        {% if e.mapsLink %}
          📍 <a href="{{ e.mapsLink }}" target="_blank">{{ '%.5f, %.5f'|format(e.lastLocation.lat, e.lastLocation.lng) }}</a>
          ±{{ e.lastLocation.accuracyM or '?' }} m · fix {{ e.lastFixAgeS }} s old · {{ e.trailPoints }} trail point(s)
        {% else %}
          📍 no location yet
        {% endif %}
        <br>first seen {{ e.createdAt }} · updated {{ e.updatedAt }}
        {% set d = e.deliveries[-1] %}
        <br>last delivery: {{ d.via }}{% if d.relayedBy %} by relay {{ d.relayedBy }} (RSSI {{ d.relayRssi }}, hop {{ d.hops }}){% endif %} at {{ d.at }}
        {% if e.note %}<br>note: {{ e.note }}{% endif %}
      </div>
    </div>
  {% else %}
    <p class="empty">Nothing received yet. Trigger SOS on Phone A.</p>
  {% endfor %}
</body>
</html>
"""


@app.get("/")
def dashboard():
    with _lock:
        evs = [public_event(e) for e in reversed(list(events.values()))][:20]
        return render_template_string(DASHBOARD_HTML, events=evs, count=len(events), now=iso(now_utc()))


if __name__ == "__main__":
    # 0.0.0.0 so phones on the same Wi-Fi can reach it. debug=False: the reloader would wipe in-memory events.
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", 5000)), debug=False, threaded=True)
