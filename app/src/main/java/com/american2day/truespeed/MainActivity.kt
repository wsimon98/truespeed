package com.american2day.truespeed

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var view: SpeedometerView
    private lateinit var status: TextView
    private lateinit var tripVal: TextView
    private lateinit var tripUnit: TextView
    private lateinit var maxVal: TextView
    private lateinit var avgVal: TextView
    private lateinit var tracker: SpeedTracker
    private lateinit var gestures: GestureDetector

    // Frames + value texts that should follow the swipe-selected colour.
    private val tintedFrames = mutableListOf<View>()
    private val tintedTexts = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Requirement: keep the screen awake on the speedometer screen. This is
        // a window flag, so it needs no permission.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        prefs = Prefs(this)
        view = findViewById(R.id.speedometer)
        status = findViewById(R.id.status)
        tripVal = findViewById(R.id.tripVal)
        tripUnit = findViewById(R.id.tripUnit)
        maxVal = findViewById(R.id.maxVal)
        avgVal = findViewById(R.id.avgVal)

        val btnTrip = findViewById<Button>(R.id.btnTrip)
        val btnBubble = findViewById<Button>(R.id.btnBubble)
        btnTrip.setOnClickListener {
            tracker.resetTrip()
            Toast.makeText(this, "Trip reset", Toast.LENGTH_SHORT).show()
        }
        btnBubble.setOnClickListener { toggleBubble() }

        // Things whose colour tracks the swipe-selected display colour.
        tintedTexts.addAll(listOf(tripVal, maxVal, avgVal, btnTrip, btnBubble))
        tintedFrames.addAll(
            listOf(
                findViewById<View>(R.id.boxTrip), findViewById<View>(R.id.boxMax),
                findViewById<View>(R.id.boxAvg), btnTrip, btnBubble,
            )
        )
        applyColor(currentColor())

        gestures = GestureDetector(this, GestureListener())
        view.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }

        tracker = SpeedTracker(this, prefs) { state -> render(state) }

        ensureLocationPermission()
    }

    override fun onStart() {
        super.onStart()
        // App is in the foreground now: the floating bubble would just overlap
        // the full speedometer, so hide it while we're visible.
        OverlayService.hide(this)
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) tracker.start()
    }

    override fun onPause() {
        super.onPause()
        tracker.stop()
    }

    override fun onStop() {
        super.onStop()
        // App went to the background: bring the bubble back (if it's running).
        OverlayService.show(this)
    }

    // -------------------- rendering --------------------

    private fun render(s: SpeedState) {
        val unit = prefs.unit
        val headingActive = s.headingSource != HeadingSource.WAITING && s.headingDeg != null
        view.update(unit.speed(s.speedMps).toString(), unit.speedLabel, s.headingDeg, headingActive)

        status.text = buildString {
            append(
                when (s.fix) {
                    FixState.OK -> getString(R.string.gps_ok)
                    FixState.WEAK -> getString(R.string.gps_weak)
                    FixState.NO_FIX -> getString(R.string.gps_waiting)
                }
            )
            if (s.accuracyM >= 0) {
                append("  ±")
                if (unit == Speedo.MPH) append("${Math.round(s.accuracyM * 3.28084f)}ft")
                else append("${Math.round(s.accuracyM)}m")
            }
            append("   ")
            append(
                when (s.headingSource) {
                    HeadingSource.GPS_COURSE -> getString(R.string.heading_gps)
                    HeadingSource.MAGNETOMETER -> getString(R.string.heading_mag)
                    HeadingSource.WAITING -> getString(R.string.heading_waiting)
                }
            )
        }

        tripVal.text = String.format("%.2f", unit.distance(s.tripDistanceM))
        tripUnit.text = unit.distLabel.uppercase()
        maxVal.text = unit.speed(s.tripMaxMps).toString()
        avgVal.text = unit.speed(s.tripAvgMovingMps).toString()
    }

    // -------------------- gestures --------------------

    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onLongPress(e: MotionEvent) {
            showMenu()
        }

        override fun onFling(
            e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float,
        ): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            val dy = e2.y - e1.y
            if (abs(dx) > abs(dy) && abs(dx) > 120 && abs(velocityX) > 200) {
                cycleColor(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    }

    private fun cycleColor(dir: Int) {
        val n = DISPLAY_COLORS.size
        val next = ((prefs.colorIndex + dir) % n + n) % n
        prefs.colorIndex = next
        applyColor(currentColor())
    }

    private fun currentColor(): Int =
        DISPLAY_COLORS[prefs.colorIndex.coerceIn(0, DISPLAY_COLORS.size - 1)]

    /** Recolour the gauge, the stat digits and the HUD frames in one place. */
    private fun applyColor(color: Int) {
        view.setDisplayColor(color)
        for (t in tintedTexts) t.setTextColor(color)
        val strokePx = (1.5f * resources.displayMetrics.density).toInt()
        val stroke = withAlpha(color, 0xB0)
        val fill = withAlpha(color, 0x14)
        for (v in tintedFrames) {
            (v.background as? GradientDrawable)?.apply {
                mutate()
                setStroke(strokePx, stroke)
                setColor(fill)
            }
        }
    }

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))

    // -------------------- long-press menu --------------------

    private fun showMenu() {
        val unit = prefs.unit
        val items = arrayOf(
            getString(if (unit == Speedo.MPH) R.string.menu_units_kph else R.string.menu_units_mph),
            getString(if (prefs.magFallback) R.string.menu_mag_off else R.string.menu_mag_on),
            getString(if (OverlayService.isRunning) R.string.menu_bubble_stop else R.string.menu_bubble_start),
            getString(R.string.menu_update),
            getString(R.string.menu_about),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        prefs.unit = if (unit == Speedo.MPH) Speedo.KPH else Speedo.MPH
                    }
                    1 -> tracker.setMagFallback(!prefs.magFallback)
                    2 -> toggleBubble()
                    3 -> UpdateChecker.check(this, announceNoUpdate = true)
                    4 -> showAbout()
                }
            }
            .show()
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(R.string.app_name)
            .setMessage(
                "TrueSpeed ${BuildConfig.VERSION_NAME}\n\n" +
                    "GPS speedometer. No ads, no accounts, no tracking.\n" +
                    "Speed and heading come from your device GPS."
            )
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    // -------------------- floating bubble --------------------

    private fun toggleBubble() {
        if (OverlayService.isRunning) {
            OverlayService.stop(this)
            return
        }
        if (!canDrawOverlays()) {
            Toast.makeText(this, R.string.overlay_needed, Toast.LENGTH_LONG).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName"),
                    )
                )
            }
            return
        }
        // We're in the foreground right now, so start it hidden; onStop() reveals
        // it once the user leaves the app.
        OverlayService.start(this, hidden = true)
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    // -------------------- permissions --------------------

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureLocationPermission() {
        if (hasLocationPermission()) return
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQ_LOCATION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION &&
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            tracker.start()
        } else {
            status.text = getString(R.string.gps_waiting)
        }
    }

    companion object {
        private const val REQ_LOCATION = 100

        /** Swipe-selectable display colors. Index persists in Prefs. */
        val DISPLAY_COLORS = intArrayOf(
            Color.WHITE,
            Color.parseColor("#FF3B30"), // red
            Color.parseColor("#0A84FF"), // blue
            Color.parseColor("#FFD60A"), // yellow
            Color.parseColor("#30D158"), // green
            Color.parseColor("#FF9F0A"), // orange
            Color.parseColor("#BF5AF2"), // purple
        )
    }
}
