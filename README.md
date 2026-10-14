# Guardian Relay — v0.2

An SOS goes out from a phone **with or without internet**. The phone tries each route in order:

```
internet POST /sos  ──fail──▶  SMS to trusted contacts (maps link)  ──fail──▶  BLE mesh broadcast
                                                                            │
                         any phone running RelayService hears it ◀──────────┘
                           ├─ has internet → POST /relay (with the sender's GPS)
                           └─ no internet  → re-broadcast, TTL−1 (up to 3 hops)
```

While the SOS is active, `LocationService` (foreground) pushes the location every 10 s. If the
internet drops, points are buffered, the BLE advert is refreshed with the newest fix, and contacts
get an SMS update at most every 5 min.

```
guardian-relay/
├── android/      App module. The core is framework-only (no AndroidX / OkHttp / Play services)
├── backend/      Flask: SOS intake + merge, live location, safe places, dashboard  (+ tests)
├── classifier/   Standalone Python copy of FollowingClassifier
└── README.md
```

---

## API for the main UI (Tanush)

```kotlin
// once, in Application.onCreate()   (GuardianApp already does this for the demo)
Guardian.init(this)
Guardian.config.backendBaseUrl = "https://api.example.com"
Guardian.config.trustedContacts = listOf("+919876543210")
Guardian.config.senderName = "Priya"

// SOS — returns immediately; outcome callback on main thread
SosDispatcher.send(SosEvent.create(location /* android.location.Location? */)) { outcome ->
    outcome.deliveredVia   // "HTTP" | "SMS" | "BLE" | null
}
SosDispatcher.stop()                         // "I'm safe"
SosDispatcher.addListener { state -> … }     // active?, deliveredVia, bleBroadcasting, offline time…

// Safe Exit — opens Google Maps walking navigation to the best nearby open place
SafeExit.start(location) { result -> … }     // Navigating(best, alternatives) | FallbackSearch | Failed

// Make this phone a relay / mesh node (background-safe foreground service)
RelayService.start(context)

// Classifier glue
val r = FollowingClassifier.classify(input)
if (r.route == Route.SOS) SosDispatcher.send(SosEvent.fromClassification(r, fix))
else SafeExit.start(fix)
```

| Package | What's in it |
|---|---|
| `sos/` | `SosDispatcher`, `SosEvent`, `FallbackChain` (pure, tested), `Channels.kt` (HTTP / SMS / BLE) |
| `ble/` | `RelaySignal` (19-byte payload), `GeoCodec`, `BleAdvertiserManager`, `BleScannerManager`, `MeshRelay`, `RelayService` |
| `location/` | `LocationService` (FGS, 10 s), `LocationProvider` (fused + GPS + network, no Play services), `GeoFix` |
| `safeexit/` | `SafeExit`, `SafePlaceScorer` (pure, tested), `MapsLauncher`, `SafePlacesParser` |
| `diag/` | `AppLog` (on-screen + logcat), `Diagnostics`, `Permissions` |

`MainActivity` is only a debug console that calls the same API.

---

## Step 1 — two-phone test checklist

Install the APK on both phones. On first launch, grant everything and choose **Precise** location.
Then on **each** phone, tap **Run diagnostics** and fix every ❌ and ⚠️ it lists.

Battery and background settings matter most. On Xiaomi/Redmi/POCO:

- Autostart **ON** (Fix: OEM)
- Battery saver → **No restrictions**
- Lock the app in Recents

On Samsung, add the app to **Never sleeping apps**. On both, tap **Fix: battery** and allow.

**Phone B (relay):** set the backend `http://<laptop-LAN-IP>:5000`, then tap Save → **Start relay**. The
status bar shows `Scanner ON: … SOS pkts, n/10s, last …s ago, RSSI …`.

**Phone A (sender):** turn on airplane mode, turn Bluetooth back on, and leave the backend URL blank or
unreachable. Tap **TRIGGER SOS**. The log should show:

```
HTTP: skipped (no network)
SMS: FAILED (… RADIO_OFF (airplane mode?))         ← or skipped if no contacts
BLE: OK (broadcasting SOS sev=2 id=… seq=0 ttl=3 26.21830,78.18280 ±12m)
[BLE-ADV] [own] advertising 19B payload …
```

Phone B should log `Heard SOS over BLE (RSSI -55 …)` and then `Forwarded … to backend (HTTP 200)`.
The dashboard at `http://<laptop-IP>:5000/` shows the event with a 📍 Maps link.

**Is pickup reliable?** Phone A advertises every ~100 ms. At 1–2 m, B's status should read roughly
**5–10 packets/10 s or more**. If it reads 0:

- Tap **Raw scan test** on B. If the `devices` count climbs but `SOS pkts` stays at 0, A isn't
  advertising. Check A's log for `advertising FAILED: …`, which names the error.
