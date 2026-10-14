"""
Rule-based stub for "am I being followed?" triage — Python version.

Same logic as the Kotlin copy wired into the Android app
(android/app/.../FollowingClassifier.kt). This standalone copy exists so you
can demo/tweak the decision logic on a laptop in seconds, without touching
Android Studio — useful for Thursday if you just need to show the reasoning,
not the phones.

Mock input -> severity level -> routes to SOS or Safe Exit.
"""

from dataclasses import dataclass, field
from enum import Enum


class Severity(Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"


class Route(Enum):
    SAFE_EXIT = "SAFE_EXIT"
    SOS = "SOS"


@dataclass
class FollowingInput:
    minutes_same_stranger_nearby: int      # how long the same unknown person has stayed close
    times_seen_across_locations: int       # e.g. seen at the bus stop AND outside home
    distance_meters: float                 # current distance to that person
    is_night_time: bool
    area_safety_score: int                 # 0 (unsafe) .. 10 (safe)
    stranger_altered_path_to_match: bool   # they changed direction/speed when she did
    user_manually_flagged_unsafe: bool     # she tapped "this feels wrong" herself


@dataclass
class ClassificationResult:
    score: int
    severity: Severity
    route: Route
    reasons: list = field(default_factory=list)


def classify(inp: FollowingInput) -> ClassificationResult:
    score = 0
    reasons = []

    # Rule 1: proximity in time is the strongest single signal.
    if inp.minutes_same_stranger_nearby >= 10:
        score += 30
        reasons.append(f"Same stranger nearby for {inp.minutes_same_stranger_nearby}+ min (+30)")
    elif inp.minutes_same_stranger_nearby >= 5:
        score += 15
        reasons.append(f"Same stranger nearby for {inp.minutes_same_stranger_nearby} min (+15)")

    # Rule 2: same person across unrelated locations is a strong pattern signal.
    if inp.times_seen_across_locations >= 2:
        score += 25
        reasons.append(f"Seen at {inp.times_seen_across_locations} different locations today (+25)")

    # Rule 3: closing distance matters more than static distance.
    if inp.distance_meters <= 3.0:
        score += 20
        reasons.append(f"Currently very close ({inp.distance_meters}m) (+20)")
    elif inp.distance_meters <= 8.0:
        score += 10
        reasons.append(f"Currently within {inp.distance_meters}m (+10)")

    # Rule 4: mirroring behaviour.
    if inp.stranger_altered_path_to_match:
        score += 20
        reasons.append("Stranger appeared to alter path/speed to match hers (+20)")

    # Rule 5: context modifiers.
    if inp.is_night_time:
        score += 10
        reasons.append("Night time (+10)")
    if inp.area_safety_score <= 3:
        score += 10
        reasons.append(f"Low area safety score: {inp.area_safety_score}/10 (+10)")

    # Rule 6: the user's own judgement is a first-class signal.
    if inp.user_manually_flagged_unsafe:
        score += 15
        reasons.append("User manually flagged the situation as unsafe (+15)")

    score = max(0, min(100, score))

    if score >= 60:
        severity = Severity.HIGH
    elif score >= 30:
        severity = Severity.MEDIUM
    else:
        severity = Severity.LOW

    # Safety-critical override: very close + path-matching escalates to HIGH
    # immediately, without waiting for the cumulative score to cross 60.
    forced_high = inp.distance_meters <= 3.0 and inp.stranger_altered_path_to_match
    if forced_high and severity != Severity.HIGH:
        reasons.append("Override: close distance + path-matching forces HIGH regardless of score")
        severity = Severity.HIGH

    route = Route.SOS if severity == Severity.HIGH else Route.SAFE_EXIT

    return ClassificationResult(score=score, severity=severity, route=route, reasons=reasons)


if __name__ == "__main__":
    scenarios = {
        "Nothing unusual": FollowingInput(0, 0, 15.0, False, 8, False, False),
        "Mildly uneasy, daytime": FollowingInput(4, 1, 6.0, False, 6, False, False),
        "Following pattern building, night": FollowingInput(6, 2, 5.0, True, 4, False, False),
        "Clear high-severity case": FollowingInput(12, 2, 2.0, True, 2, True, False),
        "Override case (close + mirroring, low prior score)": FollowingInput(2, 0, 2.5, False, 7, True, False),
        "User manually flags it": FollowingInput(1, 0, 10.0, False, 7, False, True),
    }

    for name, scenario in scenarios.items():
        result = classify(scenario)
        print(f"\n=== {name} ===")
        print(f"  score={result.score}  severity={result.severity.value}  route={result.route.value}")
        for r in result.reasons:
            print(f"    - {r}")
