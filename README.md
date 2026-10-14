<div align="center">

# 🛡️ Guardian Mesh

### An SOS that gets out — with internet, without internet, without signal at all.

[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC84?style=flat-square&logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-7F52FF?style=flat-square&logo=kotlin&logoColor=white)](#)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-4285F4?style=flat-square&logo=jetpackcompose&logoColor=white)](#)
[![Backend](https://img.shields.io/badge/backend-Flask-000000?style=flat-square&logo=flask&logoColor=white)](#)
[![Tests](https://img.shields.io/badge/tests-15%20backend%20%C2%B7%2026%20core%20%C2%B7%2020%20app-0F7B3F?style=flat-square)](#whats-verified-and-whats-not)
[![Version](https://img.shields.io/badge/version-0.3--app-3A3F8F?style=flat-square)](#)

**A women's safety Android app built around one stubborn idea: the alert has to leave the phone
even when the phone has nothing — no data, no signal, no one to call.**

</div>

---

## Why this exists

Most safety apps fail in exactly the moment they are needed. They assume a working data
connection, a free hand, and a user who can look at a screen and tap a confirmation dialog.
A basement car park, a village with one bar of signal, a phone face-down in a bag, a hand
being held — any one of those is enough.

So Guardian Mesh is built from the failure cases backwards:

| If this is true | the app still |
|---|---|
| No internet | texts your trusted contacts your location over SMS |
| No signal either | broadcasts over Bluetooth to nearby phones, which relay it on |
| You can't look at the screen | takes a power-button, shake or volume gesture from a locked phone |
| Someone is watching you unlock it | opens as a calculator; a second PIN sends a silent SOS |
| Nobody you know is close enough to help | screams in two languages at the people who are |

---

## How an SOS travels

```
             ┌──────────────────────────────────────────────┐
             │  SOS raised: button · power ×3 · shake ·     │
             │  volume up+down · duress PIN · classifier    │
             └───────────────────────┬──────────────────────┘
                                     │
                 ┌───────────────────▼────────────────────┐
                 │       SosDispatcher.send()             │
                 │    (fallback chain, stops at first ✓)  │
                 └───────────────────┬────────────────────┘
                                     │
   ┌─────────────────┬───────────────┴───────────┬───────────────────────┐
   ▼                 ▼                           ▼                       │
 ① HTTP            ② SMS                      ③ BLE mesh                │
 POST /sos      trusted contacts          19-byte advert, TTL 3          │
 + live GPS     get a maps link                  │                       │
 every 10 s     (max 1 per 5 min)                │                       │
                                                 ▼                       │
                              ┌──────────────────────────────────┐       │
                              │ any phone running Guardian Mesh  │       │
                              │  ├─ has internet → POST /relay   │       │
                              │  └─ no internet  → rebroadcast,  │       │
                              │                    TTL − 1       │───────┘
                              └──────────────────────────────────┘
```

Every route carries the **same `eventId`** (`<deviceShortId hex>-<epoch hex>`), so a direct
POST, a relayed BLE copy and later location pushes all merge into **one incident** on the
server instead of three.

While the SOS is live, a foreground service pushes the location every 10 s. If the internet
drops mid-incident, points are buffered, the BLE advert is refreshed with the newest fix, and
contacts get an SMS update at most every 5 minutes.

---

## The app

Eight things, in the order they matter.

| # | Screen / feature | What it does |
|---|---|---|
| 1 | **Home dashboard** | One enormous SOS button, plus an honest readiness panel — internet, location, how many contacts will actually be texted, whether the mesh and the gestures are armed. No green ticks for things that aren't working. |
| 2 | **One-action trigger** | **Power button ×3**, **shake**, or **volume up+down** raises an SOS from a locked phone in a pocket. Runs in a foreground service so MIUI and One UI can't quietly kill it. |
| 3 | **SOS screen** | Shows the real delivery outcome, route by route, in plain words — "no internet, so your contacts were texted". Then offers Safe Exit, the alarm, or "I'm safe". |
| 4 | **Public Deterrent Mode** | Full-screen flashing red **HELP / मदद करें**, looping alarm on the alarm stream at max volume (ignores silent mode), vibration, camera-flash strobe. Works over the lock screen. Stopping it takes a deliberate 2-second hold. |
| 5 | **Duress PIN** | The app opens as a **working calculator**. The real PIN opens the app. A second PIN silently fires an SOS and keeps on calculating — no sound, no notification, no visible change. |
| 6 | **Contacts & settings** | Trusted contacts in Room, reorderable, written straight through to the SMS channel. Every switch states its cost — battery, a notification, the volume keys behaving differently. |
| 7 | **"I'm being followed"** | Feeds `FollowingClassifier` and opens either the SOS or the Safe Exit flow. **The score is never shown** — see [below](#the-classifier-never-shows-a-score). |
| 8 | **Guardian Mesh** | The v0.2 BLE relay as a screen: relay for others, live packet counters, a raw-scan diagnostic. Also the automatic last resort when there's no internet. |

### The classifier never shows a score

`FollowingClassifier` computes a 0–100 score from six weighted signals, with a safety override
that escalates to HIGH the moment someone is both very close **and** matching her path. The UI
does not display that number, does not round it, and does not say "medium risk".

That is a deliberate product decision, not an oversight. A number would be read as a verdict,
and six hand-tuned weights are not evidence about anyone's safety. Shown as "38% risk" it would
either talk a frightened person out of an instinct that was correct, or lend false authority to
a guess about a stranger. So the classifier is allowed to do exactly one thing: **pick which
screen opens next.** Her own judgement stays the final word — "something feels wrong" is itself
one of the six inputs, and the SOS button is one tap away regardless of what the rules conclude.

---

## Repository layout

```
guardian-relay/
├── android/                 Android app (Kotlin)
│   └── app/src/main/java/com/guardianmesh/relay/
│       │
│       │   ── the v0.2 core: framework-only, zero third-party libraries ──
│       ├── sos/             SosDispatcher, SosEvent, FallbackChain, Channels (HTTP/SMS/BLE)
│       ├── ble/             RelaySignal (19-byte payload), GeoCodec, advertiser,
│       │                    scanner, MeshRelay, RelayService
│       ├── location/        LocationService (FGS, 10 s), LocationProvider, GeoFix
│       ├── safeexit/        SafeExit, SafePlaceScorer, MapsLauncher, SafePlacesParser
│       ├── net/             BackendClient (HttpURLConnection)
│       ├── diag/            AppLog, Diagnostics, Permissions
│       ├── FollowingClassifier.kt
│       ├── Guardian.kt      init + SharedPreferences config
│       ├── MainActivity.kt   v0.2 two-phone debug console (kept, unchanged)
│       │
│       │   ── the v0.3 app layer: Compose, Room, Retrofit ──
│       ├── ui/
│       │   ├── LaunchActivity.kt      routes to the lock or the app
│       │   ├── HomeActivity.kt        NavHost over the six screens
│       │   ├── AppViewModel.kt        one ViewModel for the whole graph
│       │   ├── theme/ common/         Material 3 theme, shared components
│       │   └── home/ sos/ followed/ mesh/ contacts/ settings/
│       ├── trigger/         TriggerService + power / shake / volume detectors, BootReceiver
│       ├── deterrent/       DeterrentActivity, AlarmSiren
│       ├── duress/          CalculatorLockActivity, PinVault
│       ├── data/            Room: TrustedContact, AppSettings, DAOs, GuardianRepository
│       └── net/api/         Retrofit: ApiConfig (BASE_URL), GuardianApi, DTOs, ApiProvider
│
├── backend/                 Flask: SOS intake + merge, live location, safe places, dashboard
├── classifier/              Standalone Python copy of FollowingClassifier
└── README.md
```

### One rule worth keeping

**The SOS core has no third-party dependencies and the app layer does not change that.**
Nothing in `sos/`, `ble/`, `location/`, `safeexit/`, `net/` or `diag/` imports Compose, Room or
Retrofit. That is what keeps the fallback chain small, fast, unit-testable on a plain JVM, and
droppable into any other UI.

The two layers meet in exactly one place: `data/GuardianRepository.kt` mirrors the user's
contacts and settings from Room into `Guardian.config` (SharedPreferences), because a service
the system restarts in a fresh process reads them before any UI exists.

---

## Quick start

### 1. Backend

```bash
cd backend
pip install -r requirements.txt
python server.py                       # http://0.0.0.0:5000, dashboard at /
python -m unittest test_server -v      # 15 tests
```

Note your laptop's LAN IP (`ipconfig` / `ip addr`) — the phones need it.

### 2. App

```bash
cd android
./gradlew assembleDebug                # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest            # core + app-layer JVM tests
```

Open `android/` in Android Studio (Jellyfish or newer), or build from the CLI. Requires JDK 17,
Android SDK 34.

### 3. Point the app at the backend

Either set it once in code — `net/api/ApiConfig.kt`:

```kotlin
const val BASE_URL = "http://10.0.2.2:5000/"   // 10.0.2.2 = your laptop, from the emulator
```

…or, on a real phone, change it in **Settings → Guardian server** (`http://192.168.1.5:5000`).
The constant is the compiled-in default; the setting overrides it per request, because a demo
venue's LAN IP changes and a rebuild in the middle of a demo is not an option.

### 4. First launch

Grant everything and pick **Precise** location. Then add at least one trusted contact — without
one, an offline SOS has only the Bluetooth mesh to fall back on.

---

## Using the API from your own screens

The whole core is callable in a few lines. `MainActivity` (the debug console) and every v0.3
screen go through exactly this surface and hold no logic of their own.

```kotlin
// once, in Application.onCreate()  — GuardianApp already does this
Guardian.init(this)

// ── raise an SOS ──  (every entry point funnels through SosTrigger)
SosTrigger.fire(context, SosTrigger.Source.BUTTON)
SosTrigger.fire(context, SosTrigger.Source.DURESS_PIN, silent = true)   // nothing on screen
SosTrigger.stop(context)                                                // "I'm safe"

// ── or the core directly, for the outcome ──
SosDispatcher.send(SosEvent.create(location)) { outcome ->
    outcome.deliveredVia      // "HTTP" | "SMS" | "BLE" | null
    outcome.attempts          // every route tried, and why it failed
}
SosDispatcher.addListener { state -> render(state) }

// ── Safe Exit: Maps walking navigation to the best open place nearby ──
SafeExit.start(fix) { result -> /* Navigating | FallbackSearch | Failed */ }

// ── Public Deterrent Mode ──
DeterrentActivity.start(context)

// ── make this phone a relay node ──
RelayService.start(context)

// ── classifier → flow (note: the score is never surfaced) ──
val r = FollowingClassifier.classify(input)
if (r.route == Route.SOS) SosTrigger.fire(context, Source.CLASSIFIER) else SafeExit.start(fix)
```

---

## How the hardware triggers actually work

These are the parts most likely to behave differently on your phone, so here is the mechanism
rather than a promise.

**Power button ×3.** An app cannot intercept `KEYCODE_POWER` — the system swallows it, by
design. What an app can see is the *effect*: each press toggles the screen and broadcasts
`ACTION_SCREEN_ON` / `ACTION_SCREEN_OFF`. Count three toggles inside 3 s and you have a triple
press, with no root and no accessibility service. Those two broadcasts can't be declared in a
manifest, which is why the listener is a foreground service. Many ROMs map *five* presses to
Android's own Emergency SOS; both coexist.

**Shake.** Three jolts above a g-force threshold within 1.5 s, at `SENSOR_DELAY_UI`. One jolt
is not enough — a phone gets jolted in a bag all day. Three sensitivity levels in Settings;
"Easy" will occasionally fire on a bike.

**Volume up + down.** With the screen off there is no Activity to receive `onKeyDown`. The
supported route is a `MediaSession` declaring *remote* volume, so Android sends presses to our
`VolumeProvider.onAdjustVolume` instead of changing a stream. Because that stops the rocker
changing media volume, it is **armed only while the screen is off** and released the moment it
comes on — which is exactly the pocket case. With the app open, `HomeActivity.onKeyDown` feeds
the same detector. If another app is actively playing media it may win the keys.

**What works from the background, honestly.** A gesture fires with the screen off and the app
not running. In that state Android won't let the app start an activity, and a `location`
foreground service usually can't start either (an Android 12+ rule). So the SOS goes out with
the last known fix — which needs no service — and a high-priority notification appears; the
**live 10-second trail starts when the SOS screen becomes visible**, which is the first moment
Android permits it. The alert is never dropped; only the trail waits.

---

## BLE payload v2 — 19 bytes

| Byte | Field |
|---|---|
| 0 | version (hi nibble = 2) · signalType (lo nibble) |
| 1 | severity (bits 0–1) · hasLocation (bit 2) |
| 2 | TTL (mesh hops left, starts at 3) |
| 3 | seq (bumped on each location refresh) |
| 4–5 | deviceShortId |
| 6–9 | epochSeconds (SOS start) |
| 10–12 | latitude, 24-bit fixed point (≤ 1.2 m error) |
| 13–15 | longitude, 24-bit fixed point (≤ 2.4 m error) |
| 16 | accuracy in m (255 = unknown) |
| 17–18 | fix time, seconds after start |

On air: 4 B (UUID list) + 4 B (service data header) + 19 B = **27 of the 31 bytes** a legacy
advert allows. Old 8-byte v1 packets still decode.

## Safe Exit scoring

```
score = 0.6 · e^(−d / 750 m)  +  0.4 · open        open = 1 · unknown = 0.4 · closed = 0
```

Hard rule on top: a place known to be **closed never beats** one that is open or unknown. A
locked door is not a refuge, however close. If the backend is unreachable or nothing is nearby,
Maps opens a "police station" search around her instead — so the button always does something.

## Backend API

| Endpoint | Purpose |
|---|---|
| `POST /sos` | Direct report from the victim's phone |
| `POST /relay` | Report from a relay phone (v1 payload still accepted) |
| `POST /sos/<id>/location` | `{event, points[]}` — creates the event if needed (it went out via SMS/BLE) |
| `POST /sos/<id>/resolve` | Ends the SOS |
| `GET /sos/<id>` | Event with its full location trail |
| `GET /events` | Last 50 events (JSON) |
| `GET /health` | Health check |
| `GET /safe-places/nearby?lat=&lng=&radius=` | Places by distance, with `open_now` computed in IST |
| `POST /safe-places` | Add a curated place |
| `GET /` | Live dashboard |

**Safe places data.** There is no real dataset yet. Real entries go in `safe_places.json`
(format: `safe_places.example.json`). Until then `DEMO_SYNTHETIC_PLACES=1` (the default)
generates stable places labelled **"(demo)"** around the caller, and the app says so on screen
rather than passing them off as real. Set it to `0` to serve only real data. A Google Places or
OSM Overpass lookup slots in here.

---

## Two-phone BLE test

Install the APK on both phones, grant everything, choose **Precise** location. On each phone
open **Settings → Developer → Open debug console** and tap **Run diagnostics**, then fix every
❌ and ⚠️ it lists.

Battery and background settings matter most:

- **Xiaomi / Redmi / POCO** — Autostart **ON**, battery saver → **No restrictions**, lock the app in Recents
- **Samsung** — add the app to **Never sleeping apps**
- Both — tap **Fix: battery** and allow

**Phone B (relay):** set the backend to `http://<laptop-LAN-IP>:5000`, Save → **Start relay**.
The status line shows `Scanner ON: … SOS pkts, n/10s, last …s ago, RSSI …`.

**Phone A (sender):** airplane mode on, Bluetooth back on, backend URL blank. Tap **TRIGGER
SOS**. Expect:

```
HTTP: skipped (no network)
SMS: FAILED (… RADIO_OFF (airplane mode?))         ← or skipped if no contacts
BLE: OK (broadcasting SOS sev=2 id=… seq=0 ttl=3 26.21830,78.18280 ±12m)
[BLE-ADV] [own] advertising 19B payload …
```

Phone B should log `Heard SOS over BLE (RSSI -55 …)` then `Forwarded … to backend (HTTP 200)`,
and the dashboard shows the event with a 📍 Maps link.

**Is pickup reliable?** Phone A advertises every ~100 ms. At 1–2 m, B should read roughly
**5–10 packets/10 s or more**. If it reads 0:

- Tap **Raw scan test** on B. If `devices` climbs but `SOS pkts` stays 0, A isn't advertising — check A's log for `advertising FAILED: …`, which names the error.
- If both stay 0, B's scanning is blocked (permission, Bluetooth, or the Location toggle on Xiaomi / Android ≤ 11).
- If B logs `No SOS adverts for 15s` when A's screen turns off, A's ROM is killing it — apply the battery fixes above.

**Background test:** on both phones press Home, lock the screen, wait 2 minutes. The counters
should keep climbing.

**Gesture test:** lock Phone A, press power 3× quickly. Within a few seconds the SOS
notification should appear and Phone B / the dashboard should show the event.

---

## What's verified, and what's not

**Verified:**

- 15 backend tests pass (`python -m unittest test_server`).
- The standalone Python classifier runs and routes its sample cases correctly (SOS vs Safe Exit).
- All backend JSON shapes used by the Retrofit DTOs were read off a **live server**, not guessed.
- Every cross-file import in the Android sources resolves, and all 57 Kotlin files are brace-balanced (static check).
- v0.2's own verification still stands: the core compiled against the API-34 `android.jar`, 26 JVM unit tests (payload, geo codec, scorer, fallback chain, SMS text, JSON), and an end-to-end run of the Kotlin HTTP client against the live Flask server.

**Not verified — be aware before you demo:**

- ⚠️ **The v0.3 app layer has never been compiled.** There was no Android SDK and no access to Google's or Maven's repositories in the environment it was written in, so Compose, Room and Retrofit were never resolved and `kotlinc` never ran over `ui/`, `trigger/`, `deterrent/`, `duress/`, `data/` or `net/api/`. **Expect the first Android Studio sync to need a few fixes** — most likely an import or a Material 3 parameter name.
- The 20 new app-layer JVM tests in `AppLayerTest.kt` are written but have not been run.
- Nothing has been tested on real radios: no BLE hop, no SMS, no gesture on a physical phone.
- Room's generated code, the KSP setup and the AndroidX version matrix are all unexercised.

---

## Known limits

- **`SEND_SMS` is Play-restricted.** Fine for sideloading. A Play release needs the emergency-SMS policy exception or a server-side SMS gateway.
- **BLE packets are unauthenticated and unencrypted.** Anyone nearby can read a broadcast location or spoof an SOS. Signing them is the next piece of work before real use.
- **The duress PIN is cover, not encryption.** Salted SHA-256 × 12,000 rounds, never plaintext — but a 4–8 digit PIN has too little entropy to survive offline brute force from a rooted phone. It defeats someone looking over your shoulder. Real hardening belongs in the Keystore.
- **UUID `0xFDA1` is not SIG-registered.** Use a 128-bit UUID (needs BLE 5 extended adverts for the space) or register one.
- **`usesCleartextTraffic` is on** for the `http://` LAN demo backend. Remove it and use HTTPS before any release.
- **A background classifier trigger can't start location tracking** — same Android 12+ rule as the gestures.
- **Destructive Room migration** (`fallbackToDestructiveMigration`) — fine for a dev build, replace with real migrations before anyone stores contacts they care about.
- **The deterrent flash runs a bit over 1 Hz**, deliberately well below the ~3 Hz photosensitive-seizure guidance. A faster strobe would look more urgent and be genuinely unsafe for some people.

---

## Changelog

### v0.3 — the app
- Compose + Material 3 UI: Home, SOS, "I'm being followed", Guardian Mesh, Contacts, Settings
- One-action triggers: power ×3, shake, volume up+down, in a `specialUse` foreground service, re-armed on boot
- Public Deterrent Mode: bilingual full-screen alarm, alarm-stream audio, vibration, torch strobe
- Duress PIN behind a working calculator decoy
- Room for contacts and settings, mirrored into the core's SharedPreferences config
- Retrofit for the UI layer, single `BASE_URL`, runtime host override for LAN demos
- The v0.2 debug console kept intact, reachable from Settings → Developer

### v0.2 — bugs fixed from v0.1
- `http://` to the LAN backend was blocked on targetSdk 34 (cleartext), so Phone B's forward always failed
- Advertising and scanning ran from the Activity, so MIUI and One UI killed them in the background — now foreground services
- More than 5 scan start/stops in 30 s made Android silently return nothing — restarts are now rate-limited per app
- Scans older than 30 min were silently downgraded — now auto-restart every 25 min, and on Bluetooth re-enable
- The de-dupe set grew forever, and a failed POST meant the event was never re-forwarded
- `LocationListener` is now fully implemented — a missing `onStatusChanged` crashes Android ≤ 10

---

<div align="center">

Built for a hackathon in Jaipur · part of the [Genzis](https://github.com/Genzis-Generations-Ahead) stack

**If you take one thing from this repo:** the fallback chain, not the UI. Everything else is
replaceable.

</div>
