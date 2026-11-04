package com.vansaska.wattbike.core

import kotlin.math.roundToInt

/** An easier alternative for the rest of a ride. Segments start at 0 and fill the remaining time exactly. */
data class RouteOption(
    val title: String,
    val description: String,
    val segments: List<Segment>,
) {
    val totalSeconds: Int get() = segments.sumOf { it.duration }
}

/**
 * "Take a different route": given how far through the plan you are, offers easier ways
 * to fill the remaining time. All options end at the same time as the original plan.
 */
object RouteGenerator {
    const val WORK_THRESHOLD = 65.0
    const val EASIER_FACTOR = 0.88
    const val ENDURANCE_PCT = 62.0
    const val EASY_PCT = 50.0

    fun options(plan: Plan, elapsed: Int): List<RouteOption> {
        val rest = plan.remainingFrom(elapsed)
        val total = rest.sumOf { it.duration }
        if (total < 60) return emptyList()

        val startPct = rest.first().fromPct
        if (total <= 240) {
            return listOf(
                RouteOption(
                    "Cool down now",
                    "Ease off gradually to the end.",
                    SegmentBuilder().add(total, minOf(startPct, 60.0), 40.0, "Cool down").build(),
                ),
            )
        }

        val out = mutableListOf<RouteOption>()
        val hasWork = rest.any { it.maxPct > WORK_THRESHOLD }

        if (hasWork) {
            out += RouteOption(
                "Same plan, easier",
                "The rest of the workout with every effort at ${(EASIER_FACTOR * 100).roundToInt()}% of its planned power. Recoveries unchanged.",
                rest.map { s ->
                    s.copy(fromPct = easier(s.fromPct), toPct = easier(s.toPct))
                },
            )
        }

        val hardEfforts = rest.filter { it.avgPct > 75 && it.duration >= 40 }
        if (hardEfforts.isNotEmpty()) {
            val b = SegmentBuilder()
            for (s in rest) {
                if (s.avgPct > 75 && s.duration >= 40) {
                    val first = roundTo5(s.duration / 2)
                    val midPct = s.pctAt((s.start + first).toDouble())
                    b.add(first, s.fromPct, midPct, s.label, s.cadence)
                    b.add(s.duration - first, EASY_PCT, EASY_PCT, "Recover")
                } else {
                    b.add(s.duration, s.fromPct, s.toPct, s.label, s.cadence)
                }
            }
            out += RouteOption(
                "Shorter efforts",
                "Same power targets, but each remaining effort is cut in half and the rest of it becomes recovery.",
                mergeAdjacent(b.build()),
            )
        }

        val cooldown = cooldownFor(rest, total)
        val mainLen = total - cooldown.sumOf { it.duration }
        out += RouteOption(
            "Steady endurance",
            "Steady riding at ${ENDURANCE_PCT.roundToInt()}% FTP, then cool down.",
            SegmentBuilder().add(mainLen, ENDURANCE_PCT, ENDURANCE_PCT, "Endurance").build()
                .let { appendShifted(it, cooldown) },
        )

        val easyCd = genericCooldown(total, EASY_PCT)
        out += RouteOption(
            "Easy spin to finish",
            "Very easy pedalling at ${EASY_PCT.roundToInt()}% FTP until the end.",
            SegmentBuilder()
                .add(total - easyCd, EASY_PCT, EASY_PCT, "Easy spin")
                .add(easyCd, EASY_PCT, 40.0, "Cool down")
                .build(),
        )
        return out
    }

    private fun easier(p: Double): Double =
        if (p <= WORK_THRESHOLD) p else maxOf(WORK_THRESHOLD, p * EASIER_FACTOR)

    private fun roundTo5(s: Int): Int = (s / 5) * 5

    /** Keeps the plan's own cool-down if it ends with one, otherwise makes a generic one. */
    private fun cooldownFor(rest: List<Segment>, total: Int): List<Segment> {
        val last = rest.last()
        if (last.toPct < last.fromPct && last.duration <= total / 2) {
            return listOf(last.copy(start = 0, fromPct = minOf(last.fromPct, ENDURANCE_PCT)))
        }
        val len = genericCooldown(total, ENDURANCE_PCT)
        return SegmentBuilder().add(len, ENDURANCE_PCT, 40.0, "Cool down").build()
    }

    private fun genericCooldown(total: Int, @Suppress("UNUSED_PARAMETER") from: Double): Int {
        // Short on purpose: a couple of easy minutes is enough after these efforts.
        val len = (total / 6).coerceIn(90, 150)
        return (len / 30) * 30
    }

    private fun appendShifted(a: List<Segment>, b: List<Segment>): List<Segment> {
        val out = a.toMutableList()
        var t = a.lastOrNull()?.end ?: 0
        for (s in b) {
            out += s.shifted(t)
            t += s.duration
        }
        return out
    }

    /** Joins neighbouring steady segments with the same power and label. */
    private fun mergeAdjacent(segs: List<Segment>): List<Segment> {
        val out = mutableListOf<Segment>()
        for (s in segs) {
            val prev = out.lastOrNull()
            if (prev != null && !prev.isRamp && !s.isRamp && prev.toPct == s.fromPct && prev.label == s.label && prev.cadence == s.cadence) {
                out[out.lastIndex] = prev.copy(duration = prev.duration + s.duration)
            } else {
                out += s
            }
        }
        return out
    }
}
