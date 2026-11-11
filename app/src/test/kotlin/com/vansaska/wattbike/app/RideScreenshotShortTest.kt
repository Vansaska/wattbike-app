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

/** The ride screen on a shorter landscape phone (about 780×340 dp), to check everything still fits. */
class RideScreenshotShortTest {
    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1560, screenHeight = 680, xdpi = 320, ydpi = 320,
            density = Density.XHIGH, orientation = ScreenOrientation.LANDSCAPE,
        ),
        theme = "android:Theme.Material.NoActionBar",
    )

    @Test fun shortPhoneOnPace() = paparazzi.snapshot {
        val w = Workout("m", "Morning", steps = listOf(Step.repeat(8, listOf(Step.steady(85, 85, "Effort", 90), Step.steady(45, 50, "Easy", 80)))))
        RideContent(
            RideUi(
                state = RideState.RUNNING, elapsed = 400, totalSeconds = 1040, segments = w.toSegments(),
                segmentLabel = "Effort (4/8)", nextLabel = null, nextWatts = null, nextCadence = null, targetCadence = 90,
                segmentLeft = 40, workoutLeft = 640, targetWatts = 119, intensity = 100, power = 120, cadence = 88,
                heartRate = null, planComplete = false, workoutName = "Morning", ftp = 140, events = emptyList(),
                inAmnesty = false, amnestyLeft = 0, amnestyLevelPct = 20, energyPct = 38, energyKj = 50, plannedKj = 130,
                segmentIndex = 6, segmentDuration = 85, amnestyTotal = 120,
            ),
            problem = null,
        )
    }
}
