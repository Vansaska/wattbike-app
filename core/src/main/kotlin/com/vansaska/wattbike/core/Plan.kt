package com.vansaska.wattbike.core

import kotlin.math.roundToInt

/** A flattened piece of a workout, with absolute start time in seconds. Power is % of FTP. */
data class Segment(
    val start: Int,
    val duration: Int,
    val fromPct: Double,
    val toPct: Double,
    val label: String,
) {
    val end: Int get() = start + duration
    val isRamp: Boolean get() = fromPct != toPct
    val avgPct: Double get() = (fromPct + toPct) / 2
    val maxPct: Double get() = maxOf(fromPct, toPct)

    fun pctAt(t: Double): Double {
        if (duration <= 0) return fromPct
        val f = ((t - start) / duration).coerceIn(0.0, 1.0)
        return fromPct + (toPct - fromPct) * f
    }

    fun shifted(newStart: Int) = copy(start = newStart)
}

/** Builds a contiguous list of segments from (duration, from, to, label) pieces starting at 0. */
class SegmentBuilder {
    private val out = mutableListOf<Segment>()
    private var t = 0

    fun add(duration: Int, fromPct: Double, toPct: Double = fromPct, label: String? = null): SegmentBuilder {
        if (duration <= 0) return this
        out += Segment(t, duration, fromPct, toPct, label ?: defaultLabel(fromPct, toPct))
        t += duration
        return this
    }

    fun build(): List<Segment> = out.toList()
}

fun defaultLabel(fromPct: Double, toPct: Double): String = when {
    toPct > fromPct + 0.5 -> "Ramp up"
    toPct < fromPct - 0.5 -> "Ramp down"
    else -> Zone.of(fromPct).label
}

fun Workout.toSegments(): List<Segment> {
    val b = SegmentBuilder()
    fun addSteps(steps: List<Step>, suffix: String) {
        for (s in steps) {
            when (s.kind) {
                StepKind.STEADY -> {
                    val p = (s.power ?: 50).toDouble()
                    b.add(s.duration, p, p, s.label?.let { it + suffix } ?: (defaultLabel(p, p) + suffix))
                }
                StepKind.RAMP -> {
                    val f = (s.from ?: s.power ?: 50).toDouble()
                    val to = (s.to ?: s.power ?: f.toInt()).toDouble()
                    b.add(s.duration, f, to, s.label?.let { it + suffix } ?: (defaultLabel(f, to) + suffix))
                }
                StepKind.REPEAT -> {
                    val n = s.times ?: 1
                    for (i in 1..n) addSteps(s.steps ?: emptyList(), if (n > 1) " ($i/$n)" else "")
                }
            }
        }
    }
    addSteps(steps, "")
    return b.build()
}

/** The workout as it will actually be ridden. Can be rerouted part-way through. */
class Plan(val segments: List<Segment>) {
    val totalSeconds: Int get() = segments.lastOrNull()?.end ?: 0

    fun indexAt(t: Double): Int {
        if (segments.isEmpty() || t >= totalSeconds) return -1
        val i = segments.indexOfFirst { t < it.end }
        return i
    }

    fun segmentAt(t: Double): Segment? = indexAt(t).takeIf { it >= 0 }?.let { segments[it] }

    fun pctAt(t: Double): Double {
        val seg = segmentAt(t) ?: return segments.lastOrNull()?.toPct ?: 50.0
        return seg.pctAt(t)
    }

    /** The rest of the plan from time t, as segments starting at 0. The current segment is clipped. */
    fun remainingFrom(t: Int): List<Segment> {
        val b = SegmentBuilder()
        for (s in segments) {
            if (s.end <= t) continue
            if (s.start >= t) {
                b.add(s.duration, s.fromPct, s.toPct, s.label)
            } else {
                b.add(s.end - t, s.pctAt(t.toDouble()), s.toPct, s.label)
            }
        }
        return b.build()
    }

    /** Keeps everything up to t and replaces the rest with [newRest] (segments starting at 0). */
    fun replaceFrom(t: Int, newRest: List<Segment>): Plan {
        val kept = mutableListOf<Segment>()
        for (s in segments) {
            if (s.end <= t) {
                kept += s
            } else if (s.start < t) {
                kept += s.copy(duration = t - s.start, toPct = s.pctAt(t.toDouble()))
            }
        }
        var cursor = t
        for (s in newRest) {
            kept += s.shifted(cursor)
            cursor += s.duration
        }
        return Plan(kept)
    }

    companion object {
        fun of(w: Workout) = Plan(w.toSegments())
    }
}

/** Work in kilojoules for a list of segments at a given FTP. */
fun List<Segment>.kilojoules(ftp: Int): Int =
    sumOf { it.avgPct / 100.0 * ftp * it.duration / 1000.0 }.roundToInt()
