package com.vansaska.wattbike.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** The file format of workouts/workouts.json. */
@Serializable
data class WorkoutFile(
    val version: Int = 1,
    val workouts: List<Workout> = emptyList(),
)

@Serializable
data class Workout(
    val id: String,
    val name: String,
    val description: String = "",
    val steps: List<Step> = emptyList(),
) {
    val totalSeconds: Int get() = steps.sumOf { it.totalSeconds }
}

enum class StepKind { STEADY, RAMP, REPEAT }

/**
 * One step of a workout. Power values are percentages of FTP.
 *
 *  - Steady:  {"duration": "5:00", "power": 90}
 *  - Ramp:    {"duration": "10:00", "from": 45, "to": 75}
 *  - Repeat:  {"times": 5, "steps": [ ... ]}
 *
 * "type" is optional; it is inferred from which fields are present.
 * Durations can be seconds (300) or strings ("5:00", "1:00:00", "90s", "5m").
 */
@Serializable
data class Step(
    val type: String? = null,
    @Serializable(with = DurationSerializer::class)
    val duration: Int = 0,
    val power: Int? = null,
    val from: Int? = null,
    val to: Int? = null,
    val times: Int? = null,
    val steps: List<Step>? = null,
    val label: String? = null,
) {
    val kind: StepKind
        get() = when (type?.lowercase()) {
            "steady" -> StepKind.STEADY
            "ramp", "warmup", "cooldown" -> StepKind.RAMP
            "repeat", "intervals" -> StepKind.REPEAT
            else -> when {
                times != null || steps != null -> StepKind.REPEAT
                from != null || to != null -> StepKind.RAMP
                else -> StepKind.STEADY
            }
        }

    val totalSeconds: Int
        get() = when (kind) {
            StepKind.REPEAT -> (times ?: 1) * (steps ?: emptyList()).sumOf { it.totalSeconds }
            else -> duration
        }

    companion object {
        fun steady(duration: Int, power: Int, label: String? = null) =
            Step(duration = duration, power = power, label = label)

        fun ramp(duration: Int, from: Int, to: Int, label: String? = null) =
            Step(duration = duration, from = from, to = to, label = label)

        fun repeat(times: Int, steps: List<Step>) = Step(times = times, steps = steps)
    }
}

object Durations {
    /** Parses "5:00", "1:02:03", "90", "90s", "5m", "5 min", "1h". Returns seconds. */
    fun parse(text: String): Int {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return 0
        if (':' in t) {
            val parts = t.split(':').map { it.trim().toIntOrNull() ?: throw IllegalArgumentException("Bad duration: $text") }
            return parts.fold(0) { acc, p -> acc * 60 + p }
        }
        val m = Regex("""^(\d+(?:\.\d+)?)\s*(h|hr|hrs|hours?|m|min|mins|minutes?|s|sec|secs|seconds?)?$""").find(t)
            ?: throw IllegalArgumentException("Bad duration: $text")
        val n = m.groupValues[1].toDouble()
        val unit = m.groupValues[2]
        val mult = when {
            unit.startsWith("h") -> 3600
            unit.startsWith("m") -> 60
            else -> 1
        }
        return Math.round(n * mult).toInt()
    }

    fun format(seconds: Int): String {
        val s = seconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }
}

object DurationSerializer : KSerializer<Int> {
    override val descriptor = PrimitiveSerialDescriptor("Duration", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Int {
        val json = decoder as? JsonDecoder ?: return decoder.decodeInt()
        val prim = json.decodeJsonElement().jsonPrimitive
        return if (prim.isString) Durations.parse(prim.content) else prim.int
    }

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeString(Durations.format(value))
    }
}

val WorkoutJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Coggan power zones, used for labels and colours. */
enum class Zone(val number: Int, val label: String, val maxPct: Double) {
    RECOVERY(1, "Recovery", 55.0),
    ENDURANCE(2, "Endurance", 75.0),
    TEMPO(3, "Tempo", 87.0),
    SWEET_SPOT(3, "Sweet spot", 94.0),
    THRESHOLD(4, "Threshold", 105.0),
    VO2MAX(5, "VO2 max", 120.0),
    ANAEROBIC(6, "Anaerobic", 150.0),
    SPRINT(7, "Sprint", Double.MAX_VALUE);

    companion object {
        fun of(pct: Double): Zone = entries.first { pct <= it.maxPct + 0.0001 }
    }
}
