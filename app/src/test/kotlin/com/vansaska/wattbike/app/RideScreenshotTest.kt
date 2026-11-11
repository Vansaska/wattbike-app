package com.vansaska.wattbike.app

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import com.vansaska.wattbike.app.ui.RideContent
import com.vansaska.wattbike.core.RideState
import com.vansaska.wattbike.core.Step
import com.vansaska.wattbike.core.Workout
import com.vansaska.wattbike.core.toSegments
import org.junit.Rule
import org.junit.Test

/** Renders the ride screen in a few states. Run `./gradlew :app:recordPaparazziDebug`. */
class RideScreenshotTest {
    @get:Rule
    val paparazzi = Paparazzi(
        // A typical modern phone held sideways, about 912×411 dp (rendered at 2x).
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1824, screenHeight = 822, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    private val workout = Workout(
        "morning", "Morning leg speed 8×1:25",
        steps = listOf(
            Step.steady(120, 63, "Warm-up", 80), Step.steady(60, 70, "Warm-up", 82), Step.steady(45, 78, "Warm-up", 85),
            Step.repeat(8, listOf(Step.steady(85, 85, "Effort", 90), Step.steady(45, 50, "Easy", 80))),
            Step.ramp(45, 50, 40, "Cool down", 75),
        ),
    )

    private fun ui(
        cadence: Int?, aim: Int?, label: String, elapsed: Int = 580,
        state: RideState = RideState.RUNNING, amnesty: Boolean = false, power: Int? = 121, target: Int = 119,
    ) = RideUi(
        state = state, elapsed = elapsed, totalSeconds = 1310, segments = workout.toSegments(),
        segmentLabel = label, nextLabel = null, nextWatts = null, nextCadence = null, targetCadence = aim,
        segmentLeft = 52, workoutLeft = 1310 - elapsed, targetWatts = target, intensity = 100,
        power = power, cadence = cadence, heartRate = null, planComplete = false,
        workoutName = workout.name, ftp = 140, events = emptyList(),
        inAmnesty = amnesty, amnestyLeft = 74, amnestyLevelPct = 20,
        energyPct = 54, energyKj = 77, plannedKj = 142,
    )

    @Test fun effortOnPace() = paparazzi.snapshot { RideContent(ui(89, 90, "Effort (4/8)"), problem = null) }

    @Test fun easyTooSlow() = paparazzi.snapshot { RideContent(ui(71, 80, "Easy (4/8)", power = 72, target = 70), problem = null) }

    @Test fun effortTooFast() = paparazzi.snapshot { RideContent(ui(99, 90, "Effort (5/8)"), problem = null) }

    @Test fun warmUpAmnesty() = paparazzi.snapshot {
        RideContent(ui(78, 80, "Warm-up", elapsed = 46, amnesty = true, power = 84), problem = null)
    }

    @Test fun waitingNoBike() = paparazzi.snapshot {
        RideContent(ui(null, 80, "Warm-up", elapsed = 0, state = RideState.WAITING, power = null), problem = "bike not connected")
    }
}
