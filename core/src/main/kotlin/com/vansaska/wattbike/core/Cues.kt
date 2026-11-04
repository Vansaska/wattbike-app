package com.vansaska.wattbike.core

/**
 * Spoken cues. The app plays res/raw/cue_<name>.mp3 if it exists (pre-recorded voice),
 * otherwise it speaks [phrase] with the phone's text-to-speech.
 */
enum class Cue(val phrase: String) {
    SPEED_UP("Speed up"),
    EASE_OFF("Ease off"),
    LAST_ONE("Last one"),
    COOL_DOWN("Cool down"),
    ERG_ON("Resistance on"),
    DONE("Workout complete"),
}

object CueRules {
    /** Seconds before a change that the cue is spoken, so it lands as the change happens. */
    const val LEAD_SECONDS = 2

    /** Which cue (if any) announces the move from [cur] to [next] in [plan]. */
    fun cueFor(plan: Plan, curIndex: Int): Cue? {
        val segs = plan.segments
        val cur = segs.getOrNull(curIndex) ?: return null
        val next = segs.getOrNull(curIndex + 1) ?: return null
        val isLast = curIndex + 1 == segs.lastIndex
        val coolLabel = next.label.contains("cool", ignoreCase = true) || next.label.contains("spin down", ignoreCase = true)
        if (coolLabel || (isLast && next.toPct < next.fromPct && next.fromPct <= 65)) return Cue.COOL_DOWN
        val diff = next.fromPct - cur.toPct
        return when {
            diff >= 3 -> {
                val laterHarder = segs.drop(curIndex + 2).any { it.avgPct >= next.avgPct - 2 && it.avgPct > 75 }
                if (next.avgPct > 75 && !laterHarder && curIndex > 0) Cue.LAST_ONE else Cue.SPEED_UP
            }
            diff <= -3 -> Cue.EASE_OFF
            next.cadence != null && cur.cadence != null && next.cadence - cur.cadence >= 8 -> Cue.SPEED_UP
            next.cadence != null && cur.cadence != null && cur.cadence - next.cadence >= 8 -> Cue.EASE_OFF
            else -> null
        }
    }
}