- If both counts stay at 0, B's scanning itself is blocked (permission, Bluetooth, or the Location toggle
  on Xiaomi / Android ≤ 11).
- If B logs `No SOS adverts for 15s` when A's screen turns off, A's ROM is killing it. Apply the
  battery/autostart fixes above.

**Background test:** on both phones, press Home, lock the screen and wait 2 minutes. The counters
should keep rising (both run as foreground services with a notification).

**Copy log** copies the whole log to the clipboard, so you can paste it into a chat.

### Bugs fixed from v0.1
- `http://` to the LAN backend was blocked on targetSdk 34 (cleartext), so Phone B's forward always failed.
- Advertising and scanning ran from the Activity, so MIUI and One UI killed them in the background. They now run in foreground services.
- More than 5 scan start/stops in 30 s made Android silently return nothing. Restarts are now rate-limited per app.
- Scans older than 30 min were silently downgraded. They now auto-restart every 25 min, and also restart when Bluetooth turns back on.
- The de-dupe set grew forever, and the same event was never re-forwarded after a failed POST.
- `LocationListener` is now fully implemented. A missing `onStatusChanged` crashes Android ≤ 10.

---

## Step 2 — BLE payload v2 (19 bytes, fits a 31-byte legacy advert)

| Byte | Field |
|---|---|
| 0 | version (hi nibble = 2) · signalType (lo nibble) |
| 1 | severity (bits 0-1) · hasLocation (bit 2) |
| 2 | TTL (mesh hops left, starts at 3) |
| 3 | seq (bumped on each location refresh) |
| 4–5 | deviceShortId |
| 6–9 | epochSeconds (SOS start) |
| 10–12 | latitude, 24-bit fixed point (≤ 1.2 m error) |
| 13–15 | longitude, 24-bit fixed point (≤ 2.4 m error) |
| 16 | accuracy in m (255 = unknown) |
| 17–18 | fix time, seconds after start |

On air this takes 4 B (UUID list) + 4 B (service data header) + 19 B = **27/31 bytes**. Old 8-byte v1
packets still decode. `eventId = "<shortId hex>-<epoch hex>"` is identical across BLE, SMS, HTTP and the
backend, so all routes merge into one record.

## Step 4 — Safe Exit scoring
`score = 0.6·e^(−d/750 m) + 0.4·open` where open = 1, unknown = 0.4 and closed = 0. A place known to be
**closed never beats** one that is open or has unknown hours. If the backend is unreachable or nothing is
nearby, Maps opens a "police station" search around her instead.

## Backend
```bash
cd backend && pip install -r requirements.txt && python server.py
python -m unittest test_server -v        # 15 tests
```

| Endpoint | Purpose |
|---|---|
| `POST /sos` | Direct report from the victim's phone |
| `POST /relay` | Report from a relay phone (v1 payload still accepted) |
| `POST /sos/<id>/location` | `{event, points[]}` — creates the event if needed (it went out via SMS/BLE) |
| `POST /sos/<id>/resolve` | Ends the SOS |
| `GET /sos/<id>` | Event with its location trail |
| `GET /events` | All events (JSON) |
| `GET /health` | Health check |
| `GET /safe-places/nearby?lat=&lng=&radius=` | Places sorted by distance, with `open_now` from hours in IST |
| `POST /safe-places` | Add a curated place |

**Safe places data:** there's no real dataset yet. Real entries go in `safe_places.json` (format:
`safe_places.example.json`). Until then, `DEMO_SYNTHETIC_PLACES=1` (the default) generates stable
places named **"(demo)"** around the caller so the flow can be demoed anywhere. Set it to `0` to serve
only real data. A Google Places / OSM Overpass lookup would slot in here.

## What's verified vs. not
- **Verified here:**
  - All Android Kotlin compiled against the API-34 `android.jar`.
  - 26 JVM unit tests: payload, geo codec, scorer, fallback chain, SMS text, JSON.
  - 15 backend tests.
  - An end-to-end run of the Kotlin HTTP client against the live Flask server: SOS → relay → live location merged into one event, and safe-places → scorer.
- **Not verified:** the full Gradle/aapt build (no Android SDK in this environment) and anything on real
  radios. Expect the first Android Studio sync to need a small fix or two.

## Known limits
- `SEND_SMS` is a Play-restricted permission. That's fine for sideloading; a Play release needs the emergency-SMS exception or a server-side SMS gateway.
- `LocationService` must start while the app is in the foreground (an Android 12+ rule). That's satisfied when the SOS comes from a button tap; a classifier firing in the background would need a different trigger.
- BLE security: packets are unauthenticated and unencrypted, so anyone nearby can read the location or spoof an SOS. Signing them is the next step before real use.
- The 16-bit UUID 0xFDA1 is not SIG-registered. Use a 128-bit UUID (needs BLE 5 extended adverts for the space) or register one.
- Per the blueprint's risk notes, the classifier score is never shown to the user. It only picks SOS vs Safe Exit.
