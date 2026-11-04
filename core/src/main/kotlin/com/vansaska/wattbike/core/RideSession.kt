package com.vansaska.wattbike.core

import kotlinx.serialization.Serializable
import kotlin.math.pow
import kotlin.math.roundToInt

enum class RideState { WAITING, RUNNING, PAUSED, FINISHED }

@Serializable
data class RideEvent(val atSecond: Int, val text: String)

/** Readings averaged over a 5-second window. */
@Serializable
data class Sample(
    val timeMs: Long,
    val power: Int,
    val cadence: Int,
    val heartRate: Int? = null,
    val speedMs: Double,
    val distanceM: Double,
    val targetW: Int,
)

/**
 * The ride engine. Call [tick] about once a second with the latest bike readings.
 * Time only advances while you are pedalling; it pauses itself if you stop.
 */
class RideSession(
    workout: Workout,
    val ftp: Int,
    private val sampleIntervalMs: Long = 5000,
    /** Seconds at the start with a fixed resistance instead of ERG, so stopping to stretch doesn't make it surge. */
    val amnestySeconds: Int = 0,
) {
    val workoutName: String = workout.name
    var plan: Plan = Plan.of(workout)
        private set
    var state: RideState = RideState.WAITING
        private set

    /** Seconds of active riding, which is also the position in the plan. */
    var elapsed: Double = 0.0
        private set

    /** Percent multiplier on all targets, adjusted with +/- buttons. */
    var intensity: Int = 100
        private set

    var startedAtMs: Long? = null
        private set

    val events = mutableListOf<RideEvent>()
    val samples = mutableListOf<Sample>()
    var distanceM: Double = 0.0
        private set
    var workKj: Double = 0.0
        private set

    private var lastTickMs: Long? = null
    private var stoppedSinceMs: Long? = null

    private var windowStartMs: Long? = null
    private var winPower = 0.0
    private var winCadence = 0.0
    private var winHr = 0.0
    private var winHrN = 0
    private var winSpeed = 0.0
    private var winN = 0

    val planComplete: Boolean get() = elapsed >= plan.totalSeconds

    val currentSegment: Segment? get() = plan.segmentAt(elapsed)
    val nextSegment: Segment?
        get() = plan.indexAt(elapsed).takeIf { it >= 0 && it + 1 < plan.segments.size }?.let { plan.segments[it + 1] }

    val targetPct: Double
        get() = (if (planComplete) minOf(plan.pctAt(elapsed), 50.0) else plan.pctAt(elapsed)) * intensity / 100.0

    val targetWatts: Int get() = (targetPct / 100.0 * ftp).roundToInt()

    private var amnestyEnded = false

    /** True during the free warm-up period at the start. */
    val inAmnesty: Boolean
        get() = !amnestyEnded && state != RideState.FINISHED && elapsed < amnestySeconds

    val amnestyLeft: Int get() = if (inAmnesty) (amnestySeconds - elapsed).toInt().coerceAtLeast(0) else 0

    fun endAmnesty() {
        if (inAmnesty) {
            amnestyEnded = true
            events += RideEvent(elapsed.toInt(), "Warm-up amnesty ended early")
        }
    }

    /** Planned work for the whole (possibly rerouted) plan, at 100% intensity. */
    val plannedKj: Int get() = plan.segments.kilojoules(ftp)

    /** Work done so far as a percentage of the plan's total. */
    val energyPct: Int get() = if (plannedKj > 0) (workKj * 100 / plannedKj).roundToInt() else 0

    private var cuedIndex = -1
    private var doneCued = false
    private var wasInAmnesty = amnestySeconds > 0

    /** Returns a cue to speak now, at most once per change. Call once per tick. */
    fun pollCue(): Cue? {
        if (state != RideState.RUNNING) return null
        if (wasInAmnesty && !inAmnesty) {
            wasInAmnesty = false
            return Cue.ERG_ON
        }
        if (planComplete) {
            if (!doneCued) { doneCued = true; return Cue.DONE }
            return null
        }
        val i = plan.indexAt(elapsed)
        if (i < 0 || i == cuedIndex) return null
        if (secondsLeftInSegment > CueRules.LEAD_SECONDS) return null
        cuedIndex = i
        return CueRules.cueFor(plan, i)
    }

    val targetCadence: Int? get() = if (planComplete) null else currentSegment?.cadence

    val secondsLeftInSegment: Int
        get() = currentSegment?.let { (it.end - elapsed).toInt().coerceAtLeast(0) } ?: 0

    val secondsLeftInWorkout: Int get() = (plan.totalSeconds - elapsed).toInt().coerceAtLeast(0)

    private fun pedalling(d: BikeData): Boolean =
        (d.cadenceRpm ?: 0.0) >= 10 || (d.powerW ?: 0) >= 15

    fun tick(nowMs: Long, data: BikeData) {
        if (state == RideState.FINISHED) return
        val last = lastTickMs
        lastTickMs = nowMs
        val dt = if (last == null) 0.0 else ((nowMs - last) / 1000.0).coerceIn(0.0, 5.0)
        val moving = pedalling(data)

        when (state) {
            RideState.WAITING -> if (moving) {
                state = RideState.RUNNING
                startedAtMs = nowMs
                windowStartMs = nowMs
            }
            RideState.RUNNING -> {
                elapsed += dt
                distanceM += (data.speedKmh ?: 0.0) / 3.6 * dt
                workKj += (data.powerW ?: 0).coerceAtLeast(0) * dt / 1000.0
                accumulate(data)
                if (moving) {
                    stoppedSinceMs = null
                } else {
                    if (stoppedSinceMs == null) stoppedSinceMs = nowMs
                    if (nowMs - stoppedSinceMs!! >= 3000) {
                        state = RideState.PAUSED
                        flushWindow(nowMs)
                    }
                }
                val ws = windowStartMs
                if (ws != null && nowMs - ws >= sampleIntervalMs) flushWindow(nowMs)
            }
            RideState.PAUSED -> if (moving) {
                state = RideState.RUNNING
                stoppedSinceMs = null
                windowStartMs = nowMs
            }
            RideState.FINISHED -> {}
        }
    }

    private fun accumulate(d: BikeData) {
        winPower += (d.powerW ?: 0).coerceAtLeast(0)
        winCadence += d.cadenceRpm ?: 0.0
        d.heartRate?.let { winHr += it; winHrN++ }
        winSpeed += (d.speedKmh ?: 0.0) / 3.6
        winN++
    }

    private fun flushWindow(nowMs: Long) {
        if (winN > 0) {
            samples += Sample(
                timeMs = nowMs,
                power = (winPower / winN).roundToInt(),
                cadence = (winCadence / winN).roundToInt(),
                heartRate = if (winHrN > 0) (winHr / winHrN).roundToInt() else null,
                speedMs = winSpeed / winN,
                distanceM = distanceM,
                targetW = targetWatts,
            )
        }
        winPower = 0.0; winCadence = 0.0; winHr = 0.0; winHrN = 0; winSpeed = 0.0; winN = 0
        windowStartMs = nowMs
    }

    fun pauseManually(nowMs: Long) {
        if (state == RideState.RUNNING) {
            flushWindow(nowMs)
            state = RideState.PAUSED
        }
    }

    fun adjustIntensity(delta: Int) {
        val new = (intensity + delta).coerceIn(50, 150)
        if (new != intensity) {
            intensity = new
            events += RideEvent(elapsed.toInt(), "Intensity set to $intensity%")
        }
    }

    fun routeOptions(): List<RouteOption> = RouteGenerator.options(plan, elapsed.toInt())

    fun takeRoute(option: RouteOption) {
        val at = elapsed.toInt()
        plan = plan.replaceFrom(at, option.segments)
        cuedIndex = -1
        events += RideEvent(at, "Took a different route: ${option.title}")
    }

    fun finish(nowMs: Long) {
        if (state == RideState.RUNNING) flushWindow(nowMs)
        state = RideState.FINISHED
    }

    fun toRecord(id: String): RideRecord = RideRecord(
        id = id,
        workoutName = workoutName,
        ftp = ftp,
        startedAtMs = startedAtMs ?: samples.firstOrNull()?.timeMs ?: 0L,
        activeSeconds = elapsed.roundToInt(),
        distanceM = distanceM,
        workKj = workKj,
        samples = samples.toList(),
        events = events.toList(),
    )
}

