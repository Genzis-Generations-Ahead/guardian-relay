package com.guardianmesh.relay

/**
 * Rule-based stub for "am I being followed?" triage.
 *
 * This is deliberately NOT machine learning — it's a transparent, tunable
 * rule set so it's easy to explain in a demo and easy to swap thresholds
 * as we get real data. Mock input in, severity + a routing decision out.
 *
 * Routing:
 *   HIGH severity   -> SOS            (Public Deterrent Mode / direct escalation)
 *   MEDIUM severity -> SAFE_EXIT      (reroute away from the situation, stay alert)
 *   LOW severity    -> SAFE_EXIT      (same route, but framed as a mild nudge, no alarm)
 *
 * Per the blueprint's key risk notes: this never shows a "your risk score" verdict
 * to the user as fact — severity only decides which flow the app opens next.
 */
object FollowingClassifier {

    enum class Severity { LOW, MEDIUM, HIGH }
    enum class Route { SAFE_EXIT, SOS }

    /**
     * Mock sensor/context input. In a real build these would come from GPS,
     * accelerometer/gait-matching, SafetiPin-style area scores, and manual
     * "I feel like I'm being followed" taps — here they're just plain fields
     * so the rules can be tested without any hardware.
     */
    data class FollowingInput(
        val minutesSameStrangerNearby: Int,   // how long the same unknown person has stayed close
        val timesSeenAcrossLocations: Int,    // e.g. seen at the bus stop AND outside home
        val distanceMeters: Double,           // current distance to that person
        val isNightTime: Boolean,
        val areaSafetyScore: Int,             // 0 (unsafe) .. 10 (safe), e.g. from SafetiPin-style data
        val strangerAlteredPathToMatch: Boolean, // they changed direction/speed when she did
        val userManuallyFlaggedUnsafe: Boolean   // she tapped "this feels wrong" herself
    )

    data class ClassificationResult(
        val score: Int,              // 0..100, internal only — never surfaced as a literal "risk %" to the user
        val severity: Severity,
        val route: Route,
        val reasons: List<String>    // human-readable rule hits, useful for logs/demo narration
    )

    fun classify(input: FollowingInput): ClassificationResult {
        var score = 0
        val reasons = mutableListOf<String>()

        // Rule 1: proximity in time is the strongest single signal.
        if (input.minutesSameStrangerNearby >= 10) {
            score += 30
            reasons.add("Same stranger nearby for ${input.minutesSameStrangerNearby}+ min (+30)")
        } else if (input.minutesSameStrangerNearby >= 5) {
            score += 15
            reasons.add("Same stranger nearby for ${input.minutesSameStrangerNearby} min (+15)")
        }

        // Rule 2: seeing the same person across unrelated locations is a strong pattern signal.
        if (input.timesSeenAcrossLocations >= 2) {
            score += 25
            reasons.add("Seen at ${input.timesSeenAcrossLocations} different locations today (+25)")
        }

        // Rule 3: closing distance matters more than static distance.
        if (input.distanceMeters <= 3.0) {
            score += 20
            reasons.add("Currently very close (${input.distanceMeters}m) (+20)")
        } else if (input.distanceMeters <= 8.0) {
            score += 10
            reasons.add("Currently within ${input.distanceMeters}m (+10)")
        }

        // Rule 4: mirroring behaviour (turned when she turned, sped up when she sped up).
        if (input.strangerAlteredPathToMatch) {
            score += 20
            reasons.add("Stranger appeared to alter path/speed to match hers (+20)")
        }

        // Rule 5: context modifiers.
        if (input.isNightTime) {
            score += 10
            reasons.add("Night time (+10)")
        }
        if (input.areaSafetyScore <= 3) {
            score += 10
            reasons.add("Low area safety score: ${input.areaSafetyScore}/10 (+10)")
        }

        // Rule 6: the user's own judgement is a first-class signal, not an afterthought.
        if (input.userManuallyFlaggedUnsafe) {
            score += 15
            reasons.add("User manually flagged the situation as unsafe (+15)")
        }

        score = score.coerceIn(0, 100)

        val severity = when {
            score >= 60 -> Severity.HIGH
            score >= 30 -> Severity.MEDIUM
            else -> Severity.LOW
        }

        // Safety-critical override: even a modest score gets escalated straight to SOS
        // the moment the stranger is both very close AND matching her path. Waiting for
        // the cumulative score to cross 60 in that specific case is too slow to be useful.
        val forcedHighRisk = input.distanceMeters <= 3.0 && input.strangerAlteredPathToMatch
        val finalSeverity = if (forcedHighRisk && severity != Severity.HIGH) {
            reasons.add("Override: close distance + path-matching forces HIGH regardless of score")
            Severity.HIGH
        } else severity

        val route = if (finalSeverity == Severity.HIGH) Route.SOS else Route.SAFE_EXIT

        return ClassificationResult(score, finalSeverity, route, reasons)
    }
}
