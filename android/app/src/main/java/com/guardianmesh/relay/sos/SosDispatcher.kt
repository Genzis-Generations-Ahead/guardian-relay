package com.guardianmesh.relay.sos

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.guardianmesh.relay.Guardian
import com.guardianmesh.relay.ble.BleAdvertiserManager
import com.guardianmesh.relay.diag.AppLog
import com.guardianmesh.relay.location.GeoFix
import com.guardianmesh.relay.location.LocationProvider
import com.guardianmesh.relay.location.LocationService
import com.guardianmesh.relay.net.BackendClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Public API for Tanush's UI:
 *
 *     SosDispatcher.send(SosEvent.create(fix = fix)) { outcome -> showToast(outcome.summary()) }
 *     SosDispatcher.stop()                  // "I'm safe"
 *     SosDispatcher.addListener { state -> render(state) }
 *
 * send():
 *   1. marks the SOS active (persisted, survives process death) and starts LocationService
 *   2. attaches a location if the event has none (cached ≤2 min, else waits ≤3 s)
 *   3. runs the fallback chain: HTTP POST → SMS to trusted contacts → BLE mesh broadcast
 * then every 10 s while active (driven by LocationService):
 *   - online:  POST /sos/<id>/location (with any points buffered while offline)
 *   - BLE advert running: re-advertise with the new location (seq+1)
 *   - offline ≥30 s: start BLE broadcast if not running; SMS update to contacts at most every 5 min
 *   - nothing delivered yet: retry the whole chain every 30 s
 *
 * All work runs on one background thread, so calls are safe from the main thread
 * and never overlap. Listener/outcome callbacks are posted to the main thread.
 */
object SosDispatcher {

    private const val TAG = "SOS"
    private const val OFFLINE_TICKS_BEFORE_ESCALATION = 3
    private const val CHAIN_RETRY_MS = 30_000L
    private const val MAX_BUFFERED_POINTS = 60

