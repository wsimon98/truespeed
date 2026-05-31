package com.american2day.truespeed

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import androidx.core.content.res.ResourcesCompat
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Sci-fi HUD speedometer. The big seven-segment number stays fixed and centered;
 * a glowing dual compass ring with fine radar ticks rotates around it
 * (heading-up), N/S/E/W riding the ring. Everything is laid out relative to the
 * view center and the smaller of width/height, so it works in any orientation.
 * The glow colour follows the user's swipe-selected display colour.
 */
class SpeedometerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private var displayColor = Color.parseColor("#FF3B30")
    private var speedText = "0"
    private var unitText = "MPH"
    private var headingDeg: Float? = null
    private var ringActive = false

    private val sevenSeg: Typeface =
        ResourcesCompat.getFont(context, R.font.dseg7_bold) ?: Typeface.MONOSPACE

    private val speedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sevenSeg
        textAlign = Paint.Align.CENTER
    }
    private val speedGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = sevenSeg
        textAlign = Paint.Align.CENTER
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val cardinalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    init {
        // Blur mask filters and shadow layers need software rendering.
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setDisplayColor(color: Int) {
        displayColor = color
        invalidate()
    }

    /** Push a new frame of state. heading == null keeps the ring north-up + dim. */
    fun update(speed: String, unit: String, heading: Float?, headingActive: Boolean) {
        speedText = speed
        unitText = unit
        headingDeg = heading
        ringActive = headingActive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)

        val left = paddingLeft.toFloat()
        val top = paddingTop.toFloat()
        val right = (width - paddingRight).toFloat()
        val bottom = (height - paddingBottom).toFloat()
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val shorter = min(right - left, bottom - top)
        val base = shorter / 2f

        val color = if (ringActive) displayColor else dim(displayColor, 0.4f)
        val faint = dim(color, 0.45f)
        val glow = withAlpha(color, 90)

        val rOuter = base * 0.95f
        val rInner = base * 0.80f
        val labelR = rInner * 0.99f

        val rot = headingDeg ?: 0f
        canvas.save()
        canvas.rotate(-rot, cx, cy)

        // --- glow halo behind the rings ---
        ringGlowPaint.color = glow
        ringGlowPaint.strokeWidth = shorter * 0.03f
        ringGlowPaint.maskFilter = BlurMaskFilter(shorter * 0.025f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawCircle(cx, cy, rInner, ringGlowPaint)
        canvas.drawCircle(cx, cy, rOuter, ringGlowPaint)

        // --- crisp rings: thin outer, bold inner ---
        ringPaint.color = faint
        ringPaint.strokeWidth = shorter * 0.006f
        canvas.drawCircle(cx, cy, rOuter, ringPaint)
        ringPaint.color = color
        ringPaint.strokeWidth = shorter * 0.018f
        canvas.drawCircle(cx, cy, rInner, ringPaint)

        // --- radar tick band between the rings (fine every 6°, long every 30°) ---
        var a = 0
        while (a < 360) {
            val major = a % 30 == 0
            tickPaint.color = if (major) color else faint
            tickPaint.strokeWidth = if (major) shorter * 0.010f else shorter * 0.005f
            val outer = rOuter - shorter * 0.008f
            val inner = if (major) rInner + shorter * 0.015f else rOuter - shorter * 0.055f
            val rad = Math.toRadians(a.toDouble())
            val s = sin(rad).toFloat()
            val c = cos(rad).toFloat()
            canvas.drawLine(cx + inner * s, cy - inner * c, cx + outer * s, cy - outer * c, tickPaint)
            a += 6
        }

        // --- cardinal letters just inside the bold ring ---
        cardinalPaint.textSize = shorter * 0.075f
        cardinalPaint.color = color
        drawCardinal(canvas, "N", 0.0, cx, cy, labelR)
        drawCardinal(canvas, "E", 90.0, cx, cy, labelR)
        drawCardinal(canvas, "S", 180.0, cx, cy, labelR)
        drawCardinal(canvas, "W", 270.0, cx, cy, labelR)
        canvas.restore()

        // --- fixed travel-direction marker at the very top ---
        if (ringActive) {
            val tip = cy - rOuter - shorter * 0.005f
            val baseY = cy - rOuter + shorter * 0.04f
            val half = shorter * 0.028f
            markerPaint.color = displayColor
            markerPaint.maskFilter = BlurMaskFilter(shorter * 0.012f, BlurMaskFilter.Blur.NORMAL)
            val path = android.graphics.Path().apply {
                moveTo(cx, tip); lineTo(cx - half, baseY); lineTo(cx + half, baseY); close()
            }
            canvas.drawPath(path, markerPaint)
            markerPaint.maskFilter = null
            canvas.drawPath(path, markerPaint)
        }

        // --- big seven-segment number with glow (never rotates) ---
        val numColor = if (ringActive) displayColor else dim(displayColor, 0.5f)
        speedPaint.textSize = shorter * 0.34f
        val fm = speedPaint.fontMetrics
        val baseline = cy - (fm.ascent + fm.descent) / 2f - shorter * 0.02f

        speedGlowPaint.textSize = speedPaint.textSize
        speedGlowPaint.color = withAlpha(numColor, 120)
        speedGlowPaint.maskFilter = BlurMaskFilter(shorter * 0.03f, BlurMaskFilter.Blur.NORMAL)
        canvas.drawText(speedText, cx, baseline, speedGlowPaint)
        speedPaint.color = numColor
        canvas.drawText(speedText, cx, baseline, speedPaint)

        // --- unit label below the number ---
        unitPaint.color = numColor
        unitPaint.textSize = shorter * 0.085f
        canvas.drawText(unitText, cx, baseline + shorter * 0.165f, unitPaint)
    }

    private fun drawCardinal(c: Canvas, label: String, deg: Double, cx: Float, cy: Float, r: Float) {
        val rad = Math.toRadians(deg)
        val x = cx + r * sin(rad).toFloat()
        val y = cy - r * cos(rad).toFloat()
        val fm = cardinalPaint.fontMetrics
        c.drawText(label, x, y - (fm.ascent + fm.descent) / 2f, cardinalPaint)
    }

    private fun dim(color: Int, f: Float): Int = Color.rgb(
        (Color.red(color) * f).toInt(),
        (Color.green(color) * f).toInt(),
        (Color.blue(color) * f).toInt(),
    )

    private fun withAlpha(color: Int, a: Int): Int =
        Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
}
