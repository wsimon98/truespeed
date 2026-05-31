package com.american2day.truespeed

import android.content.Context

/** Thin wrapper over SharedPreferences for unit, color and trip persistence. */
class Prefs(context: Context) {

    private val sp = context.applicationContext
        .getSharedPreferences("truespeed", Context.MODE_PRIVATE)

    var unit: Speedo
        get() = Speedo.from(sp.getString(KEY_UNIT, Speedo.MPH.name))
        set(v) = sp.edit().putString(KEY_UNIT, v.name).apply()

    /** Index into MainActivity.DISPLAY_COLORS. */
    var colorIndex: Int
        get() = sp.getInt(KEY_COLOR, 1)
        set(v) = sp.edit().putInt(KEY_COLOR, v).apply()

    var magFallback: Boolean
        get() = sp.getBoolean(KEY_MAG, false)
        set(v) = sp.edit().putBoolean(KEY_MAG, v).apply()

    /** Pinch-to-resize factor for the floating bubble (1.0 = default size). */
    var bubbleScale: Float
        get() = sp.getFloat(KEY_BUBBLE_SCALE, 1f)
        set(v) = sp.edit().putFloat(KEY_BUBBLE_SCALE, v).apply()

    // --- Trip data (stored in SI; converted for display) ---
    var tripDistanceM: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_TRIP_DIST, 0))
        set(v) = sp.edit().putLong(KEY_TRIP_DIST, java.lang.Double.doubleToRawLongBits(v)).apply()

    var tripMaxMps: Float
        get() = sp.getFloat(KEY_TRIP_MAX, 0f)
        set(v) = sp.edit().putFloat(KEY_TRIP_MAX, v).apply()

    var tripMovingDistM: Double
        get() = java.lang.Double.longBitsToDouble(sp.getLong(KEY_MOV_DIST, 0))
        set(v) = sp.edit().putLong(KEY_MOV_DIST, java.lang.Double.doubleToRawLongBits(v)).apply()

    var tripMovingTimeMs: Long
        get() = sp.getLong(KEY_MOV_TIME, 0)
        set(v) = sp.edit().putLong(KEY_MOV_TIME, v).apply()

    fun resetTrip() {
        sp.edit()
            .putLong(KEY_TRIP_DIST, 0)
            .putFloat(KEY_TRIP_MAX, 0f)
            .putLong(KEY_MOV_DIST, 0)
            .putLong(KEY_MOV_TIME, 0)
            .apply()
    }

    companion object {
        private const val KEY_UNIT = "unit"
        private const val KEY_COLOR = "color"
        private const val KEY_MAG = "mag_fallback"
        private const val KEY_BUBBLE_SCALE = "bubble_scale"
        private const val KEY_TRIP_DIST = "trip_dist"
        private const val KEY_TRIP_MAX = "trip_max"
        private const val KEY_MOV_DIST = "mov_dist"
        private const val KEY_MOV_TIME = "mov_time"
    }
}
