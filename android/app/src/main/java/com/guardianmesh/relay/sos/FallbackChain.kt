package com.guardianmesh.relay.sos

/**
 * One way to get an SOS out. Implementations block (they run on SosDispatcher's
 * worker thread) and must never throw — return a failed [DeliveryResult] instead.
 */
interface SosChannel {
    val name: String
    /** null = usable right now; otherwise a human-readable reason it's skipped (no network, no contacts, BT off…). */
    fun unavailableReason(): String?
    fun deliver(event: SosEvent): DeliveryResult
}

data class DeliveryResult(val success: Boolean, val detail: String) {
    companion object {
        fun ok(detail: String) = DeliveryResult(true, detail)
        fun fail(detail: String) = DeliveryResult(false, detail)
    }
}

data class Attempt(val channel: String, val skipped: Boolean, val result: DeliveryResult?, val tookMs: Long) {
    fun describe() = when {
        skipped -> "$channel: skipped (${result?.detail})"
        result!!.success -> "$channel: OK (${result.detail}) in ${tookMs}ms"
        else -> "$channel: FAILED (${result.detail}) after ${tookMs}ms"
    }
}

data class ChainOutcome(val eventId: String, val deliveredVia: String?, val attempts: List<Attempt>) {
    val delivered get() = deliveredVia != null
    fun summary() = if (delivered) "SOS $eventId delivered via $deliveredVia" else "SOS $eventId NOT delivered by any channel"
}

/**
 * Tries channels in order and stops at the first success:
 *     internet POST  →  SMS to trusted contacts  →  BLE mesh broadcast
 * [alsoRun] names channels that run even after an earlier success (e.g. BLE when
 * config.bleAlways is on — cheap extra coverage). Pure Kotlin, unit-tested.
 */
class FallbackChain(
    private val channels: List<SosChannel>,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun run(event: SosEvent, alsoRun: Set<String> = emptySet()): ChainOutcome {
        val attempts = mutableListOf<Attempt>()
        var deliveredVia: String? = null
        for (ch in channels) {
            if (deliveredVia != null && ch.name !in alsoRun) continue
            val reason = try { ch.unavailableReason() } catch (t: Throwable) { "availability check crashed: ${t.message}" }
            if (reason != null) {
                attempts += Attempt(ch.name, true, DeliveryResult.fail(reason), 0)
                log(attempts.last().describe())
                continue
            }
            val t0 = clock()
            val result = try { ch.deliver(event) } catch (t: Throwable) {
                DeliveryResult.fail("crashed: ${t.javaClass.simpleName}: ${t.message}")
            }
            attempts += Attempt(ch.name, false, result, clock() - t0)
            log(attempts.last().describe())
            if (result.success && deliveredVia == null) deliveredVia = ch.name
        }
        return ChainOutcome(event.eventId, deliveredVia, attempts)
    }
}
