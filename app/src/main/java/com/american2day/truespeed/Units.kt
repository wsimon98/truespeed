package com.american2day.truespeed

/** Speed/distance unit system. Background is always black; only numbers change. */
enum class Speedo {
    MPH, KPH;

    /** Convert meters/second to this unit's whole-number speed for display. */
    fun speed(mps: Float): Int = when (this) {
        MPH -> Math.round(mps * 2.2369363f)
        KPH -> Math.round(mps * 3.6f)
    }

    /** Convert meters to this unit's distance (miles or kilometers). */
    fun distance(meters: Double): Double = when (this) {
        MPH -> meters / 1609.344
        KPH -> meters / 1000.0
    }

    val speedLabel: String get() = if (this == MPH) "MPH" else "KPH"
    val distLabel: String get() = if (this == MPH) "mi" else "km"

    companion object {
        fun from(name: String?): Speedo =
            if (name == KPH.name) KPH else MPH
    }
}
