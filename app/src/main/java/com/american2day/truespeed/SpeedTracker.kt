package com.american2day.truespeed

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import kotlin.math.abs

/** Heading currently shown to the user. */
enum class HeadingSource { GPS_COURSE, MAGNETOMETER, WAITING }

/** GPS fix quality, derived from reported horizontal accuracy + fix age. */
enum class FixState { NO_FIX, WEAK, OK }

/** Immutable snapshot pushed to the UI on each update. */
data class SpeedState(
    val speedMps: Float,
    val headingDeg: Float?,        // null until we have a first good heading
    val headingSource: HeadingSource,
    val fix: FixState,
    val accuracyM: Float,          // < 0 when unknown
    val tripDistanceM: Double,
    val tripMaxMps: Float,
    val tripAvgMovingMps: Float,
)

/**
 * Reads speed and course straight from the platform GPS provider (no Google
 * Play Services). Smooths speed, decides when a heading is trustworthy, keeps
 * trip stats, and optionally falls back to the magnetometer for heading.
 *
 * All callbacks arrive on the main thread (LocationManager + SensorManager are
 * registered with the main Looper by default).
 */
class SpeedTracker(
    context: Context,
    private val prefs: Prefs,
    private val onState: (SpeedState) -> Unit,
) : LocationListener, SensorEventListener {

    private val appCtx = context.applicationContext
    private val lm = appCtx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val sm = appCtx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    var magFallback: Boolean = prefs.magFallback
        private set

    // --- speed smoothing / stop detection state ---
    private var smoothedMps = 0f
    private var lastFixElapsed = 0L          // SystemClock.elapsedRealtime of last fix
    private var lastMovingElapsed = 0L       // last time we were clearly moving

    // --- heading state ---
    private var headingDeg: Float? = null
    private var headingSource = HeadingSource.WAITING
    private var lastHeadingLoc: Location? = null
    private var lastGpsHeadingElapsed = 0L
    private var magAzimuth: Float? = null     // latest magnetometer-derived azimuth

    // --- trip state (mirrors Prefs; loaded on start) ---
    private var prevLoc: Location? = null
    private var tripDistanceM = 0.0
    private var tripMaxMps = 0f
    private var movingDistM = 0.0
    private var movingTimeMs = 0L

    fun start() {
        tripDistanceM = prefs.tripDistanceM
        tripMaxMps = prefs.tripMaxMps
        movingDistM = prefs.tripMovingDistM
        movingTimeMs = prefs.tripMovingTimeMs
        try {
            // GPS-only: works without cell service once it has a lock.
            lm.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                MIN_TIME_MS,
                0f,
                this,
            )
        } catch (_: SecurityException) {
            // Permission not granted yet; MainActivity requests it then restarts.
        }
        if (magFallback && rotationSensor != null) {
            sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        }
        emit()
    }

    fun stop() {
        try {
            lm.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        sm.unregisterListener(this)
        persistTrip()
    }

    fun setMagFallback(enabled: Boolean) {
        magFallback = enabled
        prefs.magFallback = enabled
        if (enabled && rotationSensor != null) {
            sm.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            sm.unregisterListener(this)
            if (headingSource == HeadingSource.MAGNETOMETER) {
                headingSource = HeadingSource.WAITING
            }
        }
        emit()
    }

    fun resetTrip() {
        tripDistanceM = 0.0
        tripMaxMps = 0f
        movingDistM = 0.0
        movingTimeMs = 0L
        prevLoc = null
        prefs.resetTrip()
        emit()
    }

    // -------------------- LocationListener --------------------

    override fun onLocationChanged(loc: Location) {
        val now = SystemClock.elapsedRealtime()
        val acc = if (loc.hasAccuracy()) loc.accuracy else -1f

        // Raw instantaneous speed: trust the GPS Doppler speed when present
        // (it is far better than differentiating positions); otherwise derive
        // from distance/time between fixes.
        val rawMps: Float = when {
            loc.hasSpeed() -> loc.speed
            prevLoc != null -> {
                val dt = (loc.time - prevLoc!!.time) / 1000f
                if (dt > 0f) prevLoc!!.distanceTo(loc) / dt else 0f
            }
            else -> 0f
        }.coerceAtLeast(0f)

        // Exponential moving average: enough smoothing to stop jitter, light
        // enough that it does not visibly lag acceleration.
        smoothedMps += SMOOTH_ALPHA * (rawMps - smoothedMps)

        if (rawMps >= MOVING_SPEED) lastMovingElapsed = now
        lastFixElapsed = now

        // --- trip distance: only accumulate while genuinely moving, so GPS
        // jitter at a stoplight does not inflate the odometer ---
        prevLoc?.let { p ->
            if (rawMps >= MOVING_SPEED) {
                val d = p.distanceTo(loc).toDouble()
                if (d.isFinite() && d < MAX_STEP_M) {
                    tripDistanceM += d
                    movingDistM += d
                    movingTimeMs += (loc.time - p.time).coerceIn(0, MIN_TIME_MS * 4)
                }
            }
        }
        if (rawMps > tripMaxMps) tripMaxMps = rawMps
        prevLoc = loc

        // --- heading gate ---
        // Final rule (motorcycle/scooter oriented): accept GPS course-over-
        // ground ONLY when all hold:
        //   1. the fix reports a bearing,
        //   2. horizontal accuracy is acceptable (<= MIN_HEADING_ACC),
        //   3. we are moving fast enough that bearing isn't noise
        //      (>= MIN_HEADING_SPEED),
        //   4. we have travelled at least max(10 m, 2 x accuracy) since the
        //      last accepted heading fix.
        // Rationale: GPS bearing is meaningless at a standstill and very noisy
        // at walking speed / poor accuracy. Tying the distance threshold to the
        // reported accuracy means a sloppy fix must prove a larger, real move
        // before it is allowed to swing the compass. Between accepted updates we
        // hold the last good heading rather than flicker.
        val movedEnough = lastHeadingLoc?.let { last ->
            loc.distanceTo(last) >= maxOf(MIN_HEADING_MOVE_M, 2f * maxOf(acc, 0f))
        } ?: true
        if (loc.hasBearing() && acc in 0f..MIN_HEADING_ACC &&
            rawMps >= MIN_HEADING_SPEED && movedEnough
        ) {
            headingDeg = loc.bearing
            headingSource = HeadingSource.GPS_COURSE
            lastHeadingLoc = loc
            lastGpsHeadingElapsed = now
        } else if (headingDeg == null) {
            headingSource = HeadingSource.WAITING
        }

        emit()
    }

    @Deprecated("Required by LocationListener on old APIs")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        emit()
    }

    // -------------------- SensorEventListener (magnetometer fallback) --------

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val r = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(r, event.values)
        SensorManager.getOrientation(r, orientation)
        var az = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (az < 0) az += 360f
        magAzimuth = az
        // The magnetometer reports where the *phone* points, not the direction
        // of travel. On a mounted phone these roughly agree; we only use it as a
        // labelled fallback when GPS course is unavailable.
        maybeApplyMagFallback()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // -------------------- helpers --------------------

    private fun maybeApplyMagFallback() {
        if (!magFallback) return
        val now = SystemClock.elapsedRealtime()
        val gpsHeadingStale = now - lastGpsHeadingElapsed > HEADING_STALE_MS
        if (gpsHeadingStale) {
            magAzimuth?.let {
                headingDeg = it
                headingSource = HeadingSource.MAGNETOMETER
                emit()
            }
        }
    }

    private fun currentFixState(now: Long): FixState {
        if (lastFixElapsed == 0L || now - lastFixElapsed > NO_FIX_TIMEOUT_MS) return FixState.NO_FIX
        val acc = prevLoc?.takeIf { it.hasAccuracy() }?.accuracy ?: return FixState.WEAK
        return if (acc <= MIN_HEADING_ACC) FixState.OK else FixState.WEAK
    }

    private fun displaySpeed(now: Long): Float {
        // Force 0 after a reasonable stopped timeout, or when the fix is gone.
        val stoppedTooLong = now - lastMovingElapsed > STOP_TIMEOUT_MS
        val noFix = now - lastFixElapsed > NO_FIX_TIMEOUT_MS
        return if (stoppedTooLong || noFix) 0f else smoothedMps
    }

    private fun emit() {
        val now = SystemClock.elapsedRealtime()
        val avg = if (movingTimeMs > 0) (movingDistM / (movingTimeMs / 1000.0)).toFloat() else 0f
        // If GPS heading went stale and fallback is off, reflect "waiting".
        if (headingSource == HeadingSource.GPS_COURSE &&
            now - lastGpsHeadingElapsed > HEADING_STALE_MS && !magFallback
        ) {
            headingSource = HeadingSource.WAITING
        }
        onState(
            SpeedState(
                speedMps = displaySpeed(now),
                headingDeg = headingDeg,
                headingSource = headingSource,
                fix = currentFixState(now),
                accuracyM = prevLoc?.takeIf { it.hasAccuracy() }?.accuracy ?: -1f,
                tripDistanceM = tripDistanceM,
                tripMaxMps = tripMaxMps,
                tripAvgMovingMps = avg,
            )
        )
    }

    private fun persistTrip() {
        prefs.tripDistanceM = tripDistanceM
        prefs.tripMaxMps = tripMaxMps
        prefs.tripMovingDistM = movingDistM
        prefs.tripMovingTimeMs = movingTimeMs
    }

    companion object {
        private const val MIN_TIME_MS = 1000L      // request ~1 Hz GPS
        private const val SMOOTH_ALPHA = 0.35f     // EMA factor

        private const val MOVING_SPEED = 0.7f      // m/s (~1.6 mph) = "moving"
        private const val STOP_TIMEOUT_MS = 3000L  // show 0 after this when stopped
        private const val NO_FIX_TIMEOUT_MS = 5000L

        private const val MIN_HEADING_SPEED = 2.0f // m/s (~4.5 mph)
        private const val MIN_HEADING_ACC = 25f    // meters
        private const val MIN_HEADING_MOVE_M = 10f
        private const val HEADING_STALE_MS = 5000L

        private const val MAX_STEP_M = 200f        // reject absurd single-fix jumps
    }
}
