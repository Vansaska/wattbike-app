package com.vansaska.wattbike.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoreTest {
    private fun libraryFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "workouts/workouts.json").exists()) dir = dir.parentFile
        return File(dir!!, "workouts/workouts.json")
    }

    private val library: List<Workout> by lazy {
        WorkoutJson.decodeFromString(WorkoutFile.serializer(), libraryFile().readText()).workouts
    }

    @Test fun durations() {
        assertEquals(300, Durations.parse("5:00"))
        assertEquals(3723, Durations.parse("1:02:03"))
        assertEquals(90, Durations.parse("90"))
        assertEquals(90, Durations.parse("90s"))
        assertEquals(300, Durations.parse("5m"))
        assertEquals(300, Durations.parse("5 min"))
        assertEquals("5:00", Durations.format(300))
        assertEquals("1:02:03", Durations.format(3723))
    }

    @Test fun libraryParsesAndHasExpectedLengths() {
        val ss = library.first { it.id == "sweet-spot-3x10" }
        assertEquals(60 * 60, ss.totalSeconds)
        val segs = ss.toSegments()
        assertEquals(ss.totalSeconds, segs.last().end)
        assertEquals("Sweet spot (2/3)", segs[3].label)
        // Every workout in the library must parse and be contiguous.
        for (w in library) {
            val s = w.toSegments()
            assertTrue(s.isNotEmpty(), w.id)
            s.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, w.id) }
            assertEquals(w.totalSeconds, s.last().end, w.id)
        }
        val ids = library.map { it.id }
        assertEquals(ids.size, ids.toSet().size, "workout ids must be unique")
    }

    @Test fun roundTrip() {
        val w = library.first()
        val text = WorkoutJson.encodeToString(Workout.serializer(), w)
        assertEquals(w, WorkoutJson.decodeFromString(Workout.serializer(), text))
    }

    @Test fun planReplaceKeepsTotalTime() {
        val plan = Plan.of(library.first { it.id == "vo2-5x3" })
        val t = 20 * 60 + 30
        for (opt in RouteGenerator.options(plan, t)) {
            assertEquals(plan.totalSeconds - t, opt.totalSeconds, opt.title)
            val newPlan = plan.replaceFrom(t, opt.segments)
            assertEquals(plan.totalSeconds, newPlan.totalSeconds, opt.title)
            newPlan.segments.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start, opt.title) }
            // Every option is no harder than the original remainder.
            assertTrue(opt.segments.kilojoules(250) <= plan.remainingFrom(t).kilojoules(250), opt.title)
            // Time already ridden is unchanged.
            assertEquals(plan.pctAt(100.0), newPlan.pctAt(100.0), 0.001)
        }
        val titles = RouteGenerator.options(plan, t).map { it.title }
        assertEquals(listOf("Same plan, easier", "Shorter efforts", "Steady endurance", "Easy spin to finish"), titles)
    }

    @Test fun nearTheEndOnlyCoolDown() {
        val plan = Plan.of(library.first())
        val opts = RouteGenerator.options(plan, plan.totalSeconds - 200)
        assertEquals(listOf("Cool down now"), opts.map { it.title })
        assertTrue(RouteGenerator.options(plan, plan.totalSeconds - 30).isEmpty())
    }

    @Test fun ftmsParsing() {
        // flags: speed present (bit0=0), cadence (bit2), power (bit6), HR (bit9)
        val flags = (1 shl 2) or (1 shl 6) or (1 shl 9)
        val bytes = byteArrayOf(
            (flags and 0xFF).toByte(), (flags shr 8).toByte(),
            0xC4.toByte(), 0x09, // speed 2500 -> 25.00 km/h
            0xB4.toByte(), 0x00, // cadence 180 -> 90 rpm
            0xFA.toByte(), 0x00, // power 250
            0x8C.toByte(), // hr 140
        )
        val d = assertNotNull(Ftms.parseIndoorBikeData(bytes))
        assertEquals(25.0, d.speedKmh)
        assertEquals(90.0, d.cadenceRpm)
        assertEquals(250, d.powerW)
        assertEquals(140, d.heartRate)
        assertNull(d.distanceM)

        val cmd = Ftms.setTargetPower(300)
        assertEquals(listOf(0x05, 0x2C, 0x01), cmd.map { it.toInt() and 0xFF })
        val resp = assertNotNull(Ftms.parseControlPointResponse(byteArrayOf(0x80.toByte(), 0x05, 0x01)))
        assertTrue(resp.success)
    }

    @Test fun rideSessionRunsPausesAndRecords() {
        val w = Workout("t", "Test", steps = listOf(Step.steady(120, 100), Step.steady(120, 50)))
        val s = RideSession(w, ftp = 200)
        var now = 1_000_000L
        val pedal = BikeData(speedKmh = 30.0, cadenceRpm = 90.0, powerW = 200)
        val stopped = BikeData(speedKmh = 0.0, cadenceRpm = 0.0, powerW = 0)
        s.tick(now, stopped)
        assertEquals(RideState.WAITING, s.state)
        assertEquals(200, s.targetWatts)
        repeat(61) { s.tick(now, pedal); now += 1000 }
        assertEquals(RideState.RUNNING, s.state)
        assertEquals(60.0, s.elapsed, 1.0)
        repeat(5) { s.tick(now, stopped); now += 1000 }
        assertEquals(RideState.PAUSED, s.state)
        val pausedAt = s.elapsed
        repeat(10) { s.tick(now, stopped); now += 1000 }
        assertEquals(pausedAt, s.elapsed)
        repeat(100) { s.tick(now, pedal); now += 1000 }
        assertEquals(100, s.targetWatts) // into the second step
        s.adjustIntensity(-10)
        assertEquals(90, s.targetWatts)
        val opt = s.routeOptions().single()
        assertEquals("Cool down now", opt.title)
        s.takeRoute(opt)
        assertEquals(240, s.plan.totalSeconds)
        s.finish(now)
        val rec = s.toRecord("r1")
        assertTrue(rec.samples.size >= 30)
        assertTrue(rec.avgPower in 190..200, "avg power ${rec.avgPower}")
        assertTrue(rec.distanceM > 1000)
        val tcx = TcxWriter.write(rec)
        assertTrue("<ns3:Watts>200</ns3:Watts>" in tcx)
        assertTrue("Took a different route" in tcx)
    }

    @Test fun cadenceTargets() {
        val json = """
            {"id":"c","name":"C","steps":[
              {"duration":"5:00","power":60,"cadence":85},
              {"times":2,"cadence":100,"steps":[
                {"duration":"1:00","power":120},
                {"duration":"1:00","power":50,"cadence":80}
              ]},
              {"duration":"5:00","power":50}
            ]}
        """
        val w = WorkoutJson.decodeFromString(Workout.serializer(), json)
        val segs = w.toSegments()
        assertEquals(listOf(85, 100, 80, 100, 80, null), segs.map { it.cadence })
        val plan = Plan(segs)
        // Rerouting keeps cadence on kept efforts and drops it on generic easy riding.
        val easier = RouteGenerator.options(plan, 330).first { it.title == "Same plan, easier" }
        assertEquals(100, easier.segments.first().cadence)
        val steady = RouteGenerator.options(plan, 330).first { it.title == "Steady endurance" }
        assertNull(steady.segments.first().cadence)
        val s = RideSession(w, 200)
        assertEquals(85, s.targetCadence)
        assertEquals(w, WorkoutJson.decodeFromString(Workout.serializer(), WorkoutJson.encodeToString(Workout.serializer(), w)))
    }
}
