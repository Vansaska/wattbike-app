package com.vansaska.wattbike.core

import java.util.UUID

/** Latest readings from the bike. Fields are null when the bike didn't send them. */
data class BikeData(
    val speedKmh: Double? = null,
    val cadenceRpm: Double? = null,
    val distanceM: Int? = null,
    val resistance: Int? = null,
    val powerW: Int? = null,
    val heartRate: Int? = null,
    val elapsedS: Int? = null,
) {
    /** Combines a partial update (bikes may split data across several notifications). */
    fun merge(update: BikeData) = BikeData(
        speedKmh = update.speedKmh ?: speedKmh,
        cadenceRpm = update.cadenceRpm ?: cadenceRpm,
        distanceM = update.distanceM ?: distanceM,
        resistance = update.resistance ?: resistance,
        powerW = update.powerW ?: powerW,
        heartRate = update.heartRate ?: heartRate,
        elapsedS = update.elapsedS ?: elapsedS,
    )
}

data class ControlPointResponse(val requestOpCode: Int, val result: Int) {
    val success: Boolean get() = result == 0x01
    val resultText: String
        get() = when (result) {
            0x01 -> "success"
            0x02 -> "not supported"
            0x03 -> "invalid parameter"
            0x04 -> "operation failed"
            0x05 -> "control not permitted"
            else -> "code $result"
        }
}

/** Bluetooth Fitness Machine Service (FTMS) constants, commands and parsers. */
object Ftms {
    private fun uuid16(short: Int): UUID =
        UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))

    val SERVICE: UUID = uuid16(0x1826)
    val FEATURE: UUID = uuid16(0x2ACC)
    val INDOOR_BIKE_DATA: UUID = uuid16(0x2AD2)
    val CONTROL_POINT: UUID = uuid16(0x2AD9)
    val MACHINE_STATUS: UUID = uuid16(0x2ADA)
    val CCCD: UUID = uuid16(0x2902)

    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_TARGET_RESISTANCE = 0x04
    const val OP_SET_TARGET_POWER = 0x05
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_RESPONSE = 0x80

    fun requestControl() = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun reset() = byteArrayOf(OP_RESET.toByte())
    fun startOrResume() = byteArrayOf(OP_START_RESUME.toByte())
    fun stop() = byteArrayOf(OP_STOP_PAUSE.toByte(), 0x01)

    fun setTargetPower(watts: Int): ByteArray {
        val w = watts.coerceIn(0, 2000)
        return byteArrayOf(OP_SET_TARGET_POWER.toByte(), (w and 0xFF).toByte(), ((w shr 8) and 0xFF).toByte())
    }

    fun parseControlPointResponse(b: ByteArray): ControlPointResponse? {
        if (b.size < 3 || (b[0].toInt() and 0xFF) != OP_RESPONSE) return null
        return ControlPointResponse(b[1].toInt() and 0xFF, b[2].toInt() and 0xFF)
    }

    /** Parses the Indoor Bike Data characteristic (0x2AD2). */
    fun parseIndoorBikeData(b: ByteArray): BikeData? {
        if (b.size < 2) return null
        val r = Reader(b)
        val flags = r.u16()
        fun has(bit: Int) = flags and (1 shl bit) != 0
        try {
            // Bit 0 is "More Data": when it is 0, instantaneous speed IS present.
            val speed = if (!has(0)) r.u16() / 100.0 else null
            if (has(1)) r.skip(2) // average speed
            val cadence = if (has(2)) r.u16() / 2.0 else null
            if (has(3)) r.skip(2) // average cadence
            val distance = if (has(4)) r.u24() else null
            val resistance = if (has(5)) r.s16() else null
            val power = if (has(6)) r.s16() else null
            if (has(7)) r.skip(2) // average power
            if (has(8)) r.skip(5) // energy
            val hr = if (has(9)) r.u8().takeIf { it > 0 } else null
            if (has(10)) r.skip(1) // METs
            val elapsed = if (has(11)) r.u16() else null
            return BikeData(speed, cadence, distance, resistance, power, hr, elapsed)
        } catch (e: IndexOutOfBoundsException) {
            return null
        }
    }

    private class Reader(val b: ByteArray) {
        var i = 0
        fun u8(): Int = (b[i++].toInt() and 0xFF)
        fun u16(): Int = u8() or (u8() shl 8)
        fun s16(): Int = u16().let { if (it >= 0x8000) it - 0x10000 else it }
        fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)
        fun skip(n: Int) {
            if (i + n > b.size) throw IndexOutOfBoundsException()
            i += n
        }
    }
}

/** Standard Bluetooth Heart Rate service, for an optional chest strap. */
object HeartRateService {
    val SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    val MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")

    fun parse(b: ByteArray): Int? {
        if (b.size < 2) return null
        val flags = b[0].toInt()
        return if (flags and 0x01 == 0) b[1].toInt() and 0xFF
        else if (b.size >= 3) (b[1].toInt() and 0xFF) or ((b[2].toInt() and 0xFF) shl 8) else null
    }
}
