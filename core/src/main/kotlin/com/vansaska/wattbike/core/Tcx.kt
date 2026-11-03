package com.vansaska.wattbike.core

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Writes a ride as a TCX file, which Strava accepts as an upload. */
object TcxWriter {
    private val iso = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    private fun time(ms: Long) = iso.format(Instant.ofEpochMilli(ms))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun write(ride: RideRecord): String = buildString {
        val start = time(ride.startedAtMs)
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2" xmlns:ns3="http://www.garmin.com/xmlschemas/ActivityExtension/v2">""").append('\n')
        append("<Activities>\n<Activity Sport=\"Biking\">\n")
        append("<Id>").append(start).append("</Id>\n")
        append("<Lap StartTime=\"").append(start).append("\">\n")
        append("<TotalTimeSeconds>").append(ride.activeSeconds).append("</TotalTimeSeconds>\n")
        append("<DistanceMeters>").append("%.1f".format(java.util.Locale.ROOT, ride.distanceM)).append("</DistanceMeters>\n")
        append("<Calories>").append(ride.workKj.roundToInt()).append("</Calories>\n")
        ride.avgHeartRate?.let { append("<AverageHeartRateBpm><Value>").append(it).append("</Value></AverageHeartRateBpm>\n") }
        append("<Intensity>Active</Intensity>\n")
        if (ride.avgCadence > 0) append("<Cadence>").append(ride.avgCadence).append("</Cadence>\n")
        append("<TriggerMethod>Manual</TriggerMethod>\n")
        append("<Track>\n")
        for (s in ride.samples) {
            append("<Trackpoint>")
            append("<Time>").append(time(s.timeMs)).append("</Time>")
            append("<DistanceMeters>").append("%.1f".format(java.util.Locale.ROOT, s.distanceM)).append("</DistanceMeters>")
            s.heartRate?.let { append("<HeartRateBpm><Value>").append(it).append("</Value></HeartRateBpm>") }
            append("<Cadence>").append(s.cadence.coerceIn(0, 254)).append("</Cadence>")
            append("<Extensions><ns3:TPX>")
            append("<ns3:Speed>").append("%.2f".format(java.util.Locale.ROOT, s.speedMs)).append("</ns3:Speed>")
            append("<ns3:Watts>").append(s.power).append("</ns3:Watts>")
            append("</ns3:TPX></Extensions>")
            append("</Trackpoint>\n")
        }
        append("</Track>\n</Lap>\n")
        append("<Notes>").append(esc(ride.stravaDescription())).append("</Notes>\n")
        append("</Activity>\n</Activities>\n</TrainingCenterDatabase>\n")
    }
}
