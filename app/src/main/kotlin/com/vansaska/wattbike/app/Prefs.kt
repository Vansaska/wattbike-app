package com.vansaska.wattbike.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var ftp: Int
        get() = sp.getInt("ftp", 200)
        set(v) = sp.edit().putInt("ftp", v).apply()

    var workoutsUrl: String
        get() = sp.getString("workoutsUrl", null) ?: DEFAULT_WORKOUTS_URL
        set(v) = sp.edit().putString("workoutsUrl", v).apply()

    var lastDeviceAddress: String?
        get() = sp.getString("deviceAddress", null)
        set(v) = sp.edit().putString("deviceAddress", v).apply()

    var lastDeviceName: String?
        get() = sp.getString("deviceName", null)
        set(v) = sp.edit().putString("deviceName", v).apply()

    var stravaClientId: String
        get() = sp.getString("stravaClientId", "") ?: ""
        set(v) = sp.edit().putString("stravaClientId", v.trim()).apply()

    var stravaClientSecret: String
        get() = sp.getString("stravaClientSecret", "") ?: ""
        set(v) = sp.edit().putString("stravaClientSecret", v.trim()).apply()

    var stravaAccessToken: String?
        get() = sp.getString("stravaAccess", null)
        set(v) = sp.edit().putString("stravaAccess", v).apply()

    var stravaRefreshToken: String?
        get() = sp.getString("stravaRefresh", null)
        set(v) = sp.edit().putString("stravaRefresh", v).apply()

    var stravaExpiresAt: Long
        get() = sp.getLong("stravaExpires", 0)
        set(v) = sp.edit().putLong("stravaExpires", v).apply()

    var stravaAthlete: String?
        get() = sp.getString("stravaAthlete", null)
        set(v) = sp.edit().putString("stravaAthlete", v).apply()

    var autoUpload: Boolean
        get() = sp.getBoolean("autoUpload", true)
        set(v) = sp.edit().putBoolean("autoUpload", v).apply()

    /** Seconds of fixed-resistance "amnesty" at the start of a ride (0 = off). */
    var amnestySeconds: Int
        get() = sp.getInt("amnestySeconds", 120)
        set(v) = sp.edit().putInt("amnestySeconds", v).apply()

    /** Resistance during the amnesty, as a fraction of the bike's range. */
    var amnestyLevel: Float
        get() = sp.getFloat("amnestyLevel", 0.2f)
        set(v) = sp.edit().putFloat("amnestyLevel", v).apply()

    var voiceCues: Boolean
        get() = sp.getBoolean("voiceCues2", false)
        set(v) = sp.edit().putBoolean("voiceCues2", v).apply()

    /** Sound at each change: "chime", "beep" or "none". */
    var cueSound: String
        get() = sp.getString("cueSound", null) ?: "chime"
        set(v) = sp.edit().putString("cueSound", v).apply()

    var flashCues: Boolean
        get() = sp.getBoolean("flashCues", true)
        set(v) = sp.edit().putBoolean("flashCues", v).apply()

    companion object {
        const val DEFAULT_WORKOUTS_URL =
            "https://raw.githubusercontent.com/Vansaska/wattbike-app/main/workouts/workouts.json"
    }
}