    data class State(
        val event: SosEvent?,
        val deliveredVia: String?,
        val lastOutcome: ChainOutcome?,
        val bleBroadcasting: Boolean,
        val consecutivePushFailures: Int,
        val bufferedPoints: Int,
        val lastPushAtMs: Long
    ) {
        val active get() = event != null
    }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "sos-dispatcher").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    // ── state: only touched on the worker thread (except the @Volatile read of `active`) ──
    @Volatile private var active: SosEvent? = null
    private var seq = 0
    private var lastOutcome: ChainOutcome? = null
    private var lastChainAt = 0L
    private var pushFailures = 0
    private var lastSmsAt = 0L
    private var lastPushAt = 0L
    private val buffered = ArrayDeque<GeoFix>()

    private val ctx: Context get() = Guardian.context
    private val config get() = Guardian.config
    private val location by lazy { LocationProvider(ctx) }

    private val http by lazy { HttpSosChannel(ctx) { config.backendBaseUrl } }
    private val sms by lazy { SmsSosChannel(ctx, { config.trustedContacts }, { config.senderName }) }
    private val ble by lazy { BleMeshSosChannel(ctx, { Guardian.advertiser }, { ev -> ev.toSignal(seq) }) }
    private val chain by lazy { FallbackChain(listOf(http, sms, ble), log = { AppLog.i(TAG, it) }) }

    val isActive: Boolean get() = active != null
    val activeEvent: SosEvent? get() = active

    fun addListener(l: (State) -> Unit) { listeners.add(l); worker.execute { notifyState() } }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }

    /** Fire an SOS. Safe to call repeatedly: a second call while active just retries delivery with the newest fix. */
    fun send(event: SosEvent, onOutcome: ((ChainOutcome) -> Unit)? = null) {
        val c = ctx // fail fast on the caller's thread if Guardian.init() was never called
        worker.execute {
            val current = active
            var ev = if (current != null) {
                AppLog.w(TAG, "SOS ${current.eventId} already active → re-running delivery")
                if (event.fix != null && (current.fix == null || event.fix.timeMs > current.fix.timeMs)) current.withFix(event.fix) else current
            } else {
                resetState()
                AppLog.w(TAG, "SOS TRIGGERED ${event.eventId} (${event.type}, ${event.severity}, source=${event.source})")
                event
            }
            active = ev
            SosStore.save(c, ev, seq, lastSmsAt, false)
            LocationService.start(c)

            if (ev.fix == null || ev.fix!!.ageMs() > 2 * 60_000L) {
                val fix = location.lastKnown() ?: run {
                    AppLog.i(TAG, "No recent cached fix — waiting up to 3 s for one…")
                    location.currentFix(3_000)
                }
                if (fix != null) {
                    ev = ev.withFix(fix); active = ev
                    AppLog.i(TAG, "Attached location ${fix.latLngString()} ±${fix.accuracyM?.toInt() ?: "?"}m (${fix.provider}, ${fix.ageMs() / 1000}s old)")
                } else {
                    AppLog.w(TAG, "No location yet — sending without it; LocationService will push it as soon as there's a fix")
                }
            }

            val outcome = runChain(ev)
            onOutcome?.let { cb -> main.post { cb(outcome) } }
        }
    }

    /** End the SOS ("I'm safe"): stops tracking + BLE, tells the backend (best effort). */
    fun stop() {
        worker.execute {
            val ev = active ?: return@execute
            active = null
            AppLog.ok(TAG, "SOS ${ev.eventId} stopped by user")
            Guardian.advertiser.stop(BleAdvertiserManager.OWN_KEY)
            LocationService.stop(ctx)
            SosStore.clear(ctx)
            val base = config.backendBaseUrl
            if (base.isNotBlank() && BackendClient.hasNetwork(ctx)) {
                val r = BackendClient.postJson("$base/sos/${ev.eventId}/resolve", JSONObject())
                AppLog.i(TAG, "resolve → ${r.describe()}")
            }
            resetState()
            notifyState()
        }
    }

    // ───────────────────────── called by LocationService ─────────────────────────

    /** Every 10 s while active. [fix] = best fix so far (may be null if none yet). */
    internal fun onLocationTick(fix: GeoFix?) = worker.execute { tick(fix) }

    /** After the system restarts LocationService (START_STICKY) in a fresh process. */
    internal fun restore(c: Context): Boolean {
        val saved = SosStore.load(c) ?: return false
        worker.execute {
            if (active != null) return@execute
            active = saved.event
            seq = saved.seq
            lastSmsAt = saved.lastSmsAt
            lastChainAt = System.currentTimeMillis()
            AppLog.w(TAG, "Restored active SOS ${saved.event.eventId} after process restart")
            if (saved.bleActive) Guardian.advertiser.start(BleAdvertiserManager.OWN_KEY, saved.event.toSignal(++seq))
            notifyState()
        }
        return true
    }

    // ───────────────────────── internals (worker thread) ─────────────────────────

    private fun runChain(ev: SosEvent): ChainOutcome {
        val also = if (config.bleAlways) setOf(ble.name) else emptySet()
        val outcome = chain.run(ev, alsoRun = also)
        lastOutcome = outcome
        lastChainAt = System.currentTimeMillis()
        if (outcome.attempts.any { it.channel == sms.name && it.result?.success == true }) lastSmsAt = lastChainAt
        if (outcome.delivered) AppLog.ok(TAG, outcome.summary()) else AppLog.e(TAG, outcome.summary() + " — will retry every 30 s")
        SosStore.save(ctx, ev, seq, lastSmsAt, Guardian.advertiser.isActive(BleAdvertiserManager.OWN_KEY))
        notifyState()
        return outcome
    }

    private fun tick(fix: GeoFix?) {
        var ev = active ?: return
        val now = System.currentTimeMillis()
        val isNewFix = fix != null && (ev.fix == null || fix.timeMs > ev.fix!!.timeMs)
        if (isNewFix) { ev = ev.withFix(fix!!); active = ev }

        // Nothing got out at all yet → keep retrying the full chain.
        if (lastOutcome?.delivered != true && now - lastChainAt >= CHAIN_RETRY_MS) {
            AppLog.w(TAG, "Retrying delivery chain (previous attempt failed)")
            runChain(ev)
            return
        }

        // 1) Internet: push the point (plus anything buffered while offline).
        val base = config.backendBaseUrl
        val point = ev.fix
        if (point != null) {
            if (base.isNotBlank() && BackendClient.hasNetwork(ctx)) {
                val pts = JSONArray()
                (buffered + point).distinctBy { it.timeMs }.forEach { p -> pts.put(JSONObject().also { SosEvent.putFix(it, p) }) }
                val body = JSONObject().put("event", ev.toJson()).put("points", pts)
                val r = BackendClient.postJson("$base/sos/${ev.eventId}/location", body)
                if (r.ok) {
                    if (pushFailures >= OFFLINE_TICKS_BEFORE_ESCALATION) AppLog.ok(TAG, "Back online — flushed ${pts.length()} location point(s)")
                    else AppLog.d(TAG, "live location pushed (${pts.length()} pt) ${point.latLngString()}")
                    buffered.clear(); pushFailures = 0; lastPushAt = now
                } else {
                    onPushFailed(point, r.describe())
                }
            } else {
                onPushFailed(point, if (base.isBlank()) "no backend URL" else "no network")
            }
        }

        // 2) BLE: keep the broadcast's location fresh; start it if we've been offline a while.
        val bleOn = Guardian.advertiser.isActive(BleAdvertiserManager.OWN_KEY)
        if (bleOn && isNewFix) {
            seq = (seq + 1) and 0xFF
            Guardian.advertiser.update(BleAdvertiserManager.OWN_KEY, ev.toSignal(seq))
        } else if (!bleOn && pushFailures >= OFFLINE_TICKS_BEFORE_ESCALATION && ble.unavailableReason() == null) {
            AppLog.w(TAG, "Offline for ${pushFailures * 10}s → starting BLE mesh broadcast")
            AppLog.i(TAG, "BLE: " + ble.deliver(ev).detail)
        }

        // 3) SMS update while offline, throttled.
        val every = config.smsUpdateIntervalMs
        if (pushFailures >= OFFLINE_TICKS_BEFORE_ESCALATION && every > 0 && now - lastSmsAt >= every &&
            sms.unavailableReason() == null && point != null
        ) {
            val r = sms.sendUpdate(ev)
            AppLog.i(TAG, "SMS location update: ${if (r.success) "sent" else "failed"} (${r.detail})")
            if (r.success) lastSmsAt = now
        }

        SosStore.save(ctx, ev, seq, lastSmsAt, Guardian.advertiser.isActive(BleAdvertiserManager.OWN_KEY))
        notifyState()
    }

    private fun onPushFailed(point: GeoFix, why: String) {
        pushFailures++
        if (buffered.lastOrNull()?.timeMs != point.timeMs) buffered.addLast(point)
        while (buffered.size > MAX_BUFFERED_POINTS) buffered.removeFirst()
        if (pushFailures == 1 || pushFailures % 6 == 0) {
            AppLog.w(TAG, "live location push failed ($why) — ${buffered.size} point(s) buffered")
        }
    }

    private fun resetState() {
        seq = 0; lastOutcome = null; lastChainAt = 0; pushFailures = 0; lastSmsAt = 0; lastPushAt = 0
        buffered.clear()
    }

    private fun notifyState() {
        val s = State(
            event = active,
            deliveredVia = lastOutcome?.deliveredVia,
            lastOutcome = lastOutcome,
            bleBroadcasting = Guardian.isInitialized && Guardian.advertiser.isActive(BleAdvertiserManager.OWN_KEY),
            consecutivePushFailures = pushFailures,
            bufferedPoints = buffered.size,
            lastPushAtMs = lastPushAt
        )
        main.post { listeners.forEach { runCatching { it(s) } } }
    }
}