@Serializable
data class RideRecord(
    val id: String,
    val workoutName: String,
    val ftp: Int,
    val startedAtMs: Long,
    val activeSeconds: Int,
    val distanceM: Double,
    val workKj: Double,
    val samples: List<Sample>,
    val events: List<RideEvent> = emptyList(),
    val title: String? = null,
    val stravaActivityId: Long? = null,
    val uploadError: String? = null,
) {
    val avgPower: Int
        get() = if (activeSeconds > 0) (workKj * 1000 / activeSeconds).roundToInt()
        else samples.map { it.power }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0

    val maxPower: Int get() = samples.maxOfOrNull { it.power } ?: 0

    val avgCadence: Int
        get() = samples.filter { it.cadence > 0 }.map { it.cadence }.average().takeIf { !it.isNaN() }?.roundToInt() ?: 0

    val avgHeartRate: Int?
        get() = samples.mapNotNull { it.heartRate }.takeIf { it.isNotEmpty() }?.average()?.roundToInt()

    /** Normalised power from 5-second samples (30-second rolling average = 6 samples). */
    val normalizedPower: Int
        get() {
            if (samples.size < 6) return avgPower
            val rolling = samples.indices.drop(5).map { i -> (i - 5..i).map { samples[it].power }.average() }
            return rolling.map { it.pow(4) }.average().pow(0.25).roundToInt()
        }

    val intensityFactor: Double get() = if (ftp > 0) normalizedPower.toDouble() / ftp else 0.0

    val tss: Int get() = (activeSeconds * normalizedPower * intensityFactor / (ftp * 36.0)).roundToInt()

    val displayTitle: String get() = title ?: workoutName

    fun stravaDescription(): String = buildString {
        append("Workout: ").append(workoutName).append(" (FTP ").append(ftp).append(" W)\n")
        append("Avg ").append(avgPower).append(" W · NP ").append(normalizedPower).append(" W · ")
        append(workKj.roundToInt()).append(" kJ")
        for (e in events) append("\n").append(Durations.format(e.atSecond)).append(" – ").append(e.text)
    }
}
