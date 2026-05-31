package com.american2day.truespeed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Movable, circular floating speedometer shown over other apps. Runs as a
 * foreground service so Android keeps it (and its GPS reads) alive while you are
 * in another app. Requires the standard SYSTEM_ALERT_WINDOW (overlay)
 * permission — the caller checks/requests it first; we never try to bypass it.
 */
class OverlayService : Service(), LocationListener {

    private lateinit var wm: WindowManager
    private lateinit var prefs: Prefs
    private var bubble: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    private lateinit var lm: LocationManager
    private var smoothed = 0f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        startInForeground()
        addBubble()
        requestGps()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            // The bubble is only useful when you've left the app; MainActivity
            // hides it while it's in the foreground and shows it on background.
            ACTION_HIDE -> bubble?.visibility = View.GONE
            ACTION_SHOW -> bubble?.visibility = View.VISIBLE
            // Initial start: toggled from inside the app, so come up hidden and
            // let MainActivity.onStop() reveal it once the user leaves.
            else -> if (intent?.getBooleanExtra(EXTRA_START_HIDDEN, false) == true) {
                bubble?.visibility = View.GONE
            }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val channelId = "truespeed_bubble"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId, getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            pendingFlags(),
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            pendingFlags(),
        )
        val notif: Notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notif_text))
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .build()
        startForeground(NOTIF_ID, notif)
    }

    private fun pendingFlags(): Int {
        var f = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) f = f or PendingIntent.FLAG_IMMUTABLE
        return f
    }

    private fun addBubble() {
        val scale = prefs.bubbleScale.coerceIn(MIN_SCALE, MAX_SCALE)
        val size = (resources.displayMetrics.density * BASE_DP * scale).roundToInt()
        val tv = TextView(this).apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_bg)
            setTextColor(displayColor())
            textSize = BASE_TEXT_SP * scale
            text = "0\n${prefs.unit.speedLabel}"
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }
        val scaleDetector = ScaleGestureDetector(this, ScaleHandler())
        tv.setOnTouchListener(DragHandler(lp, scaleDetector))
        wm.addView(tv, lp)
        bubble = tv
        params = lp
    }

    /** Resize the bubble (and its text) for a pinch factor; persists the scale. */
    private fun applyScale(scale: Float) {
        val s = scale.coerceIn(MIN_SCALE, MAX_SCALE)
        prefs.bubbleScale = s
        val px = (resources.displayMetrics.density * BASE_DP * s).roundToInt()
        val lp = params ?: return
        val tv = bubble ?: return
        lp.width = px
        lp.height = px
        tv.textSize = BASE_TEXT_SP * s
        runCatching { wm.updateViewLayout(tv, lp) }
    }

    private fun displayColor(): Int {
        val colors = MainActivity.DISPLAY_COLORS
        val idx = prefs.colorIndex.coerceIn(0, colors.size - 1)
        return colors[idx]
    }

    private fun requestGps() {
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this)
        } catch (_: SecurityException) {
        }
    }

    override fun onLocationChanged(loc: Location) {
        val raw = (if (loc.hasSpeed()) loc.speed else 0f).coerceAtLeast(0f)
        smoothed += 0.35f * (raw - smoothed)
        val shown = if (smoothed < 0.7f) 0f else smoothed
        val unit = prefs.unit
        bubble?.apply {
            setTextColor(displayColor())
            text = "${unit.speed(shown)}\n${unit.speedLabel}"
        }
    }

    @Deprecated("Required by LocationListener on old APIs")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        try {
            lm.removeUpdates(this)
        } catch (_: SecurityException) {
        }
        bubble?.let { runCatching { wm.removeView(it) } }
        bubble = null
    }

    /** Pinch (two fingers) grows/shrinks the bubble and its text. */
    private inner class ScaleHandler : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            applyScale(prefs.bubbleScale * detector.scaleFactor)
            return true
        }
    }

    /** Drag the bubble by following the finger; hold to close; tap to open. */
    private inner class DragHandler(
        private val lp: WindowManager.LayoutParams,
        private val scaleDetector: ScaleGestureDetector,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false
        private var longPressed = false
        private val handler = Handler(Looper.getMainLooper())
        private val longPress = Runnable {
            longPressed = true
            // Hold-to-close: a long press on the bubble shuts it down.
            stopSelf()
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            scaleDetector.onTouchEvent(e)
            // While pinch-zooming, suppress drag/tap/hold so the bubble only
            // resizes (a two-finger gesture isn't a tap or a move).
            if (scaleDetector.isInProgress) {
                handler.removeCallbacks(longPress)
                moved = true
                return true
            }
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = lp.x; startY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    moved = false
                    longPressed = false
                    handler.postDelayed(longPress, LONG_PRESS_MS)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - touchX).toInt()
                    val dy = (e.rawY - touchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) {
                        moved = true
                        handler.removeCallbacks(longPress)
                    }
                    lp.x = startX + dx
                    lp.y = startY + dy
                    runCatching { wm.updateViewLayout(v, lp) }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (!moved && !longPressed) {
                        // Tap (not drag, not hold) opens the full app.
                        startActivity(
                            Intent(this@OverlayService, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            return true
        }
    }

    companion object {
        private const val NOTIF_ID = 42
        private const val LONG_PRESS_MS = 600L
        private const val BASE_DP = 96f      // bubble diameter at scale 1.0
        private const val BASE_TEXT_SP = 22f
        private const val MIN_SCALE = 0.6f
        private const val MAX_SCALE = 3.0f
        const val ACTION_STOP = "com.american2day.truespeed.STOP_BUBBLE"
        const val ACTION_HIDE = "com.american2day.truespeed.HIDE_BUBBLE"
        const val ACTION_SHOW = "com.american2day.truespeed.SHOW_BUBBLE"
        private const val EXTRA_START_HIDDEN = "start_hidden"

        /** True while the service is alive (read from the UI, same process). */
        @Volatile
        var isRunning = false
            private set

        fun start(ctx: Context, hidden: Boolean = false) {
            val i = Intent(ctx, OverlayService::class.java)
                .putExtra(EXTRA_START_HIDDEN, hidden)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, OverlayService::class.java))
        }

        /** Command the running service to hide/show its bubble. */
        fun hide(ctx: Context) = command(ctx, ACTION_HIDE)
        fun show(ctx: Context) = command(ctx, ACTION_SHOW)

        private fun command(ctx: Context, action: String) {
            if (!isRunning) return
            ctx.startService(Intent(ctx, OverlayService::class.java).setAction(action))
        }
    }
}
